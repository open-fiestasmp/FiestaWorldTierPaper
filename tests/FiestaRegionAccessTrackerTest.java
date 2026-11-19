import ca.spottedleaf.moonrise.patches.chunk_system.io.FiestaRegionAccessTracker;
import java.nio.file.Files;
import java.nio.file.Path;

public final class FiestaRegionAccessTrackerTest {

    public static void main(final String[] args) throws Exception {
        java.util.logging.Logger.getLogger(FiestaRegionAccessTracker.class.getName())
            .setLevel(java.util.logging.Level.OFF);
        final Path dimension = Files.createTempDirectory("fiesta-access-tracker-");
        final int regionX = -1;
        final int regionZ = 2;
        final Path marker = dimension.resolve(".world-tier/access/r.-1.2.touch");

        require(!FiestaRegionAccessTracker.isCold(dimension, regionX, regionZ, 1_000L),
            "a region with unknown history must remain hot");
        FiestaRegionAccessTracker.recordAccess(dimension, regionX, regionZ);
        require(Files.isRegularFile(marker), "Paper access must create a durable region timestamp");
        final long timestamp = Long.parseLong(Files.readString(marker).trim());
        require(!FiestaRegionAccessTracker.isCold(dimension, regionX, regionZ, 1_000L, timestamp + 500L),
            "recently used region must not be cold");
        require(FiestaRegionAccessTracker.isCold(dimension, regionX, regionZ, 1_000L, timestamp + 2_000L),
            "region older than its configured threshold may be cold");

        Files.writeString(marker, "invalid\n");
        require(!FiestaRegionAccessTracker.isCold(dimension, regionX, regionZ, 1_000L, timestamp + 100_000L),
            "corrupt access history must fail closed");

        System.out.println("Fiesta region access tracker checks passed");
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
