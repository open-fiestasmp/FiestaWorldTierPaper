import ca.spottedleaf.moonrise.patches.chunk_system.io.FiestaRegionLeaseManager;
import ca.spottedleaf.moonrise.patches.chunk_system.io.FiestaRegionLeaseManager.Lease;
import ca.spottedleaf.moonrise.patches.chunk_system.io.FiestaRegionLeaseManager.ProcessLease;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CompletableFuture;

public final class FiestaRegionLeaseManagerTest {

    public static void main(final String[] args) throws Exception {
        final Path dimension = Path.of("/tmp/fiesta-world");
        final Path otherDimension = Path.of("/tmp/fiesta-nether");

        final Lease firstReader = FiestaRegionLeaseManager.acquireShared(dimension, -1, 2).join();
        final Lease secondReader = FiestaRegionLeaseManager.acquireShared(dimension, -1, 2).join();
        final CompletableFuture<Lease> writerFuture = FiestaRegionLeaseManager.acquireExclusive(dimension, -1, 2);
        final CompletableFuture<Lease> laterReaderFuture = FiestaRegionLeaseManager.acquireShared(dimension, -1, 2);

        require(!writerFuture.isDone(), "exclusive lease must wait for existing readers");
        require(!laterReaderFuture.isDone(), "a later reader must not pass a queued writer");

        firstReader.close();
        require(!writerFuture.isDone(), "writer must wait until every reader exits");
        secondReader.close();

        final Lease writer = writerFuture.join();
        require(writer.isExclusive(), "writer future must grant an exclusive lease");
        require(!laterReaderFuture.isDone(), "queued reader must wait until writer exits");

        writer.close();
        final Lease laterReader = laterReaderFuture.join();
        require(!laterReader.isExclusive(), "queued reader must receive a shared lease");
        laterReader.close();

        final Lease cancellationReader = FiestaRegionLeaseManager.acquireShared(dimension, 4, 5).join();
        final CompletableFuture<Lease> cancelledWriter = FiestaRegionLeaseManager.acquireExclusive(dimension, 4, 5);
        require(cancelledWriter.cancel(false), "queued request should cancel");
        cancellationReader.close();
        final Lease afterCancellation = FiestaRegionLeaseManager.acquireExclusive(dimension, 4, 5).join();
        afterCancellation.close();

        final Lease independent = FiestaRegionLeaseManager.acquireExclusive(otherDimension, -1, 2).join();
        independent.close();

        final Path processLeaseRoot = Files.createTempDirectory("fiesta-process-lease-");
        final Path processLockPath = processLeaseRoot.resolve(".world-tier/paper-leases/r.-3.8.lock");
        try (final ProcessLease processLease = FiestaRegionLeaseManager.acquireProcessLease(processLeaseRoot, -3, 8)) {
            require(Files.isRegularFile(processLockPath), "process lease must create its lock file");
            require(Files.getPosixFilePermissions(processLockPath).size() == 2,
                "process lease file must allow only owner read and write");
            try (final FileChannel channel = FileChannel.open(processLockPath, StandardOpenOption.WRITE)) {
                boolean overlapping = false;
                try {
                    channel.tryLock();
                } catch (final OverlappingFileLockException expected) {
                    overlapping = true;
                }
                require(overlapping, "process lease must hold an exclusive OS file lock");
            }
        }
        try (final ProcessLease ignored = FiestaRegionLeaseManager.acquireProcessLease(processLeaseRoot, -3, 8)) { }
        Files.delete(processLockPath);
        Files.delete(processLockPath.getParent());
        Files.delete(processLockPath.getParent().getParent());
        Files.delete(processLeaseRoot);

        System.out.println("Fiesta region lease manager checks passed");
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
