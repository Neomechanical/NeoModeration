package com.neomechanical.neomoderation.commands.subcommands;

import com.neomechanical.neomoderation.NeoModerationPlugin;
import com.neomechanical.neomoderation.commands.DurationParser;
import com.neomechanical.neomoderation.commands.SubCommand;
import com.neomechanical.neomoderation.config.ConfigPaths;
import com.neomechanical.neomoderation.config.ModerationAction;
import com.neomechanical.neomoderation.config.ModerationActionType;
import com.neomechanical.neomoderation.messages.ActionText;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public class ActionCmd implements SubCommand {
    private final NeoModerationPlugin plugin;
    private static final int DEFAULT_MUTE_SECONDS = 300;

    public ActionCmd(NeoModerationPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getName() {
        return "action";
    }

    @Override
    public void execute(CommandSender sender, String label, String[] args) {
        if (args.length < 2) {
            plugin.messages().send(sender, "action.usage", Map.of("label", label));
            return;
        }

        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "list" -> listActions(sender);
            case "add" -> addAction(sender, label, args);
            case "remove" -> removeAction(sender, label, args);
            case "reset" -> resetActions(sender);
            default -> plugin.messages().send(sender, "action.usage", Map.of("label", label));
        }
    }

    private void listActions(CommandSender sender) {
        List<ModerationAction> actions = plugin.settings().actions();
        if (actions.isEmpty()) {
            plugin.messages().send(sender, "action.empty");
            return;
        }
        plugin.messages().send(sender, "action.list-title");
        int index = 1;
        for (ModerationAction action : actions) {
            plugin.messages().send(sender, "action.list-item", Map.of(
                    "index", String.valueOf(index++),
                    "value", ActionText.name(plugin.messages(), sender, action)
            ));
        }
    }

    /**
     * The action type named after {@code add} or {@code remove}, or null once the sender was told the usage
     * ({@code usageKey}) or that no such action exists.
     */
    private ModerationActionType typeArgument(CommandSender sender, String label, String[] args, String usageKey) {
        if (args.length < 3) {
            plugin.messages().send(sender, usageKey, Map.of("label", label));
            return null;
        }
        String kind = args[2].toLowerCase(Locale.ROOT);
        ModerationActionType type = parseSimpleActionType(kind);
        if (type == null) {
            plugin.messages().send(sender, "action.unknown", Map.of("value", kind));
        }
        return type;
    }

    private void addAction(CommandSender sender, String label, String[] args) {
        ModerationActionType type = typeArgument(sender, label, args, "action.add-usage");
        if (type == null) {
            return;
        }

        int durationSeconds = DEFAULT_MUTE_SECONDS;
        if (type == ModerationActionType.MUTE) {
            if (args.length > 4) {
                plugin.messages().send(sender, "action.add-usage", Map.of("label", label));
                return;
            }
            if (args.length == 4) {
                try {
                    durationSeconds = DurationParser.parseSeconds(args[3], DEFAULT_MUTE_SECONDS);
                } catch (IllegalArgumentException ex) {
                    plugin.messages().send(sender, "action.bad-duration", Map.of("value", args[3]));
                    return;
                }
            }
        } else if (args.length > 3) {
            plugin.messages().send(sender, "action.add-usage", Map.of("label", label));
            return;
        }

        // No reason is stored: each player reads the default one in their own language.
        Map<String, Object> actionMap = buildActionMap(type, durationSeconds);
        plugin.editConfig(config -> {
            List<Map<String, Object>> actions = actionMaps(config);
            actions.removeIf(entry -> isType(entry, type));
            actions.add(actionMap);
            config.set(ConfigPaths.ACTIONS, actions);
            return true;
        });
        plugin.messages().send(sender, "action.added", Map.of(
                "value", ActionText.name(plugin.messages(), sender, ModerationAction.from(actionMap))
        ));
    }

    private void removeAction(CommandSender sender, String label, String[] args) {
        ModerationActionType type = typeArgument(sender, label, args, "action.remove-usage");
        if (type == null) {
            return;
        }

        Optional<ModerationAction> existing = plugin.settings().actions().stream()
                .filter(action -> action.type() == type)
                .findFirst();
        if (existing.isEmpty()) {
            plugin.messages().send(sender, "action.missing", Map.of("value", args[2].toLowerCase(Locale.ROOT)));
            return;
        }
        plugin.editConfig(config -> {
            List<Map<String, Object>> actions = actionMaps(config);
            boolean removed = actions.removeIf(entry -> isType(entry, type));
            if (removed) {
                config.set(ConfigPaths.ACTIONS, actions);
            }
            return removed;
        });
        // Named as it was set, such as "mute for 30m", not as a fresh one would be.
        plugin.messages().send(sender, "action.removed",
                Map.of("value", ActionText.name(plugin.messages(), sender, existing.get())));
    }

    private void resetActions(CommandSender sender) {
        plugin.setConfig(ConfigPaths.ACTIONS, List.of());
        plugin.messages().send(sender, "action.reset");
    }

    private ModerationActionType parseSimpleActionType(String kind) {
        return switch (kind) {
            case "clear", "clearchat", "clear_chat" -> ModerationActionType.CLEAR_CHAT;
            case "mute" -> ModerationActionType.MUTE;
            case "kick" -> ModerationActionType.KICK;
            case "ban" -> ModerationActionType.BAN;
            default -> null;
        };
    }

    private Map<String, Object> buildActionMap(ModerationActionType type, int durationSeconds) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", type.name());
        if (type == ModerationActionType.MUTE) {
            map.put("durationSeconds", durationSeconds);
        }
        return map;
    }

    private static boolean isType(Map<String, Object> entry, ModerationActionType type) {
        return type.name().equalsIgnoreCase(String.valueOf(entry.get("type")));
    }

    private static List<Map<String, Object>> actionMaps(ConfigurationSection config) {
        List<Map<String, Object>> actions = new ArrayList<>();
        for (Map<?, ?> raw : config.getMapList(ConfigPaths.ACTIONS)) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                copy.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            actions.add(copy);
        }
        return actions;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, String[] args) {
        if (args.length == 2) {
            return SubCommand.filterPrefix(args[1], "list", "add", "remove", "reset");
        }
        if (args.length == 3 && ("add".equalsIgnoreCase(args[1]) || "remove".equalsIgnoreCase(args[1]))) {
            return SubCommand.filterPrefix(args[2], "clear", "mute", "kick", "ban");
        }
        if (args.length == 4 && "add".equalsIgnoreCase(args[1]) && "mute".equalsIgnoreCase(args[2])) {
            return SubCommand.filterPrefix(args[3], "30s", "1m", "5m", "10m", "1h");
        }
        return List.of();
    }
}
