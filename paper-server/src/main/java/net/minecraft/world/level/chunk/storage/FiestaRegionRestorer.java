package net.minecraft.world.level.chunk.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
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
    private static final String DEFAULT_COMMAND = "/usr/local/bin/fiesta-world-tier";
    private static final String DEFAULT_CONFIG = "/etc/fiesta-world-tier/config.json";
    private static final long DEFAULT_TIMEOUT_SECONDS = 300L;
    private static final Pattern MARKER_STATE = Pattern.compile("\\\"state\\\"\\s*:\\s*\\\"(REMOTE_ONLY|RESTORING)\\\"");
    private static final Object[] LOCKS = new Object[256];

    static {
        for (int i = 0; i < LOCKS.length; ++i) {
            LOCKS[i] = new Object();
        }
    }

    private FiestaRegionRestorer() {}

    /**
     * Any durable marker requires recovery. A surviving terrain file does not
     * prove that eviction completed: a crash may have deleted only the entity,
     * POI, or oversized-chunk members of the bundle. Invalid markers also
     * return true so the real I/O path fails closed instead of using Moonrise's
     * negative cache.
     */
    static boolean needsRestore(final Path storageFolder, final int chunkX, final int chunkZ) {
        try {
            final Path marker = markerPath(storageFolder, chunkX, chunkZ);
            if (!Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
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
        final Path marker = markerPath(storageFolder, chunkX, chunkZ);
        if (!Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }

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
            if (!Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isRegularFile(terrainRegion, LinkOption.NOFOLLOW_LINKS)) {
                    return true;
                }
                throw new IOException("Fiesta world-tier marker disappeared before local data was restored: " + marker);
            }
            markerState(marker);
            if (Files.exists(terrainRegion, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(terrainRegion, LinkOption.NOFOLLOW_LINKS)) {
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
                    process.destroyForcibly();
                    process.waitFor(Math.min(timeoutSeconds, 10L), TimeUnit.SECONDS);
                    throw new IOException("Fiesta world-tier restore timed out after " + timeoutSeconds + " seconds");
                }
                if (process.exitValue() != 0) {
                    throw new IOException("Fiesta world-tier restore failed with exit code " + process.exitValue());
                }
            } catch (final InterruptedException ex) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while restoring a Fiesta world-tier region", ex);
            }

            if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Fiesta world-tier restore command did not clear the marker after publishing local data");
            }
            final BasicFileAttributes attributes;
            try {
                attributes = Files.readAttributes(terrainRegion, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (final IOException ex) {
                throw new IOException("Restore completed without publishing the expected terrain region " + regionName, ex);
            }
            if (!attributes.isRegularFile()) {
                throw new IOException("Restored terrain region is not a regular file: " + regionName);
            }
            return true;
        }
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

    private static String markerState(final Path marker) throws IOException {
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
