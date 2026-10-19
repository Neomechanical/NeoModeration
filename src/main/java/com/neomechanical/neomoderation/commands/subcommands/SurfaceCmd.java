package com.neomechanical.neomoderation.commands.subcommands;

import com.neomechanical.neomoderation.NeoModerationPlugin;
import com.neomechanical.neomoderation.commands.SubCommand;
import com.neomechanical.neomoderation.config.SurfaceSettings;
import com.neomechanical.neomoderation.messages.MenuRenderer;
import com.neomechanical.neomoderation.messages.MessageService;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Where local rules apply beyond chat: signs, books, anvil renames and message
 * commands. Bare {@code /nmod surface} shows one row per surface with a
 * clickable button for each setting, the current one highlighted.
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
    public List<String> getAliases() {
        return List.of("surfaces");
    }

    @Override
    public void execute(CommandSender sender, String label, String[] args) {
        MessageService messages = plugin.messages();
        if (args.length < 2) {
            showSurfaces(sender, label);
            return;
        }
        String target = args[1].trim().toLowerCase(Locale.ROOT);
        String mode = args.length == 3 ? args[2].trim().toLowerCase(Locale.ROOT) : "";
        boolean validTarget = "all".equals(target) || SurfaceSettings.SURFACE_NAMES.contains(target);
        if (args.length != 3 || !validTarget || !SurfaceSettings.SurfaceMode.isValid(mode)) {
            messages.send(sender, "surface.usage", Map.of("label", label));
            return;
        }
        for (String surface : "all".equals(target) ? SurfaceSettings.SURFACE_NAMES : List.of(target)) {
            plugin.getConfig().set(SURFACES_PREFIX + surface, mode);
        }
        plugin.saveAndReload();
        messages.send(sender, "surface.updated", Map.of(
                "surface", messages.text(sender, "surface.name." + target),
                "mode", messages.text(sender, "surface.mode." + mode)));
        if (sender instanceof Player) {
            showSurfaces(sender, label);
        }
    }

    private void showSurfaces(CommandSender sender, String label) {
        MessageService messages = plugin.messages();
        SurfaceSettings surfaces = plugin.settings().surfaces();
        List<MenuRenderer.Line> lines = new ArrayList<>();
        lines.add(MenuRenderer.text(messages.text(sender, "surface.title")));
        lines.add(MenuRenderer.text(messages.text(sender,
                plugin.settings().scanAsyncChat() ? "surface.chat-row" : "surface.chat-row-off")));
        for (String surface : SurfaceSettings.SURFACE_NAMES) {
            String current = surfaces.modeFor(surface).name().toLowerCase(Locale.ROOT);
            lines.add(row(sender, label, surface, "surface.row", current));
        }
        lines.add(row(sender, label, "all", "surface.all-row", null));
        lines.add(MenuRenderer.text(messages.format(sender, "surface.commands",
                Map.of("commands", String.join(", ", surfaces.scannedCommands())))));
        messages.sendMenu(sender, lines);
    }

    private MenuRenderer.Line row(CommandSender sender, String label, String surface, String rowKey, String current) {
        MessageService messages = plugin.messages();
        String name = messages.text(sender, "surface.name." + surface);
        List<MenuRenderer.Button> parts = new ArrayList<>();
        parts.add(new MenuRenderer.Button(messages.format(sender, rowKey, Map.of("surface", name)), "", "",
                MenuRenderer.ClickAction.NONE));
        for (String mode : SurfaceSettings.MODE_NAMES) {
            Map<String, String> values = Map.of("surface", name, "mode", messages.text(sender, "surface.mode." + mode));
            parts.add(new MenuRenderer.Button(
                    messages.format(sender, mode.equals(current) ? "surface.button-current" : "surface.button", values),
                    "/" + label + " surface " + surface + " " + mode,
                    messages.format(sender, "surface.button-hover", values),
                    MenuRenderer.ClickAction.RUN));
        }
        return MenuRenderer.buttons(parts);
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
