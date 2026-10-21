package com.neomechanical.neomoderation.platform.fakefolia;

import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Folia's schedulers as {@code PlatformScheduler} finds them by reflection: static getters, an async scheduler whose
 * repeating task is a public interface implemented by a class that is not public, and an entity scheduler that refuses
 * a task for an entity that already left. In a package of their own, as Folia's are, so that class stays out of reach.
 */
public final class FakeFolia {

    public static final GlobalScheduler GLOBAL = new GlobalScheduler();
    public static final AsyncScheduler ASYNC = new AsyncScheduler();

    private FakeFolia() {
    }

    public static GlobalScheduler getGlobalRegionScheduler() {
        return GLOBAL;
    }

    public static AsyncScheduler getAsyncScheduler() {
        return ASYNC;
    }

    public interface ScheduledTask {
        void cancel();

        boolean isCancelled();
    }

    public static final class GlobalScheduler {
        public void execute(Plugin plugin, Runnable task) {
            task.run();
        }
    }

    public static final class AsyncScheduler {
        public ScheduledTask last;

        public void runNow(Plugin plugin, Consumer<Object> task) {
            task.accept(null);
        }

        public ScheduledTask runAtFixedRate(Plugin plugin, Consumer<Object> task, long delay, long period, TimeUnit unit) {
            last = new HiddenTask();
            return last;
        }
    }

    /** Like Folia's own task class: not public, so only its interface's methods can be called from outside. */
    static final class HiddenTask implements ScheduledTask {
        private boolean cancelled;

        @Override
        public void cancel() {
            cancelled = true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }
    }

    /** An entity as Folia has it: with a scheduler of its own. */
    public interface FoliaEntity extends Entity {
        EntityScheduler getScheduler();
    }

    public static final class EntityScheduler {
        private final boolean entityStillThere;

        public EntityScheduler(boolean entityStillThere) {
            this.entityStillThere = entityStillThere;
        }

        /** False, running neither callback, once the entity is retired: Folia's {@code EntityScheduler.execute}. */
        public boolean execute(Plugin plugin, Runnable task, Runnable retired, long delay) {
            if (!entityStillThere) {
                return false;
            }
            task.run();
            return true;
        }
    }
}
