package org.openfiestasmp.worldtier.test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

/** Disposable-only probe for saving, restoring, and testing a remote outage. */
public final class FiestaTierE2E extends JavaPlugin {
    @Override
    public void onEnable() {
        Bukkit.getScheduler().runTaskLater(this, this::runProbe, 20L);
    }

    private void runProbe() {
        final String mode = System.getProperty("fiesta.test.mode", "");
        try {
            final World world = Bukkit.getWorld("world");
            if (world == null) {
                throw new IllegalStateException("test overworld is not loaded");
            }
            final int chunkX = Integer.getInteger("fiesta.test.chunkX", 0);
            final int chunkZ = Integer.getInteger("fiesta.test.chunkZ", 0);
            if (!(mode.equals("seed") || mode.equals("verify") || mode.equals("outage"))) {
                throw new IllegalArgumentException("unsupported test mode");
            }
            final long loadStartedNanos = System.nanoTime();
            world.getChunkAtAsync(chunkX, chunkZ, mode.equals("seed")).whenComplete((chunk, failure) -> {
                final long chunkLoadMillis = TimeUnit.NANOSECONDS.toMillis(
                    System.nanoTime() - loadStartedNanos
                );
                Bukkit.getScheduler().runTask(this, () -> finishProbe(
                    mode, world, chunkX, chunkZ, chunk, failure, chunkLoadMillis
                ));
            });
        } catch (final Throwable error) {
            finishFailure(mode, error);
        }
    }

    private void finishProbe(String mode, World world, int chunkX, int chunkZ,
                             org.bukkit.Chunk chunk, Throwable failure, long chunkLoadMillis) {
        try {
            if (failure != null) {
                finishFailure(mode, failure);
                return;
            }
            if (chunk == null) {
                throw new IllegalStateException("Paper completed chunk load without a chunk");
            }
            final int blockX = chunkX * 16 + 1;
            final int blockZ = chunkZ * 16 + 1;
            final Material actual = world.getBlockAt(blockX, 80, blockZ).getType();
            switch (mode) {
                case "seed" -> {
                    world.getBlockAt(blockX, 80, blockZ).setType(Material.GOLD_BLOCK);
                    world.save();
                    report("SEEDED:" + chunkLoadMillis + "ms");
                }
                case "verify" -> {
                    if (actual != Material.GOLD_BLOCK) {
                        throw new IllegalStateException("expected GOLD_BLOCK, found " + actual);
                    }
                    report("RESTORED:" + chunkLoadMillis + "ms");
                }
                case "outage" -> report("OUTAGE_RETURNED:" + actual);
                default -> throw new IllegalArgumentException("unsupported test mode");
            }
        } catch (final Throwable error) {
            finishFailure(mode, error);
        } finally {
            Bukkit.shutdown();
        }
    }

    private void finishFailure(String mode, Throwable error) {
        getLogger().severe("FIESTA_TIER_E2E_EXCEPTION: " + error);
        for (Throwable cause = error.getCause(); cause != null; cause = cause.getCause()) {
            getLogger().severe("FIESTA_TIER_E2E_CAUSE: " + cause);
        }
        report("FAILED:" + error.getClass().getSimpleName());
    }

    private void report(final String result) {
        getLogger().info("FIESTA_TIER_E2E_" + result);
        final String resultPath = System.getProperty("fiesta.test.result");
        if (resultPath == null || resultPath.isBlank()) {
            return;
        }
        try {
            final Path path = Path.of(resultPath);
            Files.createDirectories(path.toAbsolutePath().getParent());
            Files.writeString(path, result + "\n");
        } catch (final Exception error) {
            getLogger().severe("Could not write test result: " + error);
        }
    }
}
