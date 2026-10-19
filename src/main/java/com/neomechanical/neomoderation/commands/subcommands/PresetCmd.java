package com.neomechanical.neomoderation.commands.subcommands;

import com.neomechanical.neomoderation.NeoModerationPlugin;
import com.neomechanical.neomoderation.commands.SubCommand;
import com.neomechanical.neomoderation.config.ModerationAction;
import com.neomechanical.neomoderation.config.ModerationCategorySettings;
import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Cloud threshold presets. They do not change local rules, enforcement modes,
 * the API key, or an operator's punishment policy.
 */
public class PresetCmd implements SubCommand {
    private record Preset(String name, String summary, double defaultThreshold,
                          Set<String> disabledCategories) {
    }

    private static final List<Preset> PRESETS = List.of(
            new Preset(
                    "family",
                    "cloud: all categories at 0.55 (strict)",
                    0.55D,
                    Set.of()
            ),
            new Preset(
                    "community",
                    "cloud: all categories at 0.70",
                    0.7D,
                    Set.of()
            ),
            new Preset(
                    "minimal",
                    "cloud: 0.80, with harassment/scam/spam off",
                    0.8D,
                    Set.of("harassment", "scam", "spam")
            )
    );

    private final NeoModerationPlugin plugin;

    public PresetCmd(NeoModerationPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getName() {
        return "preset";
    }

    @Override
    public String getDescription() {
        return "Apply a cloud threshold preset (family, community, minimal).";
    }

    @Override
    public String getUsage() {
        return "/nmod preset <family|community|minimal>";
    }

    @Override
    public void execute(CommandSender sender, String label, String[] args) {
        if (args.length < 2 || "list".equalsIgnoreCase(args[1])) {
            plugin.messages().send(sender, "preset.list-title");
            if (sender instanceof org.bukkit.entity.Player) {
                java.util.List<com.neomechanical.neomoderation.messages.MenuRenderer.Line> lines = new java.util.ArrayList<>();
                for (Preset preset : PRESETS) {
                    String formatted = plugin.messages().format("preset.list-item", Map.of(
                            "name", preset.name(),
                            "summary", preset.summary()
                    ));
                    String cmd = "/" + label + " preset " + preset.name();
                    lines.add(com.neomechanical.neomoderation.messages.MenuRenderer.clickableLine(
                            formatted, cmd, "&b" + cmd + "\n&7" + preset.summary(),
                            com.neomechanical.neomoderation.messages.MenuRenderer.ClickAction.RUN));
                }
                plugin.messages().sendMenu(sender, lines);
                return;
            }
            for (Preset preset : PRESETS) {
                plugin.messages().send(sender, "preset.list-item", Map.of(
                        "name", preset.name(),
                        "summary", preset.summary()
                ));
            }
            return;
        }

        String requested = args[1].toLowerCase(Locale.ROOT);
        Preset preset = PRESETS.stream().filter(p -> p.name().equals(requested)).findFirst().orElse(null);
        if (preset == null) {
            plugin.messages().send(sender, "preset.usage", Map.of("label", label));
            return;
        }

        for (String category : ModerationCategorySettings.categoryKeys()) {
            Object value = preset.disabledCategories().contains(category)
                    ? Boolean.FALSE
                    : preset.defaultThreshold();
            plugin.getConfig().set("moderation.categories." + category, value);
        }
        plugin.saveAndReload();

        plugin.messages().send(sender, "preset.applied", Map.of(
                "name", preset.name(),
                "categories", String.valueOf(plugin.settings().categories().enabledCount()),
                "actions", ModerationAction.describe(plugin.settings().actions())
        ));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, String[] args) {
        if (args.length == 2) {
            return SubCommand.filterPrefix(args[1], "community", "family", "list", "minimal");
        }
        return List.of();
    }
}
