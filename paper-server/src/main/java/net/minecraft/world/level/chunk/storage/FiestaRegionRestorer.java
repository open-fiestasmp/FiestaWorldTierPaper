package net.minecraft.world.level.chunk.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Restores a marked cold region before Paper interprets a missing local file as
 * an empty region. This deliberately delegates storage and checksum policy to
 * the separately installed Fiesta World Tier command.
 */
final class FiestaRegionRestorer {
    private static final String COMMAND_PROPERTY = "fiesta.worldTier.command";
    private static final String CONFIG_PROPERTY = "fiesta.worldTier.config";
    private static final String TIMEOUT_PROPERTY = "fiesta.worldTier.restoreTimeoutSeconds";
    private static final int RETRY_EXIT_CODE = 75;
    private static final String DEFAULT_COMMAND = "/usr/local/bin/fiesta-world-tier";
    private static final String DEFAULT_CONFIG = "/etc/fiesta-world-tier/config.json";
    private static final long DEFAULT_TIMEOUT_SECONDS = 300L;
    private static final Pattern MARKER_STATE = Pattern.compile("\\\"state\\\"\\s*:\\s*\\\"(REMOTE_ONLY|EVICTING|RESTORING)\\\"");
    private static final Object[] LOCKS = new Object[256];
    private static final Map<Path, RestoreRetryState> RETRIES = new ConcurrentHashMap<>();

    static {
        for (int i = 0; i < LOCKS.length; ++i) {
            LOCKS[i] = new Object();
        }
    }

    private FiestaRegionRestorer() {}

    /**
     * Any durable marker requires recovery. A surviving terrain file does not
     * prove that eviction completed: a crash may have deleted only the entity,
     * POI, or oversized-chunk members of the bundle. Invalid markers or marker
     * paths also return true so the I/O path fails closed instead of using
     * Moonrise's negative cache.
     */
    static boolean needsRestore(final Path storageFolder, final int chunkX, final int chunkZ) {
        try {
            final Path marker = markerPath(storageFolder, chunkX, chunkZ);
            if (markerAttributesOrNull(marker) == null) {
                return false;
            }
            markerState(marker); // validate the durable state before entering recovery
            return true;
        } catch (final IOException | RuntimeException ignored) {
            // Force the normal I/O path, where the invalid storage path becomes
            // a visible IOException rather than a cached "no region" result.
            return true;
        }
    }

    /**
     * Restores a remote-only region if its marker exists. The caller must still
     * check whether its specific storage component (region/entities/poi) exists:
     * an original bundle may not have contained entity or POI data.
     */
    static boolean restoreIfMarked(
        final Path storageFolder,
        final String dimensionKey,
        final int chunkX,
        final int chunkZ
    ) throws IOException {
        try {
            return restoreIfMarkedInternal(storageFolder, dimensionKey, chunkX, chunkZ);
        } catch (final FiestaWorldTierRestoreException ex) {
            throw ex;
        } catch (final IOException | RuntimeException ex) {
            throw new FiestaWorldTierRestoreException("Fiesta world-tier region recovery failed", ex);
        }
    }

