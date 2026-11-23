package org.openfiestasmp.worldtier.test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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
            final var load = world.getChunkAtAsync(chunkX, chunkZ, mode.equals("seed"));
            load.whenComplete((chunk, failure) -> {
                final long chunkLoadMillis = TimeUnit.NANOSECONDS.toMillis(
                    System.nanoTime() - loadStartedNanos
                );
                Bukkit.getScheduler().runTask(this, () -> finishProbe(
                    mode, world, chunkX, chunkZ, chunk, failure, chunkLoadMillis
                ));
            });
            if (mode.equals("outage")) {
                final String readyPath = System.getProperty("fiesta.test.restoreAvailable");
                if (readyPath == null || readyPath.isBlank()) {
                    throw new IllegalArgumentException("outage mode requires fiesta.test.restoreAvailable");
                }
                final int localChunkX = Integer.getInteger("fiesta.test.localChunkX", 128);
                final int localChunkZ = Integer.getInteger("fiesta.test.localChunkZ", 128);
                final var localProbe = world.getChunkAtAsync(localChunkX, localChunkZ, false);
                final long outageTicks = Math.max(1L,
                    Long.getLong("fiesta.test.outageMillis", 5_000L) / 50L);
                Bukkit.getScheduler().runTaskLater(this, () -> {
                    if (load.isDone()) {
                        report("FAILED:restore-did-not-wait");
                        Bukkit.shutdown();
                        return;
                    }
                    try {
                        if (!localProbe.isDone()) {
                            throw new IllegalStateException("local region read stalled during remote restore retry");
                        }
                        final org.bukkit.Chunk localChunk = localProbe.getNow(null);
                        if (localChunk == null || world.getBlockAt(localChunkX * 16 + 1, 80,
                                localChunkZ * 16 + 1).getType() != Material.DIAMOND_BLOCK) {
                            throw new IllegalStateException("local region data was not available during remote restore retry");
                        }
                        final Path ready = Path.of(readyPath);
                        Files.createDirectories(ready.toAbsolutePath().getParent());
                        Files.writeString(ready, "ready\n");
                        report("OUTAGE_SERVER_ALIVE_AND_LOCAL_CHUNK_LOADED");
                    } catch (final Exception error) {
                        finishFailure(mode, error);
                        Bukkit.shutdown();
                    }
                }, outageTicks);
                Bukkit.getScheduler().runTaskLater(this, () -> {
                    if (!load.isDone()) {
                        report("FAILED:restore-timeout");
                        Bukkit.shutdown();
                    }
                }, 1_200L);
            }
        } catch (final Throwable error) {
            finishFailure(mode, error);
        }
    }

    private void finishProbe(String mode, World world, int chunkX, int chunkZ,
                             org.bukkit.Chunk chunk, Throwable failure, long chunkLoadMillis) {
        boolean deferredShutdown = false;
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
                    final int localChunkX = Integer.getInteger("fiesta.test.localChunkX", 128);
                    final int localChunkZ = Integer.getInteger("fiesta.test.localChunkZ", 128);
                    world.getChunkAtAsync(localChunkX, localChunkZ, true).whenComplete((localChunk, localFailure) ->
                        Bukkit.getScheduler().runTask(this, () -> {
                            try {
                                if (localFailure != null) {
                                    throw new IllegalStateException("could not seed local probe chunk", localFailure);
                                }
                                if (localChunk == null) {
                                    throw new IllegalStateException("Paper completed local probe load without a chunk");
                                }
                                world.getBlockAt(localChunkX * 16 + 1, 80, localChunkZ * 16 + 1)
                                    .setType(Material.DIAMOND_BLOCK);
                                world.save();
                                report("SEEDED:" + chunkLoadMillis + "ms");
                            } catch (final Throwable error) {
                                finishFailure(mode, error);
                            } finally {
                                Bukkit.shutdown();
                            }
                        }));
                    deferredShutdown = true;
                    return;
                }
                case "verify" -> {
                    if (actual != Material.GOLD_BLOCK) {
                        throw new IllegalStateException("expected GOLD_BLOCK, found " + actual);
                    }
                    report("RESTORED:" + chunkLoadMillis + "ms");
                }
                case "outage" -> {
                    if (actual != Material.GOLD_BLOCK) {
                        throw new IllegalStateException("expected GOLD_BLOCK after the outage, found " + actual);
                    }
                    report("OUTAGE_RECOVERED:" + chunkLoadMillis + "ms");
                }
                default -> throw new IllegalArgumentException("unsupported test mode");
            }
        } catch (final Throwable error) {
            finishFailure(mode, error);
        } finally {
            if (!deferredShutdown) {
                Bukkit.shutdown();
            }
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
            Files.writeString(path, result + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (final Exception error) {
            getLogger().severe("Could not write test result: " + error);
        }
    }
}
