package com.neomechanical.neomoderation.commands;

import com.neomechanical.neomoderation.NeoModerationPlugin;
import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Shared shape for the mode-switch commands ({@code mode}, {@code cloudmode}):
 * bare invocation shows the current level, one {@code monitor|enforce} argument
 * sets it. Subclasses only supply identity and the no-arg display.
 */
public abstract class ModeSubCommand implements SubCommand {
    protected final NeoModerationPlugin plugin;
    private final String configPath;
    private final String localePrefix;

    protected ModeSubCommand(NeoModerationPlugin plugin, String configPath, String localePrefix) {
        this.plugin = plugin;
        this.configPath = configPath;
        this.localePrefix = localePrefix;
    }

    @Override
    public final void execute(CommandSender sender, String label, String[] args) {
        if (args.length < 2) {
            showCurrent(sender);
            return;
        }
        String requested = args[1].toLowerCase(Locale.ROOT);
        if (!"monitor".equals(requested) && !"enforce".equals(requested)) {
            plugin.messages().send(sender, localePrefix + ".usage", Map.of("label", label));
            return;
        }
        plugin.setConfig(configPath, requested);
        plugin.messages().send(sender, localePrefix + ("monitor".equals(requested) ? ".set-monitor" : ".set-enforce"));
    }

    /** Bare-invocation display; each mode command shows what its operators need. */
    protected abstract void showCurrent(CommandSender sender);

    @Override
    public final List<String> onTabComplete(CommandSender sender, String[] args) {
        if (args.length == 2) {
            return SubCommand.filterPrefix(args[1], "monitor", "enforce");
        }
        return List.of();
    }
}
