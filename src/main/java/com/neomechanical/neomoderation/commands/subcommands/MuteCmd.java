package com.neomechanical.neomoderation.commands.subcommands;

import com.neomechanical.neomoderation.NeoModerationPlugin;
import com.neomechanical.neomoderation.commands.DurationParser;
import com.neomechanical.neomoderation.commands.SubCommand;
import com.neomechanical.neomoderation.messages.MessageService;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;

/** /nmod mute and /nmod unmute: NeoModeration's own chat mute, by hand. */
public class MuteCmd implements SubCommand {
    private static final int DEFAULT_SECONDS = 600;

    private final NeoModerationPlugin plugin;
    private final boolean unmute;

    public MuteCmd(NeoModerationPlugin plugin, boolean unmute) {
        this.plugin = plugin;
        this.unmute = unmute;
    }

    @Override
    public String getName() {
        return unmute ? "unmute" : "mute";
    }

    @Override
    public void execute(CommandSender sender, String label, String[] args) {
        MessageService messages = plugin.messages();
        if (args.length < 2 || (unmute && args.length > 2) || args.length > 3) {
            messages.send(sender, unmute ? "mute.unmute-usage" : "mute.usage", Map.of("label", label));
            return;
        }
        OfflinePlayer target = find(args[1]);
        if (target == null) {
            messages.send(sender, "error.player-unknown", Map.of("player", args[1]));
            return;
        }
        String name = target.getName() == null ? args[1] : target.getName();
        if (unmute) {
            if (!plugin.muteService().isMuted(target.getUniqueId())) {
                messages.send(sender, "mute.not-muted", Map.of("player", name));
                return;
            }
            plugin.muteService().unmute(target.getUniqueId());
            messages.send(sender, "mute.unmuted", Map.of("player", name));
            tell(target, "mute.told-unmuted", Map.of());
            return;
        }
        int seconds;
        try {
            seconds = DurationParser.parseSeconds(args.length == 3 ? args[2] : null, DEFAULT_SECONDS);
        } catch (IllegalArgumentException e) {
            messages.send(sender, "action.bad-duration", Map.of("value", args[2]));
            return;
        }
        plugin.muteService().mute(target.getUniqueId(), seconds);
        String duration = DurationParser.format(seconds);
        messages.send(sender, "mute.done", Map.of("player", name, "duration", duration));
        tell(target, "mute.told", Map.of("duration", duration));
    }

    /** An online player, else one the server has seen, without a web lookup. */
    private OfflinePlayer find(String name) {
        Player online = plugin.getServer().getPlayerExact(name);
        if (online != null) {
            return online;
        }
        try {
            // Paper keeps a name cache; Spigot does not have this method.
            Object cached = plugin.getServer().getClass().getMethod("getOfflinePlayerIfCached", String.class)
                    .invoke(plugin.getServer(), name);
            if (cached instanceof OfflinePlayer player) {
                return player;
            }
        } catch (ReflectiveOperationException ignored) {
            // Fall back to the players this server has stored.
        }
        for (OfflinePlayer known : plugin.getServer().getOfflinePlayers()) {
            if (name.equalsIgnoreCase(known.getName())) {
                return known;
            }
        }
        return null;
    }

    private void tell(OfflinePlayer target, String key, Map<String, String> values) {
        if (target instanceof Player player && player.isOnline()) {
            plugin.runForEntity(player, () -> plugin.messages().send(player, key, values));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, String[] args) {
        if (args.length == 2) {
            return SubCommand.filterPrefix(args[1],
                    plugin.getServer().getOnlinePlayers().stream().map(Player::getName).toArray(String[]::new));
        }
        if (args.length == 3 && !unmute) {
            return SubCommand.filterPrefix(args[2], "5m", "10m", "30m", "1h", "1d");
        }
        return List.of();
    }
}
