package com.neomechanical.neomoderation.listener;

import com.neomechanical.neomoderation.NeoModerationPlugin;
import com.neomechanical.neomoderation.config.BukkitConfigView;
import com.neomechanical.neomoderation.config.ModerationSettings;
import com.neomechanical.neomoderation.messages.MessageService;
import com.neomechanical.neomoderation.moderation.DetectionHandler;
import com.neomechanical.neomoderation.moderation.PlayerMuteService;
import com.neomechanical.neomoderation.moderation.SpamDetector;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
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

    @Test
    void aMutedPlayerCannotCarryOnThroughMessageCommands() {
        NeoModerationPlugin plugin = pluginWith("off");
        Player player = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(id);
        when(plugin.muteService().isMuted(id)).thenReturn(true);
        when(plugin.muteService().remainingSeconds(id)).thenReturn(600);

        PlayerCommandPreprocessEvent whisper = command(player, "/msg Notch hi there");
        PlayerCommandPreprocessEvent spawn = command(player, "/spawn");
        SurfaceModerationListener listener = new SurfaceModerationListener(plugin, mock(DetectionHandler.class));
        listener.onCommand(whisper);
        listener.onCommand(spawn);

        verify(whisper).setCancelled(true);
        verify(plugin.messages()).send(player, "mute.blocked", Map.of("duration", "10m"));
        verify(spawn, never()).setCancelled(true);
    }

    @Test
    void aMuteCoversTheUsualAliasesOfTheMessageCommands() {
        NeoModerationPlugin plugin = pluginWith("off");
        Player player = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(id);
        when(plugin.muteService().isMuted(id)).thenReturn(true);
        SurfaceModerationListener listener = new SurfaceModerationListener(plugin, mock(DetectionHandler.class));

        for (String text : List.of("/m Notch hi", "/t Notch hi", "/pm Notch hi", "/tm hello team", "/teammsg hi",
                "/mail send Notch hi", "/essentials:msg Notch hi", "/er hi", "/whisper Notch hi")) {
            PlayerCommandPreprocessEvent event = command(player, text);
            listener.onCommand(event);
            verify(event).setCancelled(true);
        }
        PlayerCommandPreprocessEvent home = command(player, "/home");
        listener.onCommand(home);
        verify(home, never()).setCancelled(true);
    }

    @Test
    void aMuteFollowsAnyAliasTheServerKnowsForAMessageCommand() {
        NeoModerationPlugin plugin = pluginWith("off");
        Server server = mock(Server.class, withSettings().extraInterfaces(CommandMapHolder.class));
        CommandMap map = mock(CommandMap.class);
        when(((CommandMapHolder) server).getCommandMap()).thenReturn(map);
        Command msg = mock(Command.class);
        when(msg.getName()).thenReturn("msg");
        Command warp = mock(Command.class);
        when(warp.getName()).thenReturn("warp");
        when(map.getCommand("whisp")).thenReturn(msg);
        when(map.getCommand("warp")).thenReturn(warp);
        when(plugin.getServer()).thenReturn(server);
        Player player = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(id);
        when(plugin.muteService().isMuted(id)).thenReturn(true);
        SurfaceModerationListener listener = new SurfaceModerationListener(plugin, mock(DetectionHandler.class));

        PlayerCommandPreprocessEvent whisp = command(player, "/whisp Notch hi");
        PlayerCommandPreprocessEvent warpTo = command(player, "/warp shop");
        listener.onCommand(whisp);
        listener.onCommand(warpTo);

        verify(whisp).setCancelled(true);
        verify(warpTo, never()).setCancelled(true);
    }

    /** {@code Server#getCommandMap()}, which the 1.8 API the plugin compiles against does not have. */
    public interface CommandMapHolder {
        CommandMap getCommandMap();
    }

    private static PlayerCommandPreprocessEvent command(Player player, String text) {
        PlayerCommandPreprocessEvent event = mock(PlayerCommandPreprocessEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getMessage()).thenReturn(text);
        return event;
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
        when(plugin.muteService()).thenReturn(mock(PlayerMuteService.class));
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
