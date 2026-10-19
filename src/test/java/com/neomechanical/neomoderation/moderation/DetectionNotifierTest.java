package com.neomechanical.neomoderation.moderation;

import com.neomechanical.neomoderation.NeoModerationPlugin;
import com.neomechanical.neomoderation.config.ModerationAction;
import com.neomechanical.neomoderation.config.ModerationActionType;
import com.neomechanical.neomoderation.config.ModerationSettings;
import com.neomechanical.neomoderation.messages.MessageService;
import com.neomechanical.neomoderation.testing.TestPlugin;
import org.bukkit.Server;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DetectionNotifierTest {
    private static final String WEBHOOK = "https://discord.com/api/webhooks/1/abc";

    @TempDir
    Path folder;

    private final Logger logger = mock(Logger.class);
    private final DiscordAlerts discord = mock(DiscordAlerts.class);
    private final Player germanStaff = TestPlugin.player("Hans", "de_de");
    private final Player regular = TestPlugin.player("Bob", "en_us");

    private NeoModerationPlugin plugin(YamlConfiguration config) {
        NeoModerationPlugin plugin = TestPlugin.with(config);
        MessageService messages = TestPlugin.loaded(folder.toFile(), "en_US", true);
        when(plugin.messages()).thenReturn(messages);
        when(plugin.getLogger()).thenReturn(logger);
        when(germanStaff.hasPermission(DetectionNotifier.NOTIFY_PERMISSION)).thenReturn(true);
        Server server = mock(Server.class);
        when(server.getConsoleSender()).thenReturn(mock(ConsoleCommandSender.class));
        doReturn(List.of(germanStaff, regular)).when(server).getOnlinePlayers();
        when(plugin.getServer()).thenReturn(server);
        return plugin;
    }

    private static DetectionNotifier.Detection blocked(List<ModerationAction> actions) {
        return new DetectionNotifier.Detection("Steve", "chat", "blocked_word:badword", "this is a badword",
                new Outcome(Outcome.Verdict.BLOCKED, actions, 0));
    }

    @Test
    void theConsoleGetsOnePlainLineAndEachStaffMemberAnAlertInTheirLanguage() {
        NeoModerationPlugin plugin = plugin(new YamlConfiguration());

        new DetectionNotifier(plugin, discord).notifyDetection(blocked(List.of()), plugin.settings());

        verify(logger).info("Blocked Steve in chat: this is a badword (banned word \"badword\")");
        assertEquals(List.of("[NeoMod] Blockiert Steve im Chat: this is a badword (verbotenes Wort „badword“)"),
                TestPlugin.lines(germanStaff));
        assertTrue(TestPlugin.lines(regular).isEmpty(), "only neomoderation.notify reads alerts");
        verifyNoInteractions(discord);
    }

    @Test
    void hiddenMessagesStayHiddenAndActionsAreNamed() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("moderation.alerts.includeMessage", false);
        NeoModerationPlugin plugin = plugin(config);

        new DetectionNotifier(plugin, discord).notifyDetection(
                blocked(List.of(new ModerationAction(ModerationActionType.MUTE, "", "", 600, ""))), plugin.settings());

        verify(logger).info("Blocked Steve in chat: message hidden (banned word \"badword\") then mute for 10m");
    }

    @Test
    void aFramedMapSaysWhereItWasAndWhoFoundIt() {
        NeoModerationPlugin plugin = plugin(new YamlConfiguration());

        new DetectionNotifier(plugin, discord).notifyDetection(new DetectionNotifier.Detection("Alex", "map_frame",
                "map_art:sexual", "#7 @ 10 64 -20", new Outcome(Outcome.Verdict.BLOCKED, List.of(), 0)),
                plugin.settings());

        verify(logger).info("Blocked a framed map at #7 @ 10 64 -20 (map art: sexual content), found by Alex");
    }

    @Test
    void discordGetsTheAlertInTheServersLanguage() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("moderation.alerts.discordWebhookUrl", WEBHOOK);
        NeoModerationPlugin plugin = plugin(config);
        ModerationSettings settings = plugin.settings();

        new DetectionNotifier(plugin, discord).notifyDetection(blocked(List.of()), settings);

        ArgumentCaptor<DiscordAlerts.Alert> alert = ArgumentCaptor.forClass(DiscordAlerts.Alert.class);
        verify(discord).submit(eq(WEBHOOK), alert.capture(), eq("and {count} more in the last minute"));
        assertEquals("Blocked", alert.getValue().title());
        assertEquals(Map.of("Player", "Steve", "Where", "chat", "Why", "banned word \"badword\"",
                "Message", "`this is a badword`", "Then", ""), alert.getValue().fields());
    }

    @Test
    void alertsOffStillLogsAndStillPostsToDiscord() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("moderation.alerts.enabled", false);
        config.set("moderation.alerts.discordWebhookUrl", WEBHOOK);
        NeoModerationPlugin plugin = plugin(config);

        new DetectionNotifier(plugin, discord).notifyDetection(blocked(List.of()), plugin.settings());

        verify(logger).info(anyString());
        assertTrue(TestPlugin.lines(germanStaff).isEmpty());
        verify(discord).submit(eq(WEBHOOK), any(), anyString());
    }
}
