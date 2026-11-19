package net.minecraft.server;

import ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Optional driver for the Fiesta world-tier dry-run planner and
 * Paper's exclusive region offload API. It is disabled unless both the target
 * byte budget and a positive interval are explicitly configured.
 */
public final class FiestaWorldTierScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(FiestaWorldTierScheduler.class);
    private static final String COMMAND_PROPERTY = "fiesta.worldTier.command";
    private static final String CONFIG_PROPERTY = "fiesta.worldTier.config";
    private static final String TARGET_PROPERTY = "fiesta.worldTier.schedulerTargetBytes";
    private static final String INTERVAL_PROPERTY = "fiesta.worldTier.schedulerIntervalSeconds";
    private static final String ALLOW_ONLINE_PROPERTY = "fiesta.worldTier.schedulerAllowOnline";
    private static final String TIMEOUT_PROPERTY = "fiesta.worldTier.schedulerPlanTimeoutSeconds";
    private static final String DEFAULT_COMMAND = "/usr/local/bin/fiesta-world-tier";
    private static final String DEFAULT_CONFIG = "/etc/fiesta-world-tier/config.json";
    private static final long TARGET_BYTES = longProperty(TARGET_PROPERTY, -1L);
    private static final long INTERVAL_SECONDS = longProperty(INTERVAL_PROPERTY, 0L);
    private static final boolean ALLOW_ONLINE = Boolean.getBoolean(ALLOW_ONLINE_PROPERTY);
    private static final long PLAN_TIMEOUT_SECONDS = longProperty(TIMEOUT_PROPERTY, 120L);
    private static final int MAX_PLAN_OUTPUT_BYTES = 64 * 1024;
    private static final int MAX_CANDIDATES_PER_PASS = 8;
    private static final AtomicBoolean ACTIVE = new AtomicBoolean();
    private static final ExecutorService PLANNER = Executors.newSingleThreadExecutor(task -> {
        final Thread thread = new Thread(task, "Fiesta World Tier Planner");
        thread.setDaemon(true);
        return thread;
    });
    private static long nextAttemptNanos;

    private FiestaWorldTierScheduler() {}

    /** Called from both active and idle server ticks; never runs planning on the tick thread. */
    public static void tick(final MinecraftServer server) {
        if (TARGET_BYTES <= 0L || INTERVAL_SECONDS <= 0L || INTERVAL_SECONDS > 604_800L
            || PLAN_TIMEOUT_SECONDS <= 0L || PLAN_TIMEOUT_SECONDS > 900L) {
            return;
        }
        final long intervalNanos = TimeUnit.SECONDS.toNanos(INTERVAL_SECONDS);
        final long now = System.nanoTime();
        if (now < nextAttemptNanos || !ACTIVE.compareAndSet(false, true)) {
            return;
        }
        nextAttemptNanos = now + intervalNanos;

        // Keep file scanning and the Luna quota probe off Paper's server thread.
        // The offload API rechecks loaded chunks and tickets under its lease.
        if (!ALLOW_ONLINE && server.getPlayerList().getPlayerCount() != 0) {
            ACTIVE.set(false);
            return;
        }
        try {
            PLANNER.execute(() -> {
                try {
                    final List<Selection> selections = chooseCandidates();
                    executeOnServer(server, () -> startSelectedOffload(server, selections, 0));
                } catch (final Exception ex) {
                    LOGGER.warn("Fiesta world-tier planning failed; no region was offloaded", ex);
                    executeOnServer(server, () -> ACTIVE.set(false));
                }
            });
        } catch (final RuntimeException ex) {
            ACTIVE.set(false);
            LOGGER.warn("Could not schedule Fiesta world-tier planning", ex);
        }
    }

    private static List<Selection> chooseCandidates() throws IOException, InterruptedException {
        final Path command = configuredPath(COMMAND_PROPERTY, DEFAULT_COMMAND, true);
        final Path config = configuredPath(CONFIG_PROPERTY, DEFAULT_CONFIG, false);
        final ProcessBuilder builder = new ProcessBuilder(
            command.toString(), "plan", "--config", config.toString(),
            "--target-bytes", Long.toString(TARGET_BYTES),
            "--max-selected", Integer.toString(MAX_CANDIDATES_PER_PASS)
        );
        final Map<String, String> environment = builder.environment();
        environment.clear();
        environment.put("PATH", "/usr/local/bin:/usr/bin:/bin");
        environment.put("LANG", "C");
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);

        final Process process = builder.start();
        try {
            if (!process.waitFor(PLAN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(Math.min(PLAN_TIMEOUT_SECONDS, 10L), TimeUnit.SECONDS);
                throw new IOException("Fiesta world-tier planner timed out");
            }
        } catch (final InterruptedException ex) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw ex;
        }
        if (process.exitValue() != 0) {
            throw new IOException("Fiesta world-tier planner exited with code " + process.exitValue());
        }
        final byte[] output = process.getInputStream().readNBytes(MAX_PLAN_OUTPUT_BYTES + 1);
        if (output.length > MAX_PLAN_OUTPUT_BYTES) {
            throw new IOException("Fiesta world-tier planner output exceeded its size limit");
        }
        final JsonElement parsed;
        try {
            parsed = JsonParser.parseString(new String(output, StandardCharsets.UTF_8));
        } catch (final RuntimeException ex) {
            throw new IOException("Fiesta world-tier planner returned invalid JSON", ex);
        }
        if (!parsed.isJsonObject()) {
            throw new IOException("Fiesta world-tier planner returned an invalid result object");
        }
        final JsonObject result = parsed.getAsJsonObject();
        if (!result.has("mode") || !"dry-run".equals(result.get("mode").getAsString())
            || !result.has("automatic_eviction_enabled")
            || result.get("automatic_eviction_enabled").getAsBoolean()) {
            throw new IOException("Fiesta world-tier planner result did not satisfy the dry-run contract");
        }
        if (!result.has("cold_age_days") || !result.get("cold_age_days").isJsonPrimitive()
            || !result.getAsJsonPrimitive("cold_age_days").isNumber()) {
            throw new IOException("Fiesta world-tier planner omitted its cold-age setting");
        }
        final double coldAgeMillisValue = result.get("cold_age_days").getAsDouble() * 86_400_000.0;
        if (!Double.isFinite(coldAgeMillisValue) || coldAgeMillisValue <= 0.0
            || coldAgeMillisValue > Long.MAX_VALUE) {
            throw new IOException("Fiesta world-tier planner returned an invalid cold-age setting");
        }
        final long coldAgeMillis = (long)Math.ceil(coldAgeMillisValue);
        final JsonArray selected = result.getAsJsonArray("selected");
        if (selected == null || selected.size() == 0) {
            return List.of();
        }
        if (selected.size() > MAX_CANDIDATES_PER_PASS) {
            throw new IOException("Fiesta world-tier planner exceeded the candidate limit");
        }
        final java.util.ArrayList<Selection> candidates = new java.util.ArrayList<>(selected.size());
        for (final JsonElement element : selected) {
            if (!element.isJsonObject()) {
                throw new IOException("Fiesta world-tier planner returned a malformed candidate");
            }
            final JsonObject region = element.getAsJsonObject();
            final String dimension = region.get("dimension").getAsString();
            final int x = region.get("region_x").getAsInt();
            final int z = region.get("region_z").getAsInt();
            if (!("overworld".equals(dimension) || "nether".equals(dimension) || "end".equals(dimension))) {
                throw new IOException("Fiesta world-tier planner selected an unsupported dimension");
            }
            candidates.add(new Selection(dimension, x, z, coldAgeMillis));
        }
        return List.copyOf(candidates);
    }

    private static void startSelectedOffload(final MinecraftServer server,
                                             final List<Selection> selections, final int index) {
        if (index >= selections.size()) {
            ACTIVE.set(false);
            return;
        }
        // Default mode remains idle-only. Online mode may select a cold region
        // while other players are active; Paper's exclusive region lease
        // rejects a candidate with holders or tickets before closing files.
        if (!ALLOW_ONLINE && server.getPlayerList().getPlayerCount() != 0) {
            ACTIVE.set(false);
            return;
        }
        final Selection selection = selections.get(index);
        final ServerLevel world = switch (selection.dimension()) {
            case "overworld" -> server.getLevel(Level.OVERWORLD);
            case "nether" -> server.getLevel(Level.NETHER);
            case "end" -> server.getLevel(Level.END);
            default -> null;
        };
        if (world == null) {
            LOGGER.warn("Fiesta world-tier planner selected an unavailable dimension: {}", selection.dimension());
            startSelectedOffload(server, selections, index + 1);
            return;
        }
        LOGGER.info("Fiesta world-tier scheduler attempting cold region {}/r.{}.{}",
            selection.dimension(), selection.x(), selection.z());
        try {
            MoonriseRegionFileIO.offloadRegion(world, selection.x(), selection.z(), selection.coldAgeMillis())
                .whenComplete((ignored, error) -> {
                    executeOnServer(server, () -> {
                        if (error == null) {
                            LOGGER.info("Fiesta world-tier offloaded {}/r.{}.{}",
                                selection.dimension(), selection.x(), selection.z());
                            ACTIVE.set(false);
                        } else {
                            LOGGER.info("Fiesta world-tier skipped {}/r.{}.{}; trying the next cold candidate: {}",
                                selection.dimension(), selection.x(), selection.z(), error);
                            startSelectedOffload(server, selections, index + 1);
                        }
                    });
                });
        } catch (final RuntimeException ex) {
            LOGGER.info("Fiesta world-tier could not start {}/r.{}.{}; trying the next cold candidate",
                selection.dimension(), selection.x(), selection.z(), ex);
            startSelectedOffload(server, selections, index + 1);
        }
    }

    private static void executeOnServer(final MinecraftServer server, final Runnable task) {
        try {
            server.execute(task);
        } catch (final RuntimeException ex) {
            ACTIVE.set(false);
            LOGGER.warn("Could not return Fiesta world-tier task to the server thread", ex);
        }
    }

    private static Path configuredPath(final String property, final String defaultValue, final boolean executable)
        throws IOException {
        final Path path;
        try {
            path = Path.of(System.getProperty(property, defaultValue)).toAbsolutePath().normalize();
        } catch (final RuntimeException ex) {
            throw new IOException("Invalid Fiesta world-tier path configured by " + property, ex);
        }
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
            || (executable && !Files.isExecutable(path))) {
            throw new IOException("Fiesta world-tier " + (executable ? "command" : "config")
                + " is missing or has an unsafe path type: " + path);
        }
        return path;
    }

    private static long longProperty(final String name, final long defaultValue) {
        try {
            return Long.parseLong(System.getProperty(name, Long.toString(defaultValue)));
        } catch (final NumberFormatException ex) {
            LOGGER.warn("Invalid {} value; using its disabled/default value", name);
            return defaultValue;
        }
    }

    private record Selection(String dimension, int x, int z, long coldAgeMillis) {}
}
