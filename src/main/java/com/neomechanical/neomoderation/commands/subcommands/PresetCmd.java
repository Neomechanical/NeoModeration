package com.neomechanical.neomoderation.commands.subcommands;

import com.neomechanical.neomoderation.NeoModerationPlugin;
import com.neomechanical.neomoderation.commands.SubCommand;
import com.neomechanical.neomoderation.config.ConfigPaths;
import com.neomechanical.neomoderation.config.ModerationCategorySettings;
import com.neomechanical.neomoderation.messages.MenuRenderer;
import com.neomechanical.neomoderation.messages.MessageService;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Cloud threshold presets. They do not change local rules, enforcement modes,
 * the API key, or an operator's punishment policy.
 */
public class PresetCmd implements SubCommand {
    private record Preset(String name, double threshold, Set<String> disabledCategories) {
    }

    private static final List<Preset> PRESETS = List.of(
            new Preset("family", 0.55D, Set.of()),
            new Preset("community", 0.7D, Set.of()),
            new Preset("minimal", 0.8D, Set.of("harassment", "scam", "spam")));

    private final NeoModerationPlugin plugin;

    public PresetCmd(NeoModerationPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getName() {
        return "preset";
    }

    @Override
    public void execute(CommandSender sender, String label, String[] args) {
        MessageService messages = plugin.messages();
        if (args.length < 2 || "list".equalsIgnoreCase(args[1])) {
            List<MenuRenderer.Line> lines = new ArrayList<>();
            lines.add(MenuRenderer.text(messages.format(sender, "preset.list-title", Map.of("label", label))));
            for (Preset preset : PRESETS) {
                Map<String, String> values = Map.of("name", preset.name(),
                        "summary", messages.text(sender, "preset." + preset.name()));
                lines.add(MenuRenderer.clickableLine(messages.format(sender, "preset.list-item", values),
                        "/" + label + " preset " + preset.name(), messages.format(sender, "preset.list-hover", values),
                        MenuRenderer.ClickAction.RUN));
            }
            messages.sendMenu(sender, lines);
            return;
        }
        String requested = args[1].toLowerCase(Locale.ROOT);
        Preset preset = PRESETS.stream().filter(candidate -> candidate.name().equals(requested)).findFirst().orElse(null);
        if (preset == null) {
            messages.send(sender, "preset.usage", Map.of("label", label));
            return;
        }
        plugin.editConfig(config -> {
            for (String category : ModerationCategorySettings.categoryKeys()) {
                config.set(ConfigPaths.CATEGORIES + category,
                        preset.disabledCategories().contains(category) ? Boolean.FALSE : preset.threshold());
            }
            return true;
        });
        messages.send(sender, "preset.applied", Map.of(
                "name", preset.name(),
                "categories", String.valueOf(plugin.settings().categories().enabledCount())));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, String[] args) {
        if (args.length == 2) {
            return SubCommand.filterPrefix(args[1], "community", "family", "list", "minimal");
        }
        return List.of();
    }
}
