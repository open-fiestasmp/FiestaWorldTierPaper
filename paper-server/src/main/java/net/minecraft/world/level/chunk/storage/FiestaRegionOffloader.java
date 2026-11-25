package net.minecraft.world.level.chunk.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Runs the staged Python tier operation around short Paper region leases. */
public final class FiestaRegionOffloader {
    private static final String COMMAND_PROPERTY = "fiesta.worldTier.command";
    private static final String CONFIG_PROPERTY = "fiesta.worldTier.config";
    private static final String JOURNAL_LOOKUP_PROPERTY = "fiesta.worldTier.journalLookupEnabled";
    private static final String TIMEOUT_PROPERTY = "fiesta.worldTier.offloadTimeoutSeconds";
    private static final String DEFAULT_COMMAND = "/usr/local/bin/fiesta-world-tier";
    private static final String DEFAULT_CONFIG = "/etc/fiesta-world-tier/config.json";
    private static final Pattern MANIFEST_HASH = Pattern.compile("[0-9a-f]{64}");
    private static final long DEFAULT_TIMEOUT_SECONDS = 3600L;

    private FiestaRegionOffloader() {}

    public static void snapshot(final Path dimensionRoot, final String dimensionKey, final int regionX,
                                final int regionZ, final String snapshotId) throws IOException {
        requireJournalLookupEnabled();
        final Path root = checkedRoot(dimensionRoot);
        final List<String> arguments = operation("snapshot-offload", dimensionKey, regionX, regionZ, snapshotId);
        arguments.add("--dimension-root");
        arguments.add(root.toString());
        run(arguments, false);
    }

    /** Uploads the independent snapshot without holding Paper's region lease. */
    public static String publish(final String dimensionKey, final int regionX, final int regionZ,
                                 final String snapshotId) throws IOException {
        final List<String> arguments = operation("publish-offload-snapshot", dimensionKey, regionX, regionZ, snapshotId);
        final String output = run(arguments, true).trim();
        if (!MANIFEST_HASH.matcher(output).matches()) {
            throw new IOException("Fiesta world-tier upload returned an invalid manifest checksum");
        }
        return output;
    }

    public static void finalizeOffload(final Path dimensionRoot, final String dimensionKey, final int regionX,
                                      final int regionZ, final String snapshotId, final String manifestHash)
        throws IOException {
        requireJournalLookupEnabled();
        final Path root = checkedRoot(dimensionRoot);
        if (!MANIFEST_HASH.matcher(manifestHash).matches()) {
            throw new IOException("Invalid Fiesta world-tier manifest checksum");
        }
        final List<String> arguments = operation("finalize-offload-snapshot", dimensionKey, regionX, regionZ, snapshotId);
        arguments.add("--dimension-root");
        arguments.add(root.toString());
        arguments.add("--manifest-sha256");
        arguments.add(manifestHash);
        FiestaRegionRestorer.invalidate(root.resolve("region"), regionX << 5, regionZ << 5);
        run(arguments, false);

        final Path marker = root.resolve(".world-tier").resolve("remote")
            .resolve("r." + regionX + "." + regionZ + ".json");
        if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
            || !"REMOTE_ONLY".equals(FiestaRegionRestorer.markerState(marker))) {
            throw new IOException("Fiesta world-tier finalization completed without a REMOTE_ONLY marker");
        }
        final Path terrain = root.resolve("region").resolve("r." + regionX + "." + regionZ + ".mca");
        if (Files.exists(terrain, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Fiesta world-tier finalization left the local terrain region in place: " + terrain);
        }
        FiestaRegionRestorer.invalidate(root.resolve("region"), regionX << 5, regionZ << 5);
    }

    private static void requireJournalLookupEnabled() throws IOException {
        if (!Boolean.parseBoolean(System.getProperty(JOURNAL_LOOKUP_PROPERTY, "false"))) {
            throw new IOException("Fiesta world-tier offload requires journal lookup to be enabled");
        }
    }

    /** Cleans a failed, unmarked staged upload without deleting authoritative marked data. */
    public static void abort(final String dimensionKey, final int regionX, final int regionZ,
                             final String snapshotId, final String manifestHash) throws IOException {
        final List<String> arguments = operation("abort-offload-snapshot", dimensionKey, regionX, regionZ, snapshotId);
        if (manifestHash != null) {
            if (!MANIFEST_HASH.matcher(manifestHash).matches()) {
                throw new IOException("Invalid Fiesta world-tier manifest checksum for abort");
            }
            arguments.add("--manifest-sha256");
            arguments.add(manifestHash);
        }
        run(arguments, false);
    }

    private static List<String> operation(final String operation, final String dimensionKey, final int regionX,
                                          final int regionZ, final String snapshotId) throws IOException {
        final Path command = configuredPath(COMMAND_PROPERTY, DEFAULT_COMMAND, true);
        final Path config = configuredPath(CONFIG_PROPERTY, DEFAULT_CONFIG, false);
        final List<String> arguments = new ArrayList<>(List.of(
            command.toString(), operation,
            "--config", config.toString(),
            "--dimension-key", dimensionKey,
            "--region-x", Integer.toString(regionX),
            "--region-z", Integer.toString(regionZ),
            "--snapshot-id", snapshotId
        ));
        return arguments;
    }

    private static String run(final List<String> arguments, final boolean captureOutput) throws IOException {
        final ProcessBuilder builder = new ProcessBuilder(arguments);
        final Map<String, String> environment = builder.environment();
        environment.clear();
        environment.put("PATH", "/usr/local/bin:/usr/bin:/bin");
        environment.put("LANG", "C");
        builder.redirectError(ProcessBuilder.Redirect.INHERIT);
        if (!captureOutput) {
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        }

        final Process process;
        try {
            process = builder.start();
        } catch (final IOException ex) {
            throw new IOException("Could not start Fiesta world-tier operation", ex);
        }
        final long timeout = timeoutSeconds();
        try {
            if (!process.waitFor(timeout, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(Math.min(timeout, 10L), TimeUnit.SECONDS);
                throw new IOException("Fiesta world-tier operation timed out after " + timeout + " seconds");
            }
            if (process.exitValue() != 0) {
                throw new IOException("Fiesta world-tier operation failed with exit code " + process.exitValue());
            }
            if (!captureOutput) {
                return "";
            }
            final byte[] output = process.getInputStream().readNBytes(256);
            if (output.length == 256 && process.getInputStream().read() != -1) {
                throw new IOException("Fiesta world-tier command output exceeded its limit");
            }
            return new String(output, StandardCharsets.US_ASCII);
        } catch (final InterruptedException ex) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted during a Fiesta world-tier operation", ex);
        }
    }

    private static Path checkedRoot(final Path dimensionRoot) throws IOException {
        final Path root = dimensionRoot.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Unsafe Fiesta dimension root for offload: " + root);
        }
        return root;
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
