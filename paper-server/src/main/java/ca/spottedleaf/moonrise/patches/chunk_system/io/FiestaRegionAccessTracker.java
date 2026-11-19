package ca.spottedleaf.moonrise.patches.chunk_system.io;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** Tracks region-level chunk I/O use for conservative cold-region selection. */
public final class FiestaRegionAccessTracker {

    private static final Logger LOGGER = Logger.getLogger(FiestaRegionAccessTracker.class.getName());
    private static final long WRITE_INTERVAL_MILLIS = configuredWriteIntervalMillis();
    private static final ConcurrentHashMap<RegionKey, Long> LAST_ACCESS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<RegionKey, Long> LAST_PERSISTED = new ConcurrentHashMap<>();
    private static final Set<Path> UNHEALTHY_ROOTS = ConcurrentHashMap.newKeySet();

    private FiestaRegionAccessTracker() { }

    private static long configuredWriteIntervalMillis() {
        try {
            return Math.max(1_000L, Long.parseLong(
                System.getProperty("fiesta.worldTier.accessWriteIntervalMillis", "60000")));
        } catch (final NumberFormatException ex) {
            return 1_000L;
        }
    }

    /** Record a read/write request before it waits for the region I/O lease. */
    public static void recordAccess(final Path dimensionRoot, final int regionX, final int regionZ) {
        final Path root = dimensionRoot.toAbsolutePath().normalize();
        final RegionKey key = new RegionKey(root, regionX, regionZ);
        final long now = System.currentTimeMillis();
        LAST_ACCESS.merge(key, now, Math::max);
        if (UNHEALTHY_ROOTS.contains(root)) {
            return;
        }

        try {
            LAST_PERSISTED.compute(key, (ignored, persisted) -> {
                try {
                    final long previous = persisted == null ? readTimestamp(key) : persisted;
                    if (previous > 0L && now - previous < WRITE_INTERVAL_MILLIS) {
                        return previous;
                    }
                    writeTimestamp(key, now);
                    return now;
                } catch (final IOException ex) {
                    throw new AccessWriteFailure(ex);
                }
            });
        } catch (final AccessWriteFailure ex) {
            if (UNHEALTHY_ROOTS.add(root)) {
                LOGGER.log(Level.WARNING, "Fiesta region access tracking failed for " + root
                    + "; cold offload is disabled for this dimension", ex.getCause());
            }
        }
    }

    public static boolean isCold(final Path dimensionRoot, final int regionX, final int regionZ,
                                 final long coldAgeMillis) {
        return isCold(dimensionRoot, regionX, regionZ, coldAgeMillis, System.currentTimeMillis());
    }

    public static boolean isCold(final Path dimensionRoot, final int regionX, final int regionZ,
                                  final long coldAgeMillis, final long nowMillis) {
        if (coldAgeMillis <= 0L || nowMillis <= 0L) {
            return false;
        }
        final Path root = dimensionRoot.toAbsolutePath().normalize();
        if (UNHEALTHY_ROOTS.contains(root)) {
            return false;
        }
        final RegionKey key = new RegionKey(root, regionX, regionZ);
        final long inMemory = LAST_ACCESS.getOrDefault(key, 0L);
        final long persisted;
        try {
            persisted = readTimestamp(key);
        } catch (final IOException ex) {
            if (UNHEALTHY_ROOTS.add(root)) {
                LOGGER.log(Level.WARNING, "Fiesta region access marker could not be read for " + root
                    + "; cold offload is disabled for this dimension", ex);
            }
            return false;
        }
        final long lastAccess = Math.max(inMemory, persisted);
        return lastAccess > 0L && nowMillis >= lastAccess && nowMillis - lastAccess >= coldAgeMillis;
    }

    public static boolean isTrackingHealthy(final Path dimensionRoot) {
        return !UNHEALTHY_ROOTS.contains(dimensionRoot.toAbsolutePath().normalize());
    }

