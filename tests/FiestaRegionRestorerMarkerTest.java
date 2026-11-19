package net.minecraft.world.level.chunk.storage;

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

        Files.writeString(marker, "{\"state\":\"REMOTE_ONLY\"}\n");
        check(FiestaRegionRestorer.needsRestore(regionDirectory, 0, 0),
            "REMOTE_ONLY must restore even when terrain survived partial eviction");

        Files.writeString(marker, "{\"state\":\"RESTORING\"}\n");
        check(FiestaRegionRestorer.needsRestore(regionDirectory, 0, 0),
            "RESTORING must retry when local terrain exists");

        Files.writeString(marker, "{invalid marker}\n");
        check(FiestaRegionRestorer.needsRestore(regionDirectory, 0, 0),
            "invalid markers must fail closed");

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
