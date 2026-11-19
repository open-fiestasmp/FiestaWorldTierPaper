package ca.spottedleaf.moonrise.patches.chunk_system.io;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Fair, asynchronous leases for complete Anvil regions. Chunk I/O uses shared
 * leases; a tiering operation must hold an exclusive lease before touching the
 * corresponding terrain, entity, and POI files.
 */
public final class FiestaRegionLeaseManager {

    private static final Map<RegionKey, State> STATES = new HashMap<>();

    private FiestaRegionLeaseManager() {}

    public static CompletableFuture<Lease> acquireShared(final Path dimensionRoot, final int regionX, final int regionZ) {
        return acquire(dimensionRoot, regionX, regionZ, false);
    }

    public static CompletableFuture<Lease> acquireExclusive(final Path dimensionRoot, final int regionX, final int regionZ) {
        return acquire(dimensionRoot, regionX, regionZ, true);
    }

    /**
     * Acquires a cross-process proof lock for the Python offload child. Linux
     * FileChannel locks share the POSIX record-lock namespace with Python's
     * fcntl.lockf probe.
     */
    public static ProcessLease acquireProcessLease(final Path dimensionRoot, final int regionX, final int regionZ) throws IOException {
        final Path root = dimensionRoot.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Unsafe Fiesta dimension root for process lease: " + root);
        }

        final Path worldTier = root.resolve(".world-tier");
        final Path leaseDirectory = worldTier.resolve("paper-leases");
        createPrivateDirectory(worldTier);
        createPrivateDirectory(leaseDirectory);

        final Path lockPath = leaseDirectory.resolve("r." + regionX + "." + regionZ + ".lock");
        if (Files.isSymbolicLink(lockPath)) {
            throw new IOException("Fiesta Paper process lease path is a symlink: " + lockPath);
        }
        final FileChannel channel = FileChannel.open(
            lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS
        );
        try {
            Files.setPosixFilePermissions(lockPath, Set.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE
            ));
            final FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (final OverlappingFileLockException ex) {
                throw new IOException("Fiesta Paper process lease is already held in this JVM", ex);
            }
            if (lock == null) {
                throw new IOException("Fiesta Paper process lease is already held by another process");
            }
            return new ProcessLease(channel, lock);
        } catch (final Throwable thr) {
            try {
                channel.close();
            } catch (final IOException closeFailure) {
                thr.addSuppressed(closeFailure);
            }
            if (thr instanceof IOException ioException) {
                throw ioException;
            }
            if (thr instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (thr instanceof Error error) {
                throw error;
            }
            throw new IOException("Could not acquire Fiesta Paper process lease", thr);
        }
    }

    private static void createPrivateDirectory(final Path directory) throws IOException {
        if (Files.isSymbolicLink(directory)) {
            throw new IOException("Fiesta Paper lease directory is a symlink: " + directory);
        }
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(Set.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE
            )));
        }
        if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Fiesta Paper lease path is not a directory: " + directory);
        }
        Files.setPosixFilePermissions(directory, Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE
        ));
    }

    private static CompletableFuture<Lease> acquire(final Path dimensionRoot, final int regionX, final int regionZ,
                                                    final boolean exclusive) {
        final RegionKey key = new RegionKey(dimensionRoot.toAbsolutePath().normalize(), regionX, regionZ);
        final CompletableFuture<Lease> future = new CompletableFuture<>();
        Lease immediate = null;

        synchronized (STATES) {
            final State state = STATES.computeIfAbsent(key, ignored -> new State());
            if (!state.exclusive && state.waiters.isEmpty() && (!exclusive || state.readers == 0)) {
                if (exclusive) {
                    state.exclusive = true;
                } else {
                    ++state.readers;
                }
                immediate = new Lease(key, state, exclusive);
            } else {
                state.waiters.addLast(new Waiter(exclusive, future));
            }
        }

        if (immediate != null) {
            complete(future, immediate);
        }
        return future;
    }

    private static void release(final Lease lease) {
        final List<Grant> grants = new ArrayList<>();

        synchronized (STATES) {
            final State state = lease.state;
            if (lease.exclusive) {
                if (!state.exclusive) {
                    throw new IllegalStateException("Exclusive Fiesta region lease was already released");
                }
                state.exclusive = false;
            } else {
                if (state.readers <= 0) {
                    throw new IllegalStateException("Shared Fiesta region lease count underflow");
                }
                --state.readers;
            }

            while (!state.exclusive && !state.waiters.isEmpty()) {
                final Waiter next = state.waiters.peekFirst();
                if (next.future.isCancelled()) {
                    state.waiters.removeFirst();
                    continue;
                }
                if (next.exclusive) {
                    if (state.readers != 0) {
                        break;
                    }
                    state.waiters.removeFirst();
                    state.exclusive = true;
                    grants.add(new Grant(next.future, new Lease(lease.key, state, true)));
                    break;
                }

                state.waiters.removeFirst();
                ++state.readers;
                grants.add(new Grant(next.future, new Lease(lease.key, state, false)));
            }

            if (!state.exclusive && state.readers == 0 && state.waiters.isEmpty()) {
                STATES.remove(lease.key, state);
            }
        }

        for (final Grant grant : grants) {
            complete(grant.future, grant.lease);
        }
    }

    private static void complete(final CompletableFuture<Lease> future, final Lease lease) {
        if (!future.complete(lease)) {
            lease.close();
        }
    }

    private static final class State {
        private int readers;
        private boolean exclusive;
        private final ArrayDeque<Waiter> waiters = new ArrayDeque<>();
    }

    private static final class Waiter {
        private final boolean exclusive;
        private final CompletableFuture<Lease> future;

        private Waiter(final boolean exclusive, final CompletableFuture<Lease> future) {
            this.exclusive = exclusive;
            this.future = future;
        }
    }

    private static final class Grant {
        private final CompletableFuture<Lease> future;
        private final Lease lease;

        private Grant(final CompletableFuture<Lease> future, final Lease lease) {
            this.future = future;
            this.lease = lease;
        }
    }

    private record RegionKey(Path dimensionRoot, int regionX, int regionZ) {}

    public static final class ProcessLease implements AutoCloseable {
        private final FileChannel channel;
        private final FileLock lock;
        private final AtomicBoolean closed = new AtomicBoolean();

        private ProcessLease(final FileChannel channel, final FileLock lock) {
            this.channel = channel;
            this.lock = lock;
        }

        @Override
        public void close() throws IOException {
            if (this.closed.compareAndSet(false, true)) {
                try {
                    this.lock.release();
                } finally {
                    this.channel.close();
                }
            }
        }
    }

    public static final class Lease implements AutoCloseable {
        private final RegionKey key;
        private final State state;
        private final boolean exclusive;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Lease(final RegionKey key, final State state, final boolean exclusive) {
            this.key = key;
            this.state = state;
            this.exclusive = exclusive;
        }

        public boolean isExclusive() {
            return this.exclusive;
        }

        @Override
        public void close() {
            if (this.closed.compareAndSet(false, true)) {
                release(this);
            }
        }
    }
}
