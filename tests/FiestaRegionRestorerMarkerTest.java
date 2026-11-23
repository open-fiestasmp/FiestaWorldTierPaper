package net.minecraft.world.level.chunk.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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

        try (var paths = Files.walk(dimension)) {
            for (final Path path : paths.sorted((left, right) -> right.compareTo(left)).toList()) {
                Files.deleteIfExists(path);
            }
        }
        System.out.println("FiestaRegionRestorer marker fixture passed");
    }

    private static void check(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
