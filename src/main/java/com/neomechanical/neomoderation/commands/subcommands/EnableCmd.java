package com.neomechanical.neomoderation.commands.subcommands;

import com.neomechanical.neomoderation.NeoModerationPlugin;
import com.neomechanical.neomoderation.commands.SubCommand;
import com.neomechanical.neomoderation.config.ConfigPaths;
import org.bukkit.command.CommandSender;

import java.util.List;

public class EnableCmd implements SubCommand {

    private final NeoModerationPlugin plugin;

    public EnableCmd(NeoModerationPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getName() {
        return "on";
    }

    @Override
    public List<String> getAliases() {
        return List.of("enable");
    }

    @Override
    public void execute(CommandSender sender, String label, String[] args) {
        plugin.setConfig(ConfigPaths.ENABLED, true);
        plugin.messages().send(sender, "toggle.on");
    }
}
