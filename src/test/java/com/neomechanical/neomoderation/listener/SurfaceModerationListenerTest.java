package com.neomechanical.neomoderation.listener;

import com.neomechanical.neomoderation.NeoModerationPlugin;
import com.neomechanical.neomoderation.config.BukkitConfigView;
import com.neomechanical.neomoderation.config.ModerationSettings;
import com.neomechanical.neomoderation.messages.MessageService;
import com.neomechanical.neomoderation.moderation.DetectionHandler;
import com.neomechanical.neomoderation.moderation.SpamDetector;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SurfaceModerationListenerTest {
    @Test
    void scansBukkitNamespacedPrivateMessages() {
        checkBlocked("/minecraft:msg Someone badword", "block", "blocked_word:badword");
    }

    @Test
    void censorModeBlocksAnObfuscatedLinkItCannotMask() {
        checkBlocked("/msg Someone grabify[.]link", "censor", "blocked_url:grabify.link");
    }

    @Test
    void theRecipientOfAPrivateMessageIsNotCheckedAsTheSendersWords() {
        Player recipient = mock(Player.class);
        PlayerCommandPreprocessEvent event = run("/msg badword see you at spawn", "block", recipient);

        verify(event, never()).setCancelled(true);
    }

    @Test
    void aBannedWordAfterTheRecipientIsStillBlocked() {
        Player recipient = mock(Player.class);
        PlayerCommandPreprocessEvent event = run("/msg Notch you badword", "block", recipient);

        verify(event).setCancelled(true);
    }

    private PlayerCommandPreprocessEvent run(String text, String mode, Player onlineRecipient) {
        PlayerCommandPreprocessEvent event = mock(PlayerCommandPreprocessEvent.class);
        DetectionHandler handler = mock(DetectionHandler.class);
        when(handler.handle(any(), anyString(), anyString(), anyString(), any()))
                .thenAnswer(call -> call.getArgument(4));
        NeoModerationPlugin plugin = pluginWith(mode);
        Server server = plugin.getServer();
        String recipientName = text.split(" ")[1];
        when(server.getPlayerExact(recipientName)).thenReturn(onlineRecipient);
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(event.getPlayer()).thenReturn(player);
        when(event.getMessage()).thenReturn(text);
        new SurfaceModerationListener(plugin, handler).onCommand(event);
        return event;
    }

    private static NeoModerationPlugin pluginWith(String mode) {
        YamlConfiguration config = new YamlConfiguration();
        config.set("moderation.enabled", true);
        config.set("moderation.offline.enabled", true);
        config.set("moderation.surfaces.command", mode);
        config.set("moderation.surfaces.scannedCommands", List.of("msg"));
        config.set("moderation.offline.bannedWords", List.of("badword"));
        config.set("moderation.offline.bannedUrls", List.of("grabify.link"));
        config.set("moderation.spam.enabled", false);
        NeoModerationPlugin plugin = mock(NeoModerationPlugin.class);
        when(plugin.settings()).thenReturn(ModerationSettings.from(new BukkitConfigView(config)));
        when(plugin.spamDetector()).thenReturn(new SpamDetector());
        when(plugin.messages()).thenReturn(mock(MessageService.class));
        when(plugin.getServer()).thenReturn(mock(Server.class));
        return plugin;
    }

    private void checkBlocked(String text, String mode, String reason) {
        NeoModerationPlugin plugin = pluginWith(mode);
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        PlayerCommandPreprocessEvent event = mock(PlayerCommandPreprocessEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getMessage()).thenReturn(text);
        DetectionHandler handler = mock(DetectionHandler.class);
        when(handler.handle(any(), anyString(), anyString(), anyString(), any()))
                .thenAnswer(call -> call.getArgument(4));

        new SurfaceModerationListener(plugin, handler).onCommand(event);

        verify(handler).handle(eq(player), eq("command"), eq(reason), anyString(), eq(DetectionHandler.Disposition.BLOCK));
        verify(event).setCancelled(true);
    }
}
