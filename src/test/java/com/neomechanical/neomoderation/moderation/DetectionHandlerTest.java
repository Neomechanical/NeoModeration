package com.neomechanical.neomoderation.moderation;

import com.neomechanical.neomoderation.NeoModerationPlugin;
import com.neomechanical.neomoderation.config.ModerationAction;
import com.neomechanical.neomoderation.testing.TestPlugin;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class DetectionHandlerTest {
    private final ChatModerationActionExecutor executor = mock(ChatModerationActionExecutor.class);
    private final DetectionNotifier notifier = mock(DetectionNotifier.class);

    private DetectionHandler handler() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("moderation.enabled", true);
        config.set("moderation.actions", List.of(Map.of("type", "mute", "durationSeconds", 600)));
        NeoModerationPlugin plugin = TestPlugin.with(config);
        return new DetectionHandler(plugin, executor, plugin.monitorStats(), notifier, new StrikeService());
    }

    @SuppressWarnings("unchecked")
    private List<ModerationAction> actionsRun(Player player) {
        ArgumentCaptor<List<ModerationAction>> actions = ArgumentCaptor.forClass(List.class);
        verify(executor).execute(eq(player), actions.capture());
        return actions.getValue();
    }

    @Test
    void abuseRunsTheServersActions() {
        Player player = TestPlugin.player("Steve", "en_us");

        handler().handle(player, "chat", "blocked_word:badword", "badword", DetectionHandler.Disposition.BLOCK);

        assertEquals(List.of("MUTE"), actionsRun(player).stream().map(action -> action.type().name()).toList());
    }

    @Test
    void spamIsOnlyBlockedSoAFloodOfGgNeverMutesAnyone() {
        Player player = TestPlugin.player("Steve", "en_us");

        DetectionHandler.Disposition done = handler().handle(player, "chat", "spam:duplicate", "gg",
                DetectionHandler.Disposition.BLOCK);

        assertEquals(DetectionHandler.Disposition.BLOCK, done);
        assertTrue(actionsRun(player).isEmpty());
        verify(notifier).notifyDetection(any(), any());
    }

    @Test
    void spamNeverClimbsTheStrikeLadder() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("moderation.enabled", true);
        config.set("moderation.strikes.enabled", true);
        config.set("moderation.strikes.escalation", List.of(Map.of("atStrikes", 2, "type", "kick")));
        NeoModerationPlugin plugin = TestPlugin.with(config);
        StrikeService strikes = new StrikeService();
        DetectionHandler handler = new DetectionHandler(plugin, executor, plugin.monitorStats(), notifier, strikes);
        Player player = TestPlugin.player("Steve", "en_us");

        for (int i = 0; i < 5; i++) {
            handler.handle(player, "chat", "spam:duplicate", "gg", DetectionHandler.Disposition.BLOCK);
        }

        assertEquals(0, strikes.tracked(), "spam earns no strike");
        org.mockito.Mockito.verify(executor, org.mockito.Mockito.never())
                .execute(eq(player), org.mockito.ArgumentMatchers.argThat(actions -> !actions.isEmpty()));
    }

    @Test
    void aMapHeldOnlyBecauseTheCloudCouldNotCheckItNeverEarnsAStrike() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("moderation.enabled", true);
        config.set("moderation.strikes.enabled", true);
        config.set("moderation.strikes.escalation", List.of(Map.of("atStrikes", 2, "type", "kick")));
        NeoModerationPlugin plugin = TestPlugin.with(config);
        StrikeService strikes = new StrikeService();
        DetectionHandler handler = new DetectionHandler(plugin, executor, plugin.monitorStats(), notifier, strikes);
        Player player = TestPlugin.player("Steve", "en_us");

        for (int i = 0; i < 5; i++) {
            handler.handle(player, "map_art", "map_art:unchecked", "#7", DetectionHandler.Disposition.BLOCK,
                    DetectionHandler.Source.CLOUD);
        }

        assertEquals(0, strikes.tracked(), "nobody judged the map");
        org.mockito.Mockito.verify(executor, org.mockito.Mockito.never())
                .execute(eq(player), org.mockito.ArgumentMatchers.argThat(actions -> !actions.isEmpty()));
    }
}
