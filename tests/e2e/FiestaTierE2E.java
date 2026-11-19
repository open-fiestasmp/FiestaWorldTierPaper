package org.openfiestasmp.worldtier.test;

import java.nio.file.Files;
import java.nio.file.Path;
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
            world.getChunkAt(chunkX, chunkZ);
            final int blockX = chunkX * 16 + 1;
            final int blockZ = chunkZ * 16 + 1;
            final Material actual = world.getBlockAt(blockX, 80, blockZ).getType();
            switch (mode) {
                case "seed" -> {
                    world.getBlockAt(blockX, 80, blockZ).setType(Material.GOLD_BLOCK);
                    world.save();
                    report("SEEDED");
                }
                case "verify" -> {
                    if (actual != Material.GOLD_BLOCK) {
                        throw new IllegalStateException("expected GOLD_BLOCK, found " + actual);
                    }
                    report("RESTORED");
                }
                case "outage" -> report("OUTAGE_RETURNED:" + actual);
                default -> throw new IllegalArgumentException("unsupported test mode");
            }
        } catch (final Throwable error) {
            getLogger().severe("FIESTA_TIER_E2E_EXCEPTION: " + error);
            for (Throwable cause = error.getCause(); cause != null; cause = cause.getCause()) {
                getLogger().severe("FIESTA_TIER_E2E_CAUSE: " + cause);
            }
            if ("outage".equals(mode) && isWorldTierFailure(error)) {
                report("FAILED_CLOSED");
            } else {
                report("FAILED:" + error.getClass().getSimpleName());
            }
        } finally {
            Bukkit.shutdown();
        }
    }

    private static boolean isWorldTierFailure(final Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause.getClass().getName().contains("FiestaWorldTierRestoreException")) {
                return true;
            }
        }
        return false;
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