    private static boolean restoreIfMarkedInternal(
        final Path storageFolder,
        final String dimensionKey,
        final int chunkX,
        final int chunkZ
    ) throws IOException {
        final Path marker = markerPath(storageFolder, chunkX, chunkZ);
        final Path dimensionRoot = storageFolder.toAbsolutePath().normalize().getParent();
        if (dimensionRoot == null) {
            throw new IOException("Cannot determine the world-tier dimension root");
        }
        final int regionX = chunkX >> 5;
        final int regionZ = chunkZ >> 5;
        final String regionName = "r." + regionX + "." + regionZ + ".mca";
        final Path terrainRegion = dimensionRoot.resolve("region").resolve(regionName);
        final Object lock = LOCKS[(marker.hashCode() & 0x7fffffff) % LOCKS.length];

        synchronized (lock) {
            final RestoreRetryState retry = RETRIES.computeIfAbsent(marker, ignored -> new RestoreRetryState());
            if (retry.nextAttemptNanos > System.nanoTime()) {
                throw new FiestaWorldTierRestoreException(
                    "Fiesta world-tier restore is waiting for its region retry window: " + regionName, null, true
                );
            }
            try {
                if (markerAttributesOrNull(marker) == null) {
                    RETRIES.remove(marker, retry);
                    return false;
                }
                markerState(marker);
                final BasicFileAttributes localTerrain = attributesOrNull(terrainRegion);
                if (localTerrain != null && !localTerrain.isRegularFile()) {
                    throw new IOException("Local terrain region is not a regular file: " + terrainRegion);
                }
                final Path command = configuredPath(COMMAND_PROPERTY, DEFAULT_COMMAND, true);
                final Path config = configuredPath(CONFIG_PROPERTY, DEFAULT_CONFIG, false);
                final long timeoutSeconds = timeoutSeconds();
                final ProcessBuilder builder = new ProcessBuilder(
                    command.toString(),
                    "restore",
                    "--config", config.toString(),
                    "--dimension-key", dimensionKey,
                    "--dimension-root", dimensionRoot.toString(),
                    "--region-x", Integer.toString(regionX),
                    "--region-z", Integer.toString(regionZ)
                );
                final Map<String, String> environment = builder.environment();
                final String path = environment.getOrDefault("PATH", "/usr/local/bin:/usr/bin:/bin");
                environment.clear();
                environment.put("PATH", path);
                environment.put("LANG", "C");
                environment.put("FIESTA_WORLD_TIER_PAPER", "1");
                builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
                builder.redirectError(ProcessBuilder.Redirect.INHERIT);

                final Process process;
                try {
                    process = builder.start();
                } catch (final IOException ex) {
                    throw new IOException("Could not start the Fiesta world-tier restore command", ex);
                }

                try {
                    if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                        destroyProcessTree(process);
                        throw new IOException("Fiesta world-tier restore timed out after " + timeoutSeconds + " seconds");
                    }
                } catch (final InterruptedException ex) {
                    destroyProcessTree(process);
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while restoring a Fiesta world-tier region", ex);
                }

                final int exitCode = process.exitValue();
                if (exitCode == RETRY_EXIT_CODE) {
                    throw new FiestaWorldTierRestoreException(
                        "Fiesta remote region storage is temporarily unavailable for " + regionName, null, true
                    );
                }
                if (exitCode != 0) {
                    throw new IOException("Fiesta world-tier restore failed with exit code " + exitCode);
                }

                if (markerAttributesOrNull(marker) != null) {
                    throw new IOException("Fiesta world-tier restore command did not clear the marker after publishing local data");
                }
                final BasicFileAttributes attributes = attributesOrNull(terrainRegion);
                if (attributes == null) {
                    throw new IOException("Restore completed without publishing the expected terrain region " + regionName);
                }
                if (!attributes.isRegularFile()) {
                    throw new IOException("Restored terrain region is not a regular file: " + regionName);
                }
                RETRIES.remove(marker, retry);
                return true;
            } catch (final IOException | RuntimeException ex) {
                retry.recordFailure();
                throw new FiestaWorldTierRestoreException("Fiesta world-tier region recovery failed", ex, true);
            }
        }
    }

    private static void destroyProcessTree(final Process process) {
        process.toHandle().descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        try {
            process.waitFor(10L, TimeUnit.SECONDS);
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class RestoreRetryState {
        private int failures;
        private long nextAttemptNanos;

        private void recordFailure() {
            this.failures = Math.min(this.failures + 1, 6);
            final long maximumDelay = Math.min(5_000L, 1_000L << (this.failures - 1));
            final long delay = ThreadLocalRandom.current().nextLong(maximumDelay * 3L / 4L, maximumDelay + 1L);
            this.nextAttemptNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delay);
        }
    }

    private static BasicFileAttributes attributesOrNull(final Path path) throws IOException {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (final NoSuchFileException ex) {
            return null;
        }
    }

    private static BasicFileAttributes markerAttributesOrNull(final Path marker) throws IOException {
        final Path remoteDirectory = marker.getParent();
        final Path stateDirectory = remoteDirectory.getParent();
        final BasicFileAttributes stateAttributes = attributesOrNull(stateDirectory);
        if (stateAttributes == null) {
            return null;
        }
        if (!stateAttributes.isDirectory()) {
            throw new IOException("Fiesta world-tier state path is not a directory: " + stateDirectory);
        }
        final BasicFileAttributes remoteAttributes = attributesOrNull(remoteDirectory);
        if (remoteAttributes == null) {
            return null;
        }
        if (!remoteAttributes.isDirectory()) {
            throw new IOException("Fiesta world-tier remote marker path is not a directory: " + remoteDirectory);
        }
        return attributesOrNull(marker);
    }

    private static Path markerPath(final Path storageFolder, final int chunkX, final int chunkZ) throws IOException {
        final Path normalized = storageFolder.toAbsolutePath().normalize();
        final Path dimensionRoot = normalized.getParent();
        final Path component = normalized.getFileName();
        if (dimensionRoot == null || component == null
            || !(component.toString().equals("region")
                || component.toString().equals("entities")
                || component.toString().equals("poi"))) {
            throw new IOException("Unexpected Paper region storage folder: " + storageFolder);
        }
        return dimensionRoot.resolve(".world-tier").resolve("remote")
            .resolve("r." + (chunkX >> 5) + "." + (chunkZ >> 5) + ".json");
    }

    private static void validateMarker(final Path marker) throws IOException {
        final BasicFileAttributes attributes = Files.readAttributes(
            marker, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS
        );
        if (!attributes.isRegularFile() || attributes.size() > 4096L) {
            throw new IOException("Invalid Fiesta world-tier marker: " + marker);
        }
    }

    static String markerState(final Path marker) throws IOException {
        if (markerAttributesOrNull(marker) == null) {
            throw new IOException("Missing Fiesta world-tier marker: " + marker);
        }
        validateMarker(marker);
        final String contents = Files.readString(marker, java.nio.charset.StandardCharsets.UTF_8);
        final Matcher matcher = MARKER_STATE.matcher(contents);
        if (!matcher.find()) {
            throw new IOException("Invalid Fiesta world-tier marker state: " + marker);
        }
        return matcher.group(1);
    }

    private static Path configuredPath(final String property, final String defaultValue, final boolean executable)
        throws IOException {
        final Path path;
        try {
            path = Path.of(System.getProperty(property, defaultValue)).toAbsolutePath().normalize();
        } catch (final RuntimeException ex) {
            throw new IOException("Invalid Fiesta world-tier path configured by " + property, ex);
        }
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
            || (executable && !Files.isExecutable(path))) {
            throw new IOException("Fiesta world-tier " + (executable ? "command" : "config")
                + " is missing, not a regular file, or has unsafe path type: " + path);
        }
        return path;
    }

    private static long timeoutSeconds() throws IOException {
        final long timeout;
        try {
            timeout = Long.parseLong(System.getProperty(TIMEOUT_PROPERTY, Long.toString(DEFAULT_TIMEOUT_SECONDS)));
        } catch (final NumberFormatException ex) {
            throw new IOException("Invalid Fiesta world-tier restore timeout", ex);
        }
        if (timeout < 1L || timeout > Duration.ofHours(2).toSeconds()) {
            throw new IOException("Fiesta world-tier restore timeout must be between 1 and 7200 seconds");
        }
        return timeout;
    }
}
