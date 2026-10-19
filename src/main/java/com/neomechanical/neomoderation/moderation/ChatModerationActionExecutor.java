package com.neomechanical.neomoderation.moderation;

import com.neomechanical.neomoderation.config.ModerationAction;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.regex.Pattern;

public final class ChatModerationActionExecutor {
    /**
     * Names a console command can safely carry. The server refuses anything else at
     * login unless a proxy turned that check off, and a name with a space in it
     * would add arguments to the command.
     */
    private static final Pattern COMMAND_SAFE_NAME = Pattern.compile("[^\\s§]{1,32}");
    private static final String DEFAULT_TIMEOUT_COMMAND = "tempmute %PLAYER% %DURATION%s %REASON%";
    private static final String DEFAULT_GIVE_ROLE_COMMAND = "lp user %PLAYER% parent add %ROLE%";
    private static final String DEFAULT_TAKE_ROLE_COMMAND = "lp user %PLAYER% parent remove %ROLE%";
    private static final String DEFAULT_TEMP_ROLE_COMMAND = "lp user %PLAYER% parent addtemp %ROLE% %DURATION%s";

    private final String sourceName;
    private final PlayerMuteService muteService;

    public ChatModerationActionExecutor(String sourceName, PlayerMuteService muteService) {
        this.sourceName = sourceName;
        this.muteService = muteService;
    }

    public void execute(Player player, List<ModerationAction> actions) {
        for (ModerationAction action : actions) {
            executeAction(player, action);
        }
    }

    private void executeAction(Player player, ModerationAction action) {
        switch (action.type()) {
            case CLEAR_CHAT -> clearChat();
            case KICK -> player.kickPlayer(action.reason());
            case BAN -> banPlayer(player, action.reason());
            case MUTE -> muteService.mute(player.getUniqueId(), action.durationSeconds());
            case TIMEOUT, GIVE_ROLE, TAKE_ROLE, TEMP_ROLE, COMMAND -> {
                String command = commandFor(action);
                if (!command.isBlank()) {
                    runCommand(player, action, command);
                }
            }
        }
    }

    /**
     * The console command an action runs, placeholders not yet filled: its own
     * command, else the default for its type. Empty for actions that run none.
     */
    public static String commandFor(ModerationAction action) {
        return switch (action.type()) {
            case TIMEOUT -> fallbackOr(action.command(), DEFAULT_TIMEOUT_COMMAND);
            case GIVE_ROLE -> fallbackOr(action.command(), DEFAULT_GIVE_ROLE_COMMAND);
            case TAKE_ROLE -> fallbackOr(action.command(), DEFAULT_TAKE_ROLE_COMMAND);
            case TEMP_ROLE -> fallbackOr(action.command(), DEFAULT_TEMP_ROLE_COMMAND);
            case COMMAND -> action.command() == null ? "" : action.command().trim();
            case CLEAR_CHAT, MUTE, KICK, BAN -> "";
        };
    }

    private void clearChat() {
        // Per-player messages avoid flooding the server console/log the way broadcastMessage does.
        for (Player online : Bukkit.getOnlinePlayers()) {
            for (int i = 0; i < 20; i++) {
                online.sendMessage(" ");
            }
        }
    }

    private void banPlayer(Player player, String reason) {
        Bukkit.getBanList(BanList.Type.NAME).addBan(player.getName(), reason, null, sourceName);
        player.kickPlayer(reason);
    }

    private void runCommand(Player player, ModerationAction action, String command) {
        if (!COMMAND_SAFE_NAME.matcher(player.getName()).matches()) {
            return;
        }
        ConsoleCommandSender console = Bukkit.getServer().getConsoleSender();
        String hydratedCommand = hydrate(command, player, action);
        Bukkit.dispatchCommand(console, hydratedCommand.startsWith("/") ? hydratedCommand.substring(1) : hydratedCommand);
    }

    private String hydrate(String command, Player player, ModerationAction action) {
        return command
                .replace("%PLAYER%", player.getName())
                .replace("%UUID%", player.getUniqueId().toString())
                .replace("%ROLE%", action.role())
                .replace("%DURATION%", String.valueOf(action.durationSeconds()))
                .replace("%REASON%", action.reason());
    }

    private static String fallbackOr(String maybeEmpty, String fallback) {
        return maybeEmpty == null || maybeEmpty.trim().isEmpty() ? fallback : maybeEmpty;
    }
}
