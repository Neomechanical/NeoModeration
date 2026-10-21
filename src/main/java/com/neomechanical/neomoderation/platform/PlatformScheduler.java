package com.neomechanical.neomoderation.platform;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Runs work on whichever scheduler the running server actually has.
 *
 * <p>Folia replaced the single main thread with per-region threads and removed
 * {@code BukkitScheduler#runTask}, which throws {@code UnsupportedOperationException}
 * there. That, plus a missing {@code folia-supported} flag in plugin.yml, meant
 * Folia refused to load NeoModeration at all -- an entire class of server could
 * not install it.
 *
 * <p>Everything here is reflective on purpose. The plugin compiles against an
 * old Spigot API so it keeps working back to 1.18.2, and the Folia classes do
 * not exist in that API or on a Paper server. Lookups happen once at construction
 * ({@link Folia#resolve}); the hot path costs one field read plus, on Folia, an
 * already-resolved {@link Method} invocation.
 *
 * <p>The distinction that matters on Folia is <em>which</em> thread owns the
 * state you are about to touch. Work affecting one player must run on that
 * player's entity scheduler, not on a global thread, so {@link #runForEntity}
 * is the correct call for kicks, mutes, messages and inventory edits.
 */
public final class PlatformScheduler {

    private static final String FOLIA_MARKER = "io.papermc.paper.threadedregions.RegionizedServer";

    private final Plugin plugin;
    /** Folia's schedulers, or null on Spigot and Paper. */
    private final Folia folia;

    public PlatformScheduler(Plugin plugin) {
        this(plugin, detectFolia() ? Folia.resolve(plugin, Bukkit.class, Entity.class) : null);
    }

    PlatformScheduler(Plugin plugin, Folia folia) {
        this.plugin = plugin;
        this.folia = folia;
    }

    /** True when running on Folia (or a fork exposing its regionised server). */
    public boolean isFolia() {
        return folia != null;
    }

    /** A short platform name, used in telemetry and the User-Agent. */
    public String platformName() {
        return folia != null ? "Folia" : Bukkit.getName();
    }

    private static boolean detectFolia() {
        try {
            Class.forName(FOLIA_MARKER);
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }

    /**
     * Folia's schedulers, looked up once: {@code bukkit} holds the static getters ({@code Bukkit} on a server), and
     * {@code entity} the type whose {@code getScheduler} runs one entity's work. A part this fork lacks is null, and
     * its work falls back to the Bukkit scheduler, which complains loudly rather than disabling moderation.
     */
    record Folia(Method globalRegionScheduler, Method globalExecute, Method asyncScheduler, Method asyncRunNow,
                 Method asyncRunAtFixedRate, Method cancelScheduled, Method entityGetScheduler, Method entityExecute) {

        static Folia resolve(Plugin plugin, Class<?> bukkit, Class<?> entity) {
            Method globalRegionScheduler = null;
            Method globalExecute = null;
            Method asyncScheduler = null;
            Method asyncRunNow = null;
            Method entityGetScheduler = null;
            Method entityExecute = null;
            try {
                globalRegionScheduler = bukkit.getMethod("getGlobalRegionScheduler");
                globalExecute = globalRegionScheduler.getReturnType().getMethod("execute", Plugin.class, Runnable.class);
                asyncScheduler = bukkit.getMethod("getAsyncScheduler");
                asyncRunNow = asyncScheduler.getReturnType().getMethod("runNow", Plugin.class, Consumer.class);
                entityGetScheduler = entity.getMethod("getScheduler");
                entityExecute = entityGetScheduler.getReturnType()
                        .getMethod("execute", Plugin.class, Runnable.class, Runnable.class, long.class);
            } catch (ReflectiveOperationException error) {
                // A Folia fork we do not recognise.
                plugin.getLogger().warning("Folia detected but its schedulers could not be resolved ("
                        + error.getMessage() + "); falling back to the Bukkit scheduler.");
                globalExecute = null;
            }
            Method asyncRunAtFixedRate = null;
            Method cancelScheduled = null;
            try {
                asyncRunAtFixedRate = bukkit.getMethod("getAsyncScheduler").getReturnType().getMethod(
                        "runAtFixedRate", Plugin.class, Consumer.class, long.class, long.class, TimeUnit.class);
                // From the task's interface: the class Folia returns is not public, so its own method cannot be called.
                cancelScheduled = asyncRunAtFixedRate.getReturnType().getMethod("cancel");
            } catch (ReflectiveOperationException error) {
                asyncRunAtFixedRate = null;
            }
            return new Folia(globalRegionScheduler, globalExecute, asyncScheduler, asyncRunNow, asyncRunAtFixedRate,
                    cancelScheduled, entityGetScheduler, entityExecute);
        }
    }

    /** Off the server thread. Safe to block on I/O. */
    public void runAsync(Runnable task) {
        if (folia != null && folia.asyncRunNow() != null) {
            try {
                Object scheduler = folia.asyncScheduler().invoke(null);
                folia.asyncRunNow().invoke(scheduler, plugin, (Consumer<Object>) ignored -> task.run());
                return;
            } catch (ReflectiveOperationException ignored) {
                // fall through
            }
        }
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, task);
    }

    /**
     * Every {@code minutes}, off the server thread, first {@code minutes} from now; returns what cancels it, which the
     * plugin runs when it stops. On Folia the period is wall-clock time; on Spigot and Paper it is counted in ticks, so
     * a lagging server stretches it. A server whose scheduler refuses it logs "Could not schedule a repeating task",
     * which the release matrix looks for, and runs without it.
     */
    public Runnable runAsyncEvery(long minutes, Runnable task) {
        try {
            if (folia != null && folia.asyncScheduler() != null && folia.asyncRunAtFixedRate() != null) {
                Object scheduled = folia.asyncRunAtFixedRate().invoke(folia.asyncScheduler().invoke(null), plugin,
                        (Consumer<Object>) ignored -> task.run(), minutes, minutes, TimeUnit.MINUTES);
                Method cancel = folia.cancelScheduled();
                return () -> {
                    try {
                        cancel.invoke(scheduled);
                    } catch (ReflectiveOperationException ignored) {
                        // The server cancels it with the plugin anyway.
                    }
                };
            }
            long ticks = TimeUnit.MINUTES.toSeconds(minutes) * 20L;
            return plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, task, ticks, ticks)::cancel;
        } catch (ReflectiveOperationException | RuntimeException error) {
            plugin.getLogger().warning("Could not schedule a repeating task (" + error.getMessage() + ").");
            return () -> { };
        }
    }

    /**
     * Work that belongs to no particular entity or region -- config reloads,
     * counters, console output.
     */
    public void runGlobal(Runnable task) {
        if (folia != null && folia.globalExecute() != null) {
            try {
                folia.globalExecute().invoke(folia.globalRegionScheduler().invoke(null), plugin, task);
                return;
            } catch (ReflectiveOperationException ignored) {
                // fall through
            }
        }
        plugin.getServer().getScheduler().runTask(plugin, task);
    }

    /**
     * Work that touches one player or entity. On Folia this is the only correct
     * place to kick, mute, message or edit the inventory of that player.
     *
     * <p>If the entity has been removed before the task runs, Folia drops it;
     * that is the intended behaviour for a punishment aimed at someone who has
     * already disconnected.
     */
    public void runForEntity(Entity entity, Runnable task) {
        runForEntity(entity, task, null);
    }

    /**
     * As {@link #runForEntity(Entity, Runnable)}, and {@code retired} runs instead when Folia drops the task because
     * the entity left first: when it left before the task was scheduled, Folia runs neither, so {@code retired} runs
     * here. Elsewhere the task always runs, so it checks for itself.
     */
    public void runForEntity(Entity entity, Runnable task, Runnable retired) {
        if (folia != null && folia.entityExecute() != null) {
            try {
                Object scheduler = folia.entityGetScheduler().invoke(entity);
                Object scheduled = folia.entityExecute().invoke(scheduler, plugin, task, retired, 1L);
                if (Boolean.FALSE.equals(scheduled) && retired != null) {
                    retired.run();
                }
                return;
            } catch (ReflectiveOperationException ignored) {
                // fall through
            }
        }
        plugin.getServer().getScheduler().runTask(plugin, task);
    }
}
