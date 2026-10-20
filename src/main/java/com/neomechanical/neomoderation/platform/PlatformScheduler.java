package com.neomechanical.neomoderation.platform;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
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
 * and the result is a boolean; the hot path costs one field read plus, on Folia,
 * an already-resolved {@link Method} invocation.
 *
 * <p>The distinction that matters on Folia is <em>which</em> thread owns the
 * state you are about to touch. Work affecting one player must run on that
 * player's entity scheduler, not on a global thread, so {@link #runForEntity}
 * is the correct call for kicks, mutes, messages and inventory edits.
 */
public final class PlatformScheduler {

    private static final String FOLIA_MARKER = "io.papermc.paper.threadedregions.RegionizedServer";

    private final Plugin plugin;
    private final boolean folia;

    private Method globalRegionScheduler;
    private Method globalExecute;
    private Method asyncScheduler;
    private Method asyncRunNow;
    private Method entityGetScheduler;
    private Method entityExecute;

    public PlatformScheduler(Plugin plugin) {
        this.plugin = plugin;
        this.folia = detectFolia();
        if (folia) {
            resolveFoliaMethods();
        }
    }

    /** True when running on Folia (or a fork exposing its regionised server). */
    public boolean isFolia() {
        return folia;
    }

    /** A short platform name, used in telemetry and the User-Agent. */
    public String platformName() {
        return folia ? "Folia" : Bukkit.getName();
    }

    private static boolean detectFolia() {
        try {
            Class.forName(FOLIA_MARKER);
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }

    private void resolveFoliaMethods() {
        try {
            globalRegionScheduler = Bukkit.class.getMethod("getGlobalRegionScheduler");
            Class<?> globalType = globalRegionScheduler.getReturnType();
            globalExecute = globalType.getMethod("execute", Plugin.class, Runnable.class);

            asyncScheduler = Bukkit.class.getMethod("getAsyncScheduler");
            asyncRunNow = asyncScheduler.getReturnType().getMethod("runNow", Plugin.class, Consumer.class);

            entityGetScheduler = Entity.class.getMethod("getScheduler");
            entityExecute = entityGetScheduler.getReturnType()
                    .getMethod("execute", Plugin.class, Runnable.class, Runnable.class, long.class);
        } catch (ReflectiveOperationException error) {
            // A Folia fork we do not recognise. Better to degrade to the Bukkit
            // scheduler and let it complain loudly than to disable moderation.
            plugin.getLogger().warning("Folia detected but its schedulers could not be resolved ("
                    + error.getMessage() + "); falling back to the Bukkit scheduler.");
            globalExecute = null;
        }
    }

    /** Off the server thread. Safe to block on I/O. */
    public void runAsync(Runnable task) {
        if (folia && asyncRunNow != null) {
            try {
                Object scheduler = asyncScheduler.invoke(null);
                asyncRunNow.invoke(scheduler, plugin, (Consumer<Object>) ignored -> task.run());
                return;
            } catch (ReflectiveOperationException ignored) {
                // fall through
            }
        }
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, task);
    }

    /**
     * Work that belongs to no particular entity or region -- config reloads,
     * counters, console output.
     */
    public void runGlobal(Runnable task) {
        if (folia && globalExecute != null) {
            try {
                globalExecute.invoke(globalRegionScheduler.invoke(null), plugin, task);
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
        if (folia && entityExecute != null) {
            try {
                Object scheduler = entityGetScheduler.invoke(entity);
                entityExecute.invoke(scheduler, plugin, task, null, 1L);
                return;
            } catch (ReflectiveOperationException ignored) {
                // fall through
            }
        }
        plugin.getServer().getScheduler().runTask(plugin, task);
    }
}
