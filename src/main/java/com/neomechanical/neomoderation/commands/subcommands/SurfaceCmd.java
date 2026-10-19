package com.neomechanical.neomoderation.commands.subcommands;

import com.neomechanical.neomoderation.NeoModerationPlugin;
import com.neomechanical.neomoderation.commands.SubCommand;
import com.neomechanical.neomoderation.config.SurfaceSettings;
import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * In-game configuration for extra local moderation surfaces (signs, books,
 * anvil renames, and private-message commands).
 */
public class SurfaceCmd implements SubCommand {
    private static final String SURFACES_PREFIX = "moderation.surfaces.";

    private final NeoModerationPlugin plugin;

    public SurfaceCmd(NeoModerationPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getName() {
        return "surface";
    }

    @Override
    public String getDescription() {
        return "Configure extra local moderation surfaces.";
    }

    @Override
    public String getUsage() {
        return "/nmod surface <sign|book|anvil|command|all> <off|monitor|censor|block>";
    }

    @Override
    public List<String> getAliases() {
        return List.of("surfaces");
    }

    @Override
    public void execute(CommandSender sender, String label, String[] args) {
        if (args.length < 2) {
            showSurfaces(sender, label);
            return;
        }
        if (args.length != 3) {
            plugin.messages().send(sender, "surface.usage", Map.of("label", label));
            return;
        }

        String target = args[1].trim().toLowerCase(Locale.ROOT);
        String mode = args[2].trim().toLowerCase(Locale.ROOT);
        boolean validTarget = "all".equals(target) || SurfaceSettings.SURFACE_NAMES.contains(target);
        if (!validTarget || !SurfaceSettings.SurfaceMode.isValid(mode)) {
            plugin.messages().send(sender, "surface.usage", Map.of("label", label));
            return;
        }

        if ("all".equals(target)) {
            for (String surface : SurfaceSettings.SURFACE_NAMES) {
                plugin.getConfig().set(SURFACES_PREFIX + surface, mode);
            }
        } else {
            plugin.getConfig().set(SURFACES_PREFIX + target, mode);
        }
        plugin.saveAndReload();
        plugin.messages().send(sender, "surface.updated", Map.of(
                "surface", target,
                "mode", mode
        ));
    }

    private void showSurfaces(CommandSender sender, String label) {
        SurfaceSettings surfaces = plugin.settings().surfaces();
        plugin.messages().send(sender, "surface.title");
        if (sender instanceof org.bukkit.entity.Player) {
            java.util.List<com.neomechanical.neomoderation.messages.MenuRenderer.Line> lines = new java.util.ArrayList<>();
            for (String name : SurfaceSettings.SURFACE_NAMES) {
                String mode = surfaces.modeFor(name).name().toLowerCase(Locale.ROOT);
                String formatted = plugin.messages().format("surface.row", Map.of(
                        "surface", name,
                        "mode", mode
                ));
                String suggest = "/" + label + " surface " + name + " ";
                lines.add(com.neomechanical.neomoderation.messages.MenuRenderer.clickableLine(
                        formatted, suggest, "&b" + suggest + "<off|monitor|censor|block>",
                        com.neomechanical.neomoderation.messages.MenuRenderer.ClickAction.SUGGEST));
            }
            plugin.messages().sendMenu(sender, lines);
        } else {
            for (String name : SurfaceSettings.SURFACE_NAMES) {
                plugin.messages().send(sender, "surface.row", Map.of(
                        "surface", name,
                        "mode", surfaces.modeFor(name).name().toLowerCase(Locale.ROOT)
                ));
            }
        }
        plugin.messages().send(sender, "surface.commands", Map.of(
                "commands", String.join(", ", surfaces.scannedCommands())
        ));
        plugin.messages().send(sender, "surface.hint", Map.of("label", label));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, String[] args) {
        if (args.length == 2) {
            return SubCommand.filterPrefix(args[1], "sign", "book", "anvil", "command", "all");
        }
        if (args.length == 3) {
            return SubCommand.filterPrefix(args[2], "off", "monitor", "censor", "block");
        }
        return List.of();
    }
}
