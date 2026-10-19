package com.neomechanical.neomoderation.commands.subcommands;

import com.neomechanical.neomoderation.NeoModerationPlugin;
import com.neomechanical.neomoderation.commands.SubCommand;
import com.neomechanical.neomoderation.config.ConfigPaths;
import org.bukkit.command.CommandSender;

import java.util.List;

public class DisableCmd implements SubCommand {

    private final NeoModerationPlugin plugin;

    public DisableCmd(NeoModerationPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getName() {
        return "off";
    }

    @Override
    public List<String> getAliases() {
        return List.of("disable");
    }

    @Override
    public void execute(CommandSender sender, String label, String[] args) {
        plugin.setConfig(ConfigPaths.ENABLED, false);
        plugin.messages().send(sender, "toggle.off");
    }
}
