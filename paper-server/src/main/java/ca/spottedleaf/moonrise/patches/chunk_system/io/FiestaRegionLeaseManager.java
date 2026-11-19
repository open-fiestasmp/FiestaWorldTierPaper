package ca.spottedleaf.moonrise.patches.chunk_system.io;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
