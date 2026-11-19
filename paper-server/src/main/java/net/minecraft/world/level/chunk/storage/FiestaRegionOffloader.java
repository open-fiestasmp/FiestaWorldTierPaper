package net.minecraft.world.level.chunk.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Runs the durable Python tier operation while Paper holds the region lease. */
public final class FiestaRegionOffloader {
    private static final String COMMAND_PROPERTY = "fiesta.worldTier.command";
    private static final String CONFIG_PROPERTY = "fiesta.worldTier.config";
    private static final String TIMEOUT_PROPERTY = "fiesta.worldTier.offloadTimeoutSeconds";
    private static final String DEFAULT_COMMAND = "/usr/local/bin/fiesta-world-tier";
    private static final String DEFAULT_CONFIG = "/etc/fiesta-world-tier/config.json";
    private static final long DEFAULT_TIMEOUT_SECONDS = 3600L;

    private FiestaRegionOffloader() {}

    public static void offload(final Path dimensionRoot, final String dimensionKey, final int regionX, final int regionZ)
        throws IOException {
        final Path root = dimensionRoot.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Unsafe Fiesta dimension root for offload: " + root);
        }
        final Path command = configuredPath(COMMAND_PROPERTY, DEFAULT_COMMAND, true);
        final Path config = configuredPath(CONFIG_PROPERTY, DEFAULT_CONFIG, false);
        final long timeoutSeconds = timeoutSeconds();
        final ProcessBuilder builder = new ProcessBuilder(
            command.toString(),
            "offload",
            "--config", config.toString(),
            "--dimension-key", dimensionKey,
            "--dimension-root", root.toString(),
            "--region-x", Integer.toString(regionX),
            "--region-z", Integer.toString(regionZ)
        );
        final Map<String, String> environment = builder.environment();
        environment.clear();
        environment.put("PATH", "/usr/local/bin:/usr/bin:/bin");
        environment.put("LANG", "C");
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        builder.redirectError(ProcessBuilder.Redirect.INHERIT);

        final Process process;
        try {
            process = builder.start();
        } catch (final IOException ex) {
            throw new IOException("Could not start Fiesta world-tier offload", ex);
        }
        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(Math.min(timeoutSeconds, 10L), TimeUnit.SECONDS);
                throw new IOException("Fiesta world-tier offload timed out after " + timeoutSeconds + " seconds");
            }
            if (process.exitValue() != 0) {
                throw new IOException("Fiesta world-tier offload failed with exit code " + process.exitValue());
            }
        } catch (final InterruptedException ex) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while offloading a Fiesta region", ex);
        }

        final Path marker = root.resolve(".world-tier").resolve("remote")
            .resolve("r." + regionX + "." + regionZ + ".json");
        if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
            || !"REMOTE_ONLY".equals(FiestaRegionRestorer.markerState(marker))) {
            throw new IOException("Fiesta world-tier offload completed without a REMOTE_ONLY marker");
        }
        final Path terrain = root.resolve("region").resolve("r." + regionX + "." + regionZ + ".mca");
        if (Files.exists(terrain, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Fiesta world-tier offload left the local terrain region in place: " + terrain);
        }
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
                + " is missing or has an unsafe path type: " + path);
        }
        return path;
    }

    private static long timeoutSeconds() throws IOException {
        final long timeout;
        try {
            timeout = Long.parseLong(System.getProperty(TIMEOUT_PROPERTY, Long.toString(DEFAULT_TIMEOUT_SECONDS)));
        } catch (final NumberFormatException ex) {
            throw new IOException("Invalid Fiesta world-tier offload timeout", ex);
        }
        if (timeout < 1L || timeout > Duration.ofHours(2).toSeconds()) {
            throw new IOException("Fiesta world-tier offload timeout must be between 1 and 7200 seconds");
        }
        return timeout;
    }
}
