package net.minecraft.world.level.chunk.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public final class FiestaRegionRestorerMarkerTest {
    public static void main(final String[] args) throws Exception {
        final Path dimension = Files.createTempDirectory("fiesta-region-marker-");
        final Path regionDirectory = Files.createDirectories(dimension.resolve("region"));
        final Path terrain = regionDirectory.resolve("r.0.0.mca");
        final Path markerDirectory = Files.createDirectories(dimension.resolve(".world-tier/remote"));
        final Path marker = markerDirectory.resolve("r.0.0.json");

        Files.writeString(terrain, "local terrain survives");
        check(!FiestaRegionRestorer.needsRestore(regionDirectory, 0, 0), "unmarked local region should not restore");

        for (final String state : new String[] {"REMOTE_ONLY", "EVICTING", "RESTORING"}) {
            Files.writeString(marker, "{\"state\":\"" + state + "\"}\n");
            check(FiestaRegionRestorer.needsRestore(regionDirectory, 0, 0),
                state + " must trigger recovery when local terrain exists");
            check(state.equals(FiestaRegionRestorer.markerState(marker)),
                state + " marker state must pass validation");
        }

        Files.writeString(marker, "{invalid marker}\n");
        check(FiestaRegionRestorer.needsRestore(regionDirectory, 0, 0),
            "invalid markers must fail closed");
        boolean typedRestoreFailure = false;
        try {
            FiestaRegionRestorer.restoreIfMarked(regionDirectory, "minecraft:overworld", 0, 0);
        } catch (final FiestaWorldTierRestoreException expected) {
            typedRestoreFailure = true;
        }
        check(typedRestoreFailure, "restore failures must use the typed fail-closed exception");

        final FiestaWorldTierRestoreException tierFailure = new FiestaWorldTierRestoreException(
            "restore failure", new IOException("remote store unavailable"), true
        );
        check(tierFailure.isRetryable(), "transport failures should be retryable");
        check(FiestaWorldTierRestoreException.find(tierFailure) == tierFailure,
            "tier restore failures must remain identifiable after wrapping");
        check(FiestaWorldTierRestoreException.find(new RuntimeException("wrapper", tierFailure)) == tierFailure,
            "wrapped tier restore failures must remain identifiable");

        Files.delete(marker);
        check(!FiestaRegionRestorer.needsRestore(regionDirectory, 0, 0), "removed marker should allow local data");

        Files.delete(terrain);
        check(!FiestaRegionRestorer.restoreIfMarked(regionDirectory, "minecraft:overworld", 0, 0),
            "an absent marker and local region should not start a restore");
        Files.setPosixFilePermissions(markerDirectory, PosixFilePermissions.fromString("---------"));
        boolean permissionDenied = !Files.isReadable(markerDirectory);
        if (!permissionDenied) {
            Files.setPosixFilePermissions(markerDirectory, PosixFilePermissions.fromString("rwx------"));
            Files.delete(markerDirectory);
            Files.delete(markerDirectory.getParent());
            Files.writeString(dimension.resolve(".world-tier"), "not a directory");
        }
        check(FiestaRegionRestorer.needsRestore(regionDirectory, 0, 0),
            "an unreadable marker path must force recovery");
        typedRestoreFailure = false;
        try {
            FiestaRegionRestorer.restoreIfMarked(regionDirectory, "minecraft:overworld", 0, 0);
        } catch (final FiestaWorldTierRestoreException expected) {
            typedRestoreFailure = true;
        }
        check(typedRestoreFailure, "an unreadable marker path must fail closed");
        check(!Files.exists(terrain), "a failed marker check must not create an empty local region");
        if (permissionDenied) {
            Files.setPosixFilePermissions(markerDirectory, PosixFilePermissions.fromString("rwx------"));
        }

        try (var paths = Files.walk(dimension)) {
            for (final Path path : paths.sorted((left, right) -> right.compareTo(left)).toList()) {
                Files.deleteIfExists(path);
            }
        }
        checkConcurrentRestore();
        checkRestoreTimeoutKillsChildren();
        System.out.println("FiestaRegionRestorer marker fixture passed");
    }

    private static void checkConcurrentRestore() throws Exception {
        final Path dimension = Files.createTempDirectory("fiesta-region-concurrent-restore-");
        final Path regionDirectory = Files.createDirectories(dimension.resolve("region"));
        final Path markerDirectory = Files.createDirectories(dimension.resolve(".world-tier/remote"));
        final Path marker = markerDirectory.resolve("r.0.0.json");
        final Path calls = dimension.resolve("restore-calls.log");
        final Path config = Files.writeString(dimension.resolve("restore-config.json"), "{}\n");
        final Path command = dimension.resolve("restore-command.sh");
        final String script = "#!/bin/sh\n"
            + "set -eu\n"
            + "printf 'call\\n' >> '" + calls + "'\n"
            + "dimension_root=\nregion_x=\nregion_z=\n"
            + "while [ \"$#\" -gt 0 ]; do\n"
            + "  case \"$1\" in\n"
            + "    --dimension-root) dimension_root=$2; shift 2 ;;\n"
            + "    --region-x) region_x=$2; shift 2 ;;\n"
            + "    --region-z) region_z=$2; shift 2 ;;\n"
            + "    *) shift ;;\n"
            + "  esac\n"
            + "done\n"
            + "sleep 0.25\n"
            + "mkdir -p \"$dimension_root/region\"\n"
            + "printf 'saved region\\n' > \"$dimension_root/region/r.$region_x.$region_z.mca\"\n"
            + "python3 - \"$dimension_root/.world-tier/remote/r.$region_x.$region_z.json\" <<'PY'\n"
            + "from pathlib import Path\n"
            + "import sys\n"
            + "Path(sys.argv[1]).unlink()\n"
            + "PY\n";
        Files.writeString(command, script);
        Files.setPosixFilePermissions(command, PosixFilePermissions.fromString("rwx------"));
        Files.writeString(marker, "{\"state\":\"REMOTE_ONLY\"}\n");
        System.setProperty("fiesta.worldTier.command", command.toString());
        System.setProperty("fiesta.worldTier.config", config.toString());

        final int workers = 12;
        final CountDownLatch ready = new CountDownLatch(workers);
        final CountDownLatch start = new CountDownLatch(1);
        final ExecutorService executor = Executors.newFixedThreadPool(workers);
        try {
            final List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < workers; ++i) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return FiestaRegionRestorer.restoreIfMarked(
                        regionDirectory, "minecraft:overworld", 0, 0
                    );
                }));
            }
            check(ready.await(5, TimeUnit.SECONDS), "concurrent restore workers did not start");
            start.countDown();

            int restored = 0;
            for (final Future<Boolean> result : results) {
                if (result.get(10, TimeUnit.SECONDS)) {
                    ++restored;
                }
            }
            check(restored == workers, "all concurrent callers should observe the published region");
            check(Files.readAllLines(calls).size() == 1,
                "concurrent reads must share a single region restore command");
            check(Files.isRegularFile(regionDirectory.resolve("r.0.0.mca")),
                "the concurrent restore must publish the saved region");
            check(!Files.exists(marker), "the concurrent restore must clear its marker after publishing data");
        } finally {
            executor.shutdownNow();
            System.clearProperty("fiesta.worldTier.command");
            System.clearProperty("fiesta.worldTier.config");
            try (var paths = Files.walk(dimension)) {
                for (final Path path : paths.sorted((left, right) -> right.compareTo(left)).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static void checkRestoreTimeoutKillsChildren() throws Exception {
        final Path dimension = Files.createTempDirectory("fiesta-region-restore-timeout-");
        final Path regionDirectory = Files.createDirectories(dimension.resolve("region"));
        final Path markerDirectory = Files.createDirectories(dimension.resolve(".world-tier/remote"));
        final Path marker = markerDirectory.resolve("r.0.0.json");
        final Path config = Files.writeString(dimension.resolve("restore-config.json"), "{}\n");
        final Path command = dimension.resolve("restore-command.sh");
        final Path childScript = dimension.resolve("restore-child.py");
        final Path childPid = dimension.resolve("restore-child.pid");
        final Path ticks = dimension.resolve("restore-child-ticks.log");
        Files.writeString(childScript,
            "import sys, time\n"
                + "while True:\n"
                + "    with open(sys.argv[1], 'a', encoding='utf-8') as output:\n"
                + "        output.write('tick\\n')\n"
                + "    time.sleep(0.05)\n");
        final String script = "#!/bin/sh\n"
            + "set -eu\n"
            + "python3 '" + childScript + "' '" + ticks + "' &\n"
            + "child=$!\n"
            + "printf '%s\\n' \"$child\" > '" + childPid + "'\n"
            + "wait \"$child\"\n";
        Files.writeString(command, script);
        Files.setPosixFilePermissions(command, PosixFilePermissions.fromString("rwx------"));
        Files.writeString(marker, "{\"state\":\"REMOTE_ONLY\"}\n");
        System.setProperty("fiesta.worldTier.command", command.toString());
        System.setProperty("fiesta.worldTier.config", config.toString());
        System.setProperty("fiesta.worldTier.restoreTimeoutSeconds", "1");

        try {
            boolean failed = false;
            try {
                FiestaRegionRestorer.restoreIfMarked(regionDirectory, "minecraft:overworld", 0, 0);
            } catch (final IOException expected) {
                failed = true;
            }
            check(failed, "the restore command must fail after its timeout");
            check(Files.isRegularFile(childPid), "the restore fixture child did not start");
            check(Files.isRegularFile(ticks), "the restore fixture child did not write its liveness log");
            Thread.sleep(300L);
            final int stableCount = Files.readAllLines(ticks).size();
            Thread.sleep(300L);
            check(Files.readAllLines(ticks).size() == stableCount,
                "the restore child continued running after its parent timed out");
            check(Files.isRegularFile(marker), "the marker must remain after a timed-out restore");
            check(!Files.exists(regionDirectory.resolve("r.0.0.mca")),
                "a timed-out restore must not publish a terrain region");
        } finally {
            System.clearProperty("fiesta.worldTier.command");
            System.clearProperty("fiesta.worldTier.config");
            System.clearProperty("fiesta.worldTier.restoreTimeoutSeconds");
            if (Files.isRegularFile(childPid)) {
                final long pid = Long.parseLong(Files.readString(childPid).trim());
                ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
            }
            try (var paths = Files.walk(dimension)) {
                for (final Path path : paths.sorted((left, right) -> right.compareTo(left)).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static void check(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
