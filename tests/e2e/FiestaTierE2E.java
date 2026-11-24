package org.openfiestasmp.worldtier.test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Barrel;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

/** Disposable-only probe for saving, restoring, and testing a remote outage. */
public final class FiestaTierE2E extends JavaPlugin {
    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onPlayerJoin(final PlayerJoinEvent event) {
                final String mode = System.getProperty("fiesta.test.mode", "");
                if (!(mode.equals("outage") || mode.equals("cold"))) {
                    return;
                }
                final World world = Bukkit.getWorld("world");
                if (world == null) {
                    return;
                }
                final int chunkX = Integer.getInteger("fiesta.test.chunkX", 0);
                final int chunkZ = Integer.getInteger("fiesta.test.chunkZ", 0);
                final Location target = new Location(world, chunkX * 16 + 8, 100.0D, chunkZ * 16 + 8);
                if (mode.equals("cold")) {
                    final long coldStartedNanos = System.nanoTime();
                    report("COLD_RESTORE_REQUESTED");
                    final Player player = event.getPlayer();
                    player.teleportAsync(target);
                    new BukkitRunnable() {
                        private int ticks;

                        @Override
                        public void run() {
                            if (!player.isOnline()) {
                                report("COLD_CLIENT_DISCONNECTED");
                                cancel();
                                Bukkit.shutdown();
                                return;
                            }
                            final Location current = player.getLocation();
                            if (player.getWorld() == world
                                && (current.getBlockX() >> 4) == chunkX
                                && (current.getBlockZ() >> 4) == chunkZ
                                && world.isChunkLoaded(chunkX, chunkZ)) {
                                if (world.getBlockAt(chunkX * 16 + 1, 80, chunkZ * 16 + 1).getType()
                                    != Material.GOLD_BLOCK) {
                                    report("COLD_CLIENT_BLOCK_CHECK_FAILED");
                                    cancel();
                                    Bukkit.shutdown();
                                    return;
                                }
                                final long coldLoadMillis = TimeUnit.NANOSECONDS.toMillis(
                                    System.nanoTime() - coldStartedNanos
                                );
                                report("COLD_CLIENT_CHUNK_READY:" + coldLoadMillis + "ms");
                                cancel();
                                final long keepAliveSeconds = Long.getLong("fiesta.test.keepAliveSeconds", 10L);
                                if (keepAliveSeconds > 0L) {
                                    Bukkit.getScheduler().runTaskLater(
                                        FiestaTierE2E.this, Bukkit::shutdown, keepAliveSeconds * 20L
                                    );
                                } else {
                                    Bukkit.shutdown();
                                }
                                return;
                            }
                            if (++this.ticks >= 1800) {
                                report("COLD_CLIENT_TIMEOUT");
                                cancel();
                                Bukkit.shutdown();
                            }
                        }
                    }.runTaskTimer(FiestaTierE2E.this, 1L, 1L);
                } else {
                    event.getPlayer().teleportAsync(target).thenAccept(success ->
                        report(success ? "PLAYER_TELEPORTED_TO_TARGET" : "PLAYER_TELEPORT_FAILED")
                    );
                }
            }
        }, this);
        if (!"cold".equals(System.getProperty("fiesta.test.mode", ""))) {
            Bukkit.getScheduler().runTaskLater(this, this::runProbe, 20L);
        }
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
            if (!(mode.equals("seed") || mode.equals("verify") || mode.equals("outage")
                || mode.equals("seed-rich") || mode.equals("verify-rich")
                || mode.equals("seed-entity-rich") || mode.equals("verify-entity-rich"))) {
                throw new IllegalArgumentException("unsupported test mode");
            }
            final long loadStartedNanos = System.nanoTime();
            final var load = world.getChunkAtAsync(
                chunkX, chunkZ, mode.equals("seed") || mode.equals("seed-rich")
                    || mode.equals("seed-entity-rich")
            );
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
                case "seed-rich" -> {
                    seedRichRegion(world, chunkX, chunkZ);
                    world.save();
                    report("SEEDED_FULL_DATA:" + chunkLoadMillis + "ms");
                }
                case "verify-rich" -> {
                    verifyRichRegion(world, chunk, chunkX, chunkZ, actual);
                    report("FULL_DATA_RESTORED:" + chunkLoadMillis + "ms");
                }
                case "seed-entity-rich" -> {
                    seedEntitySidecar(world, chunkX, chunkZ);
                    world.save();
                    report("SEEDED_ENTITY_SIDECAR:" + chunkLoadMillis + "ms");
                }
                case "verify-entity-rich" -> {
                    verifyEntitySidecar(world, chunk, chunkX, chunkZ);
                    report("VERIFIED_ENTITY_SIDECAR:" + chunkLoadMillis + "ms");
                }
                case "outage" -> {
                    if (actual != Material.GOLD_BLOCK) {
                        throw new IllegalStateException("expected GOLD_BLOCK after the outage, found " + actual);
                    }
                    report("OUTAGE_RECOVERED:" + chunkLoadMillis + "ms");
                    Bukkit.getScheduler().runTaskLater(this, Bukkit::shutdown, 300L);
                    deferredShutdown = true;
                }
                default -> throw new IllegalArgumentException("unsupported test mode");
            }
        } catch (final Throwable error) {
            finishFailure(mode, error);
        } finally {
            if (!deferredShutdown) {
                final long keepAliveSeconds = Long.getLong("fiesta.test.keepAliveSeconds", 10L);
                if (keepAliveSeconds > 0L) {
                    Bukkit.getScheduler().runTaskLater(this, Bukkit::shutdown, keepAliveSeconds * 20L);
                } else {
                    Bukkit.shutdown();
                }
            }
        }
    }

    private void seedRichRegion(World world, int chunkX, int chunkZ) {
        final int baseX = chunkX * 16;
        final int baseZ = chunkZ * 16;
        world.getBlockAt(baseX + 1, 80, baseZ + 1).setType(Material.GOLD_BLOCK, false);
        world.getBlockAt(baseX + 4, 81, baseZ + 1).setType(Material.LECTERN, false);

        final ArmorStand stand = (ArmorStand)world.spawnEntity(
            new Location(world, baseX + 5.5, 81.0, baseZ + 5.5), EntityType.ARMOR_STAND
        );
        stand.setCustomName("fiesta-tier-e2e-entity");
        stand.setPersistent(true);
        stand.setGravity(false);

        final NamespacedKey payloadKey = new NamespacedKey(this, "tier_payload");
        for (int barrelX = 0; barrelX < 4; ++barrelX) {
            for (int barrelZ = 0; barrelZ < 4; ++barrelZ) {
                final int x = baseX + 1 + barrelX * 3;
                final int z = baseZ + 1 + barrelZ * 3;
                world.getBlockAt(x, 82, z).setType(Material.BARREL, false);
                final Barrel barrel = (Barrel)world.getBlockAt(x, 82, z).getState();
                final var inventory = barrel.getSnapshotInventory();
                for (int slot = 0; slot < inventory.getSize(); ++slot) {
                    final ItemStack item = new ItemStack(Material.PAPER);
                    final ItemMeta meta = item.getItemMeta();
                    meta.getPersistentDataContainer().set(
                        payloadKey, PersistentDataType.BYTE_ARRAY, oversizedPayload(x, z, slot)
                    );
                    item.setItemMeta(meta);
                    inventory.setItem(slot, item);
                }
                if (!barrel.update(true, false)) {
                    throw new IllegalStateException("could not save oversized-chunk fixture barrel");
                }
            }
        }
    }

    private void verifyRichRegion(World world, org.bukkit.Chunk chunk, int chunkX, int chunkZ, Material terrain)
        throws IOException {
        if (terrain != Material.GOLD_BLOCK) {
            throw new IllegalStateException("expected GOLD_BLOCK, found " + terrain);
        }
        final int baseX = chunkX * 16;
        final int baseZ = chunkZ * 16;
        final Path worldRoot = world.getWorldFolder().toPath();
        final String regionName = "r." + (chunkX >> 5) + "." + (chunkZ >> 5) + ".mca";
        if (!Files.isRegularFile(worldRoot.resolve("entities").resolve(regionName))) {
            throw new IllegalStateException("restored entity region file is missing");
        }
        if (!Files.isRegularFile(worldRoot.resolve("poi").resolve(regionName))) {
            throw new IllegalStateException("restored POI region file is missing");
        }
        final Path sidecar = worldRoot.resolve("region").resolve("c." + chunkX + "." + chunkZ + ".mcc");
        if (!Files.isRegularFile(sidecar) || Files.size(sidecar) <= 1_048_576L) {
            throw new IllegalStateException("restored oversized chunk sidecar is missing or too small");
        }
        if (world.getBlockAt(baseX + 4, 81, baseZ + 1).getType() != Material.LECTERN) {
            throw new IllegalStateException("restored POI block is missing");
        }
        boolean entityFound = false;
        for (final Entity entity : chunk.getEntities()) {
            if ("fiesta-tier-e2e-entity".equals(entity.getCustomName())) {
                entityFound = true;
                break;
            }
        }
        if (!entityFound) {
            throw new IllegalStateException("restored entity marker is missing");
        }
        final Barrel barrel = (Barrel)world.getBlockAt(baseX + 1, 82, baseZ + 1).getState();
        final ItemStack item = barrel.getSnapshotInventory().getItem(0);
        final byte[] payload = item == null || !item.hasItemMeta() ? null
            : item.getItemMeta().getPersistentDataContainer().get(
                new NamespacedKey(this, "tier_payload"), PersistentDataType.BYTE_ARRAY
            );
        if (!Arrays.equals(payload, oversizedPayload(baseX + 1, baseZ + 1, 0))) {
            throw new IllegalStateException("restored oversized chunk data is missing");
        }
    }

    private static byte[] oversizedPayload(int x, int z, int slot) {
        final byte[] payload = new byte[4096];
        final long seed = 0x4653544100000000L ^ ((long)x << 32) ^ ((long)z << 8) ^ slot;
        new Random(seed).nextBytes(payload);
        return payload;
    }

    private void seedEntitySidecar(World world, int chunkX, int chunkZ) {
        final int baseX = chunkX * 16;
        final int baseZ = chunkZ * 16;
        world.getBlockAt(baseX + 4, 81, baseZ + 1).setType(Material.LECTERN, false);
        final ArmorStand stand = (ArmorStand)world.spawnEntity(
            new Location(world, baseX + 5.5, 90.0, baseZ + 5.5), EntityType.ARMOR_STAND
        );
        stand.setCustomName("fiesta-tier-e2e-large-entity");
        stand.setPersistent(true);
        stand.setGravity(false);
        stand.getPersistentDataContainer().set(
            new NamespacedKey(this, "large_entity_payload"), PersistentDataType.BYTE_ARRAY,
            entityPayload(chunkX, chunkZ)
        );
    }

    private void verifyEntitySidecar(World world, org.bukkit.Chunk chunk, int chunkX, int chunkZ)
        throws IOException {
        final Path worldRoot = world.getWorldFolder().toPath();
        final String regionName = "r." + (chunkX >> 5) + "." + (chunkZ >> 5) + ".mca";
        if (!Files.isRegularFile(worldRoot.resolve("entities").resolve(regionName))) {
            throw new IllegalStateException("entity region file is missing");
        }
        if (!Files.isRegularFile(worldRoot.resolve("poi").resolve(regionName))) {
            throw new IllegalStateException("POI region file is missing");
        }
        final Path sidecar = worldRoot.resolve("entities").resolve("c." + chunkX + "." + chunkZ + ".mcc");
        if (!Files.isRegularFile(sidecar) || Files.size(sidecar) <= 1_048_576L) {
            throw new IllegalStateException("oversized entity sidecar is missing or too small");
        }
        if (world.getBlockAt(chunkX * 16 + 4, 81, chunkZ * 16 + 1).getType() != Material.LECTERN) {
            throw new IllegalStateException("POI fixture did not survive restart");
        }
        for (final Entity entity : chunk.getEntities()) {
            if (!"fiesta-tier-e2e-large-entity".equals(entity.getCustomName())) {
                continue;
            }
            final byte[] payload = entity.getPersistentDataContainer().get(
                new NamespacedKey(this, "large_entity_payload"), PersistentDataType.BYTE_ARRAY
            );
            if (Arrays.equals(payload, entityPayload(chunkX, chunkZ))) {
                return;
            }
            throw new IllegalStateException("large entity payload did not survive restart");
        }
        throw new IllegalStateException("large persistent entity is missing after restart");
    }

    private static byte[] entityPayload(int chunkX, int chunkZ) {
        final byte[] payload = new byte[1_500_000];
        final long seed = 0x4653544100000000L ^ ((long)chunkX << 32) ^ chunkZ;
        new Random(seed).nextBytes(payload);
        return payload;
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
