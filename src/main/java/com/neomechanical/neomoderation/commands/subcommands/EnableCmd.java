package com.neomechanical.neomoderation.commands.subcommands;

import com.neomechanical.neomoderation.NeoModerationPlugin;
import com.neomechanical.neomoderation.commands.SubCommand;
import org.bukkit.command.CommandSender;

import java.util.List;

public class EnableCmd implements SubCommand {
    private static final String ENABLED_PATH = "moderation.enabled";

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
        plugin.getConfig().set(ENABLED_PATH, true);
        plugin.saveAndReload();
        plugin.messages().send(sender, "toggle.on");
    }
}