    private static long readTimestamp(final RegionKey key) throws IOException {
        final Path marker = markerPath(key);
        validateAccessDirectories(key);
        if (!Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
            return 0L;
        }
        if (Files.isSymbolicLink(marker) || !Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
            || Files.size(marker) > 32L) {
            throw new IOException("Unsafe Fiesta region access marker: " + marker);
        }
        final Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(marker, LinkOption.NOFOLLOW_LINKS);
        if (permissions.stream().anyMatch(permission -> switch (permission) {
            case GROUP_READ, GROUP_WRITE, GROUP_EXECUTE, OTHERS_READ, OTHERS_WRITE, OTHERS_EXECUTE -> true;
            default -> false;
        })) {
            throw new IOException("Fiesta region access marker permissions are too broad: " + marker);
        }
        final String value = Files.readString(marker).trim();
        try {
            final long timestamp = Long.parseLong(value);
            if (timestamp <= 0L) {
                throw new IOException("Invalid Fiesta region access timestamp: " + marker);
            }
            final long now = System.currentTimeMillis();
            return timestamp > now + TimeUnit.MINUTES.toMillis(5) ? now : timestamp;
        } catch (final NumberFormatException ex) {
            throw new IOException("Invalid Fiesta region access timestamp: " + marker, ex);
        }
    }

    private static void writeTimestamp(final RegionKey key, final long timestamp) throws IOException {
        final Path root = key.dimensionRoot;
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Unsafe Fiesta dimension root for access tracking: " + root);
        }
        final Path worldTier = root.resolve(".world-tier");
        final Path accessDirectory = worldTier.resolve("access");
        createPrivateDirectory(worldTier);
        createPrivateDirectory(accessDirectory);
        validateAccessDirectories(key);

        final Path marker = markerPath(key);
        final Path temporary = Files.createTempFile(accessDirectory, marker.getFileName().toString(), ".tmp",
            PosixFilePermissions.asFileAttribute(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)));
        try {
            try (final FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                final ByteBuffer data = ByteBuffer.wrap((Long.toString(timestamp) + "\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                while (data.hasRemaining()) {
                    channel.write(data);
                }
                channel.force(true);
            }
            try {
                Files.move(temporary, marker, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (final AtomicMoveNotSupportedException ex) {
                throw new IOException("Atomic Fiesta access-marker publication is unsupported", ex);
            }
            try (final FileChannel directory = FileChannel.open(accessDirectory, StandardOpenOption.READ)) {
                directory.force(true);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void createPrivateDirectory(final Path directory) throws IOException {
        if (Files.isSymbolicLink(directory)) {
            throw new IOException("Fiesta access tracking directory is a symlink: " + directory);
        }
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(Set.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE
            )));
        }
        if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Fiesta access tracking path is not a directory: " + directory);
        }
        Files.setPosixFilePermissions(directory, Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE
        ));
    }

    private static void validateAccessDirectories(final RegionKey key) throws IOException {
        final Path root = key.dimensionRoot;
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Unsafe Fiesta dimension root for access tracking: " + root);
        }
        for (final Path directory : new Path[] { root.resolve(".world-tier"), root.resolve(".world-tier/access") }) {
            if (Files.isSymbolicLink(directory)) {
                throw new IOException("Fiesta access tracking directory is a symlink: " + directory);
            }
            if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                || !directory.toRealPath(LinkOption.NOFOLLOW_LINKS).startsWith(root.toRealPath(LinkOption.NOFOLLOW_LINKS))) {
                throw new IOException("Unsafe Fiesta access tracking directory: " + directory);
            }
            final Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(directory, LinkOption.NOFOLLOW_LINKS);
            if (permissions.stream().anyMatch(permission -> switch (permission) {
                case GROUP_READ, GROUP_WRITE, GROUP_EXECUTE, OTHERS_READ, OTHERS_WRITE, OTHERS_EXECUTE -> true;
                default -> false;
            })) {
                throw new IOException("Fiesta access tracking directory permissions are too broad: " + directory);
            }
        }
    }

    private static Path markerPath(final RegionKey key) {
        return key.dimensionRoot.resolve(".world-tier").resolve("access")
            .resolve("r." + key.x + "." + key.z + ".touch");
    }

    private record RegionKey(Path dimensionRoot, int x, int z) { }

    private static final class AccessWriteFailure extends RuntimeException {
        private AccessWriteFailure(final IOException cause) {
            super(cause);
        }
    }
}
