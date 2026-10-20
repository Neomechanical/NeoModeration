package com.neomechanical.neomoderation.moderation;

import com.neomechanical.neomoderation.config.ModerationAction;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatModerationActionExecutorTest {
    @Test
    void usesBuiltInMuteAndDispatchesAdvancedCommands() {
        Player player = mockPlayer();
        Server server = mock(Server.class);
        ConsoleCommandSender console = mock(ConsoleCommandSender.class);
        when(server.getConsoleSender()).thenReturn(console);
        PlayerMuteService muteService = new PlayerMuteService((java.io.File) null);

        try (MockedStatic<Bukkit> bukkit = Mockito.mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getServer).thenReturn(server);
            bukkit.when(() -> Bukkit.dispatchCommand(any(ConsoleCommandSender.class), any(String.class))).thenReturn(true);

            new ChatModerationActionExecutor("NeoModeration", muteService, name -> { }).execute(player, List.of(
                    action("MUTE", Map.of("durationSeconds", 30, "reason", "bad chat")),
                    action("TIMEOUT", Map.of("durationSeconds", 60, "reason", "timeout")),
                    action("GIVE_ROLE", Map.of("role", "trusted")),
                    action("TAKE_ROLE", Map.of("role", "member")),
                    action("TEMP_ROLE", Map.of("role", "probation", "durationSeconds", 90)),
                    action("COMMAND", Map.of("command", "/warn %PLAYER% %UUID% %REASON%", "reason", "custom reason"))
            ));

            assertTrue(muteService.isMuted(player.getUniqueId()));
            bukkit.verify(() -> Bukkit.dispatchCommand(console, "tempmute TestPlayer 60s timeout"));
            bukkit.verify(() -> Bukkit.dispatchCommand(console, "lp user TestPlayer parent add trusted"));
            bukkit.verify(() -> Bukkit.dispatchCommand(console, "lp user TestPlayer parent remove member"));
            bukkit.verify(() -> Bukkit.dispatchCommand(console, "lp user TestPlayer parent addtemp probation 90s"));
            bukkit.verify(() -> Bukkit.dispatchCommand(console, "warn TestPlayer 00000000-0000-0000-0000-000000000123 custom reason"));
        }
    }

    @Test
    void aNameWithASpaceNeverReachesAConsoleCommand() {
        Player player = mockPlayer();
        when(player.getName()).thenReturn("Steve op Steve");
        Server server = mock(Server.class);
        when(server.getConsoleSender()).thenReturn(mock(ConsoleCommandSender.class));

        try (MockedStatic<Bukkit> bukkit = Mockito.mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getServer).thenReturn(server);

            List<String> skipped = new java.util.ArrayList<>();
            new ChatModerationActionExecutor("NeoModeration", new PlayerMuteService((java.io.File) null), skipped::add)
                    .execute(player, List.of(action("COMMAND", Map.of("command", "warn %PLAYER%"))));

            bukkit.verify(() -> Bukkit.dispatchCommand(any(), any()), Mockito.never());
            assertTrue(skipped.contains("Steve op Steve"), "the console is told the action was skipped");
        }
    }

    @Test
    void executesKickBanAndClearChatActions() {
        Player player = mockPlayer();
        BanList banList = mock(BanList.class);
        PlayerMuteService muteService = new PlayerMuteService((java.io.File) null);

        try (MockedStatic<Bukkit> bukkit = Mockito.mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getBanList(BanList.Type.NAME)).thenReturn(banList);
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(player));

            new ChatModerationActionExecutor("NeoModeration", muteService, name -> { }).execute(player, List.of(
                    action("CLEAR_CHAT", Map.of()),
                    action("KICK", Map.of("reason", "kick reason")),
                    action("BAN", Map.of("reason", "ban reason"))
            ));

            verify(player, times(20)).sendMessage(" ");
            verify(player).kickPlayer("kick reason");
            verify(banList).addBan(eq("TestPlayer"), eq("ban reason"), eq(null), eq("NeoModeration"));
            verify(player).kickPlayer("ban reason");
        }
    }

    private Player mockPlayer() {
        Player player = mock(Player.class);
        when(player.getName()).thenReturn("TestPlayer");
        when(player.getUniqueId()).thenReturn(UUID.fromString("00000000-0000-0000-0000-000000000123"));
        return player;
    }

    private ModerationAction action(String type, Map<String, Object> extras) {
        HashMap<String, Object> raw = new HashMap<>(extras);
        raw.put("type", type);
        return ModerationAction.from(raw);
    }
}
