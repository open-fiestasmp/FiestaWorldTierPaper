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
        check(!FiestaRegionRestorer.needsRestore(regionDirectory, "minecraft:overworld", 0, 0),
            "unmarked local region should not restore");

        for (final String state : new String[] {"REMOTE_ONLY", "EVICTING", "RESTORING"}) {
            Files.writeString(marker, "{\"state\":\"" + state + "\"}\n");
            check(FiestaRegionRestorer.needsRestore(regionDirectory, "minecraft:overworld", 0, 0),
                state + " must trigger recovery when local terrain exists");
            check(state.equals(FiestaRegionRestorer.markerState(marker)),
                state + " marker state must pass validation");
        }

        Files.writeString(marker, "{invalid marker}\n");
        check(FiestaRegionRestorer.needsRestore(regionDirectory, "minecraft:overworld", 0, 0),
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
        check(!FiestaRegionRestorer.needsRestore(regionDirectory, "minecraft:overworld", 0, 0),
            "removed marker should allow local data");

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
        check(FiestaRegionRestorer.needsRestore(regionDirectory, "minecraft:overworld", 0, 0),
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
        checkOffloadTimeoutKillsChildren();
        checkOffloadRequiresJournalLookup();
        checkNoIoJournalLookup();
        checkJournalRecoveryWithoutMarker();
        System.out.println("FiestaRegionRestorer marker fixture passed");
    }

    private static void checkOffloadRequiresJournalLookup() throws Exception {
        final Path dimension = Files.createTempDirectory("fiesta-offload-journal-guard-");
        final String oldLookup = System.getProperty("fiesta.worldTier.journalLookupEnabled");
        System.clearProperty("fiesta.worldTier.journalLookupEnabled");
        try {
            boolean snapshotBlocked = false;
            try {
                FiestaRegionOffloader.snapshot(dimension, "minecraft:overworld", 0, 0, "test");
            } catch (final IOException expected) {
                snapshotBlocked = expected.getMessage().contains("journal lookup");
            }
            check(snapshotBlocked, "offload snapshot must require journal lookup");

            boolean finalizationBlocked = false;
            try {
                FiestaRegionOffloader.finalizeOffload(
                    dimension, "minecraft:overworld", 0, 0, "test", "a".repeat(64)
                );
            } catch (final IOException expected) {
                finalizationBlocked = expected.getMessage().contains("journal lookup");
            }
            check(finalizationBlocked, "offload finalization must require journal lookup");
        } finally {
            restoreProperty("fiesta.worldTier.journalLookupEnabled", oldLookup);
            Files.deleteIfExists(dimension);
        }
    }

    private static void checkNoIoJournalLookup() throws Exception {
        final Path dimension = Files.createTempDirectory("fiesta-no-io-journal-");
        final Path regionDirectory = Files.createDirectories(dimension.resolve("region"));
        final Path config = Files.writeString(dimension.resolve("config.json"), "{}\n");
        final Path calls = dimension.resolve("calls.log");
        final Path command = dimension.resolve("world-tier-command.sh");
        Files.writeString(command, "#!/bin/sh\n"
            + "set -eu\n"
            + "operation=$1; shift\n"
            + "dimension_root=\nregion_x=\nregion_z=\n"
            + "while [ \"$#\" -gt 0 ]; do\n"
            + "  case \"$1\" in\n"
            + "    --dimension-root) dimension_root=$2; shift 2 ;;\n"
            + "    --region-x) region_x=$2; shift 2 ;;\n"
            + "    --region-z) region_z=$2; shift 2 ;;\n"
            + "    *) shift ;;\n"
            + "  esac\n"
            + "done\n"
            + "case \"$operation\" in\n"
            + "  region-state) printf 'called\\n' >> '" + calls + "'; printf 'UNTRACKED\\n' ;;\n"
            + "  finalize-offload-snapshot)\n"
            + "    mkdir -p \"$dimension_root/.world-tier/remote\"\n"
            + "    rm \"$dimension_root/region/r.$region_x.$region_z.mca\"\n"
            + "    printf '{\"state\":\"REMOTE_ONLY\"}\\n' > \"$dimension_root/.world-tier/remote/r.$region_x.$region_z.json\"\n"
            + "    ;;\n"
            + "  *) exit 2 ;;\n"
            + "esac\n");
        Files.setPosixFilePermissions(command, PosixFilePermissions.fromString("rwx------"));

        final String oldCommand = System.getProperty("fiesta.worldTier.command");
        final String oldConfig = System.getProperty("fiesta.worldTier.config");
        final String oldLookup = System.getProperty("fiesta.worldTier.journalLookupEnabled");
        System.setProperty("fiesta.worldTier.command", command.toString());
        System.setProperty("fiesta.worldTier.config", config.toString());
        System.setProperty("fiesta.worldTier.journalLookupEnabled", "true");
        try {
            check(FiestaRegionRestorer.needsRestoreNoIO(regionDirectory, 0, 0),
                "an uncached region must stay possible until journal lookup runs on the I/O path");
            check(!Files.exists(calls), "the no-I/O lookup must not start the companion process");
            check(!FiestaRegionRestorer.needsRestore(regionDirectory, "minecraft:overworld", 0, 0),
                "the journal I/O path should identify an untracked region");
            check(!FiestaRegionRestorer.needsRestoreNoIO(regionDirectory, 0, 0),
                "a cached untracked region may be reported absent without another process");

            final Path marker = dimension.resolve(".world-tier/remote/r.0.0.json");
            Files.writeString(regionDirectory.resolve("r.0.0.mca"), "local region\n");
            FiestaRegionOffloader.finalizeOffload(
                dimension, "minecraft:overworld", 0, 0, "test", "a".repeat(64)
            );
            check(!Files.exists(regionDirectory.resolve("r.0.0.mca")),
                "offload finalization should remove the local terrain file");
            check(Files.isRegularFile(marker), "offload finalization should publish the remote marker");
            check(FiestaRegionRestorer.needsRestoreNoIO(regionDirectory, 0, 0),
                "offload finalization must invalidate a cached untracked state");

            Files.delete(marker);
            restoreProperty("fiesta.worldTier.journalLookupEnabled", "false");
            FiestaRegionRestorer.invalidate(regionDirectory, 0, 0);
            check(FiestaRegionRestorer.needsRestoreNoIO(regionDirectory, 0, 0),
                "an uncached marker state must stay possible until the normal I/O lookup checks it");
            check(!FiestaRegionRestorer.needsRestore(regionDirectory, "minecraft:overworld", 0, 0),
                "the non-tier path should cache an absent marker after checking it");
            check(!FiestaRegionRestorer.needsRestoreNoIO(regionDirectory, 0, 0),
                "the no-I/O path may trust the cached absent-marker result");
        } finally {
            restoreProperty("fiesta.worldTier.command", oldCommand);
            restoreProperty("fiesta.worldTier.config", oldConfig);
            restoreProperty("fiesta.worldTier.journalLookupEnabled", oldLookup);
            FiestaRegionRestorer.invalidate(regionDirectory, 0, 0);
            try (var paths = Files.walk(dimension)) {
                for (final Path path : paths.sorted((left, right) -> right.compareTo(left)).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static void checkJournalRecoveryWithoutMarker() throws Exception {
        final Path dimension = Files.createTempDirectory("fiesta-journal-recovery-");
        final Path regionDirectory = Files.createDirectories(dimension.resolve("region"));
        final Path config = Files.writeString(dimension.resolve("config.json"), "{}\n");
        final Path journalState = Files.writeString(dimension.resolve("journal-state"), "UNTRACKED\n");
        final Path command = dimension.resolve("world-tier-command.sh");
        final String script = "#!/bin/sh\n"
            + "set -eu\n"
            + "operation=$1; shift\n"
            + "dimension_root=\nregion_x=\nregion_z=\n"
            + "while [ \"$#\" -gt 0 ]; do\n"
            + "  case \"$1\" in\n"
            + "    --dimension-root) dimension_root=$2; shift 2 ;;\n"
            + "    --region-x) region_x=$2; shift 2 ;;\n"
            + "    --region-z) region_z=$2; shift 2 ;;\n"
            + "    *) shift ;;\n"
            + "  esac\n"
            + "done\n"
            + "case \"$operation\" in\n"
            + "  region-state) cat '" + journalState + "' ;;\n"
            + "  recover-region)\n"
            + "    case \"$(cat '" + journalState + "')\" in\n"
            + "      UNTRACKED) printf 'UNTRACKED\\n' ;;\n"
            + "      REMOTE) printf 'saved region\\n' > \"$dimension_root/region/r.$region_x.$region_z.mca\"; printf 'LOCAL\\n' ;;\n"
            + "      *) exit 1 ;;\n"
            + "    esac\n"
            + "    ;;\n"
            + "  *) exit 2 ;;\n"
            + "esac\n";
        Files.writeString(command, script);
        Files.setPosixFilePermissions(command, PosixFilePermissions.fromString("rwx------"));

        final String oldCommand = System.getProperty("fiesta.worldTier.command");
        final String oldConfig = System.getProperty("fiesta.worldTier.config");
        final String oldLookup = System.getProperty("fiesta.worldTier.journalLookupEnabled");
        System.setProperty("fiesta.worldTier.command", command.toString());
        System.setProperty("fiesta.worldTier.config", config.toString());
        System.setProperty("fiesta.worldTier.journalLookupEnabled", "true");
        try {
            check(!FiestaRegionRestorer.needsRestore(regionDirectory, "minecraft:overworld", 0, 0),
                "an untracked missing region should remain eligible for normal generation");
            check(!FiestaRegionRestorer.restoreIfMarked(regionDirectory, "minecraft:overworld", 0, 0),
                "an untracked missing region should not be treated as restored");

            Files.writeString(journalState, "REMOTE\n");
            FiestaRegionRestorer.invalidate(regionDirectory, 0, 0);
            check(FiestaRegionRestorer.needsRestore(regionDirectory, "minecraft:overworld", 0, 0),
                "a journaled remote region must restore when its marker is missing");
            check(FiestaRegionRestorer.restoreIfMarked(regionDirectory, "minecraft:overworld", 0, 0),
                "journal recovery should publish the missing local terrain");
            check(Files.readString(regionDirectory.resolve("r.0.0.mca")).equals("saved region\n"),
                "journal recovery must preserve the remote terrain bytes");

            Files.delete(regionDirectory.resolve("r.0.0.mca"));
            Files.writeString(journalState, "TRACKED_MISSING\n");
            FiestaRegionRestorer.invalidate(regionDirectory, 0, 0);
            check(FiestaRegionRestorer.needsRestore(regionDirectory, "minecraft:overworld", 0, 0),
                "a tracked local region with missing terrain must fail closed");
            boolean failedClosed = false;
            try {
                FiestaRegionRestorer.restoreIfMarked(regionDirectory, "minecraft:overworld", 0, 0);
            } catch (final FiestaWorldTierRestoreException expected) {
                failedClosed = true;
            }
            check(failedClosed, "missing tracked terrain must not fall through to empty-region creation");
            check(!Files.exists(regionDirectory.resolve("r.0.0.mca")),
                "failed journal recovery must not create replacement terrain");
        } finally {
            restoreProperty("fiesta.worldTier.command", oldCommand);
            restoreProperty("fiesta.worldTier.config", oldConfig);
            restoreProperty("fiesta.worldTier.journalLookupEnabled", oldLookup);
            clearJournalStateCache(regionDirectory);
            try (var paths = Files.walk(dimension)) {
                for (final Path path : paths.sorted((left, right) -> right.compareTo(left)).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static void restoreProperty(final String key, final String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    private static void clearJournalStateCache(final Path regionDirectory) throws IOException {
        FiestaRegionRestorer.invalidate(regionDirectory, 0, 0);
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

    private static void checkOffloadTimeoutKillsChildren() throws Exception {
        final Path dimension = Files.createTempDirectory("fiesta-region-offload-timeout-");
        final Path config = Files.writeString(dimension.resolve("offload-config.json"), "{}\n");
        final Path command = dimension.resolve("offload-command.sh");
        final Path childScript = dimension.resolve("offload-child.py");
        final Path childPid = dimension.resolve("offload-child.pid");
        final Path ticks = dimension.resolve("offload-child-ticks.log");
        Files.writeString(childScript,
            "import sys, time\n"
                + "while True:\n"
                + "    with open(sys.argv[1], 'a', encoding='utf-8') as output:\n"
                + "        output.write('tick\\n')\n"
                + "    time.sleep(0.05)\n");
        Files.writeString(command, "#!/bin/sh\n"
            + "set -eu\n"
            + "python3 '" + childScript + "' '" + ticks + "' &\n"
            + "child=$!\n"
            + "printf '%s\\n' \"$child\" > '" + childPid + "'\n"
            + "wait \"$child\"\n");
        Files.setPosixFilePermissions(command, PosixFilePermissions.fromString("rwx------"));

        final String oldCommand = System.getProperty("fiesta.worldTier.command");
        final String oldConfig = System.getProperty("fiesta.worldTier.config");
        final String oldLookup = System.getProperty("fiesta.worldTier.journalLookupEnabled");
        final String oldTimeout = System.getProperty("fiesta.worldTier.offloadTimeoutSeconds");
        System.setProperty("fiesta.worldTier.command", command.toString());
        System.setProperty("fiesta.worldTier.config", config.toString());
        System.setProperty("fiesta.worldTier.journalLookupEnabled", "true");
        System.setProperty("fiesta.worldTier.offloadTimeoutSeconds", "1");

        try {
            boolean failed = false;
            try {
                FiestaRegionOffloader.snapshot(dimension, "minecraft:overworld", 0, 0, "timeout-test");
            } catch (final IOException expected) {
                failed = expected.getMessage().contains("timed out");
            }
            check(failed, "the offload command must fail after its timeout");
            check(Files.isRegularFile(childPid), "the offload fixture child did not start");
            check(Files.isRegularFile(ticks), "the offload fixture child did not write its liveness log");
            Thread.sleep(300L);
            final int stableCount = Files.readAllLines(ticks).size();
            Thread.sleep(300L);
            check(Files.readAllLines(ticks).size() == stableCount,
                "the offload child continued running after its parent timed out");
        } finally {
            restoreProperty("fiesta.worldTier.command", oldCommand);
            restoreProperty("fiesta.worldTier.config", oldConfig);
            restoreProperty("fiesta.worldTier.journalLookupEnabled", oldLookup);
            restoreProperty("fiesta.worldTier.offloadTimeoutSeconds", oldTimeout);
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
