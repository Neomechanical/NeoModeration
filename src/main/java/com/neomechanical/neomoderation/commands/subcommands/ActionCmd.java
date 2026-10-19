package com.neomechanical.neomoderation.commands.subcommands;

import com.neomechanical.neomoderation.NeoModerationPlugin;
import com.neomechanical.neomoderation.commands.DurationParser;
import com.neomechanical.neomoderation.commands.SubCommand;
import com.neomechanical.neomoderation.config.ModerationAction;
import com.neomechanical.neomoderation.config.ModerationActionType;
import com.neomechanical.neomoderation.messages.ActionText;
import com.neomechanical.neomoderation.messages.MessageService;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ActionCmd implements SubCommand {
    private final NeoModerationPlugin plugin;
    private static final String ACTIONS_PATH = "moderation.actions";
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

    private void addAction(CommandSender sender, String label, String[] args) {
        if (args.length < 3) {
            plugin.messages().send(sender, "action.add-usage", Map.of("label", label));
            return;
        }

        String kind = args[2].toLowerCase(Locale.ROOT);
        ModerationActionType type = parseSimpleActionType(kind);
        if (type == null) {
            plugin.messages().send(sender, "action.unknown", Map.of("value", kind));
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

        List<Map<String, Object>> actions = loadActionMaps();
        actions.removeIf(entry -> type.name().equalsIgnoreCase(String.valueOf(entry.get("type"))));
        Map<String, Object> actionMap = buildActionMap(type, durationSeconds,
                MessageService.plain(plugin.messages().text(sender, "action.default-reason")));
        actions.add(actionMap);
        saveActions(actions);
        plugin.messages().send(sender, "action.added", Map.of(
                "value", ActionText.name(plugin.messages(), sender, ModerationAction.from(actionMap))
        ));
    }

    private void removeAction(CommandSender sender, String label, String[] args) {
        if (args.length < 3) {
            plugin.messages().send(sender, "action.remove-usage", Map.of("label", label));
            return;
        }
        String kind = args[2].toLowerCase(Locale.ROOT);
        ModerationActionType type = parseSimpleActionType(kind);
        if (type == null) {
            plugin.messages().send(sender, "action.unknown", Map.of("value", kind));
            return;
        }

        List<Map<String, Object>> actions = loadActionMaps();
        boolean removed = actions.removeIf(entry -> type.name().equalsIgnoreCase(String.valueOf(entry.get("type"))));
        String name = ActionText.name(plugin.messages(), sender,
                new ModerationAction(type, "", "", DEFAULT_MUTE_SECONDS, ""));
        if (!removed) {
            plugin.messages().send(sender, "action.missing", Map.of("value", name));
            return;
        }
        saveActions(actions);
        plugin.messages().send(sender, "action.removed", Map.of("value", name));
    }

    private void resetActions(CommandSender sender) {
        saveActions(List.of());
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

    private Map<String, Object> buildActionMap(ModerationActionType type, int durationSeconds, String reason) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", type.name());
        if (type == ModerationActionType.MUTE) {
            map.put("durationSeconds", durationSeconds);
        }
        if (type == ModerationActionType.MUTE || type == ModerationActionType.KICK || type == ModerationActionType.BAN) {
            map.put("reason", reason);
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> loadActionMaps() {
        List<Map<String, Object>> actions = new ArrayList<>();
        for (Map<?, ?> raw : plugin.getConfig().getMapList(ACTIONS_PATH)) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                copy.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            actions.add(copy);
        }
        return actions;
    }

    private void saveActions(List<Map<String, Object>> actions) {
        plugin.getConfig().set(ACTIONS_PATH, actions);
        plugin.saveAndReload();
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
