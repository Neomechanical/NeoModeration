package com.neomechanical.neomoderation.platform;

import com.neomechanical.neomoderation.platform.fakefolia.FakeFolia;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlatformSchedulerTest {

    private final Plugin plugin = mock(Plugin.class);
    private final BukkitScheduler bukkit = mock(BukkitScheduler.class);
    private final List<String> warnings = new ArrayList<>();

    PlatformSchedulerTest() {
        Server server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(bukkit);
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                warnings.add(record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        when(plugin.getLogger()).thenReturn(logger);
    }

    private PlatformScheduler folia() {
        return new PlatformScheduler(plugin,
                PlatformScheduler.Folia.resolve(plugin, FakeFolia.class, FakeFolia.FoliaEntity.class));
    }

    private static FakeFolia.FoliaEntity entity(boolean stillThere) {
        FakeFolia.FoliaEntity entity = mock(FakeFolia.FoliaEntity.class);
        when(entity.getScheduler()).thenReturn(new FakeFolia.EntityScheduler(stillThere));
        return entity;
    }

    @Test
    void onFoliaTheRepeatingTaskIsCancelledThroughItsInterface() {
        // Folia's task class is not public: a cancel looked up on it could never be called.
        Runnable cancel = folia().runAsyncEvery(10, () -> { });
        assertFalse(FakeFolia.ASYNC.last.isCancelled());
        cancel.run();
        assertTrue(FakeFolia.ASYNC.last.isCancelled());
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void onFoliaAPlayerWhoLeftBeforeTheTaskWasScheduledGetsTheRetiredWork() {
        // Folia runs neither callback then, so a join notice claimed for that admin was never given back.
        List<String> ran = new ArrayList<>();
        folia().runForEntity(entity(false), () -> ran.add("task"), () -> ran.add("retired"));
        assertTrue(ran.equals(List.of("retired")), ran.toString());
    }

    @Test
    void onFoliaWorkForAPlayerStillOnlineRunsOnTheirScheduler() {
        List<String> ran = new ArrayList<>();
        folia().runForEntity(entity(true), () -> ran.add("task"), () -> ran.add("retired"));
        assertTrue(ran.equals(List.of("task")), ran.toString());
    }

    @Test
    void onSpigotAndPaperTheRepeatingTaskIsATimerItsHandleCancels() {
        BukkitTask timer = mock(BukkitTask.class);
        Runnable task = () -> { };
        when(bukkit.runTaskTimerAsynchronously(plugin, task, 12_000L, 12_000L)).thenReturn(timer);
        Runnable cancel = new PlatformScheduler(plugin, null).runAsyncEvery(10, task);
        cancel.run();
        verify(timer).cancel();
    }

    @Test
    void aSchedulerThatRefusesTheRepeatingTaskSaysSoInTheLog() {
        // The release matrix fails a server whose log has this line, so a timer that never runs cannot pass unseen.
        when(bukkit.runTaskTimerAsynchronously(any(Plugin.class), any(Runnable.class), anyLong(), anyLong()))
                .thenThrow(new UnsupportedOperationException("not on this server"));
        Runnable cancel = new PlatformScheduler(plugin, null).runAsyncEvery(10, () -> { });
        cancel.run();
        assertTrue(warnings.stream().anyMatch(line -> line.startsWith("Could not schedule a repeating task")), warnings.toString());
    }
}
