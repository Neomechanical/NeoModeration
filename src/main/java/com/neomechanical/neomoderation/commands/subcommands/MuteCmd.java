package com.neomechanical.neomoderation.commands.subcommands;

import com.neomechanical.neomoderation.NeoModerationPlugin;
import com.neomechanical.neomoderation.Permissions;
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
    public String getPermission() {
        return Permissions.MODERATE;
    }

    @Override
    public void execute(CommandSender sender, String label, String[] args) {
        MessageService messages = plugin.messages();
        if (args.length < 2 || (unmute && args.length > 2) || args.length > 3) {
            messages.send(sender, unmute ? "mute.unmute-usage" : "mute.usage", Map.of("label", label));
            return;
        }
        int seconds = DEFAULT_SECONDS;
        if (!unmute) {
            try {
                seconds = DurationParser.parseSeconds(args.length == 3 ? args[2] : null, DEFAULT_SECONDS);
            } catch (IllegalArgumentException e) {
                messages.send(sender, "action.bad-duration", Map.of("value", args[2]));
                return;
            }
        }
        String typed = args[1];
        int duration = seconds;
        OfflinePlayer known = findQuickly(typed);
        if (known != null) {
            apply(sender, typed, known, duration);
            return;
        }
        // Reading every player the server has stored can take a while, so it happens off the main thread.
        plugin.runAsync(() -> {
            OfflinePlayer stored = searchStored(typed);
            plugin.runSync(() -> apply(sender, typed, stored, duration));
        });
    }

    private void apply(CommandSender sender, String typed, OfflinePlayer target, int seconds) {
        MessageService messages = plugin.messages();
        if (target == null) {
            messages.send(sender, "error.player-unknown", Map.of("player", typed));
            return;
        }
        String name = target.getName() == null ? typed : target.getName();
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
        plugin.muteService().mute(target.getUniqueId(), seconds);
        String duration = DurationParser.format(seconds);
        messages.send(sender, "mute.done", Map.of("player", name, "duration", duration));
        tell(target, "mute.told", Map.of("duration", duration));
    }

    /** An online player, else one in Paper's name cache; never a web lookup. */
    private OfflinePlayer findQuickly(String name) {
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
            // Spigot: the stored players are searched instead.
        }
        return null;
    }

    private OfflinePlayer searchStored(String name) {
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
