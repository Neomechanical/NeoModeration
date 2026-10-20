package com.neomechanical.neomoderation.commands.subcommands;

import com.neomechanical.neomoderation.NeoModerationPlugin;
import com.neomechanical.neomoderation.commands.RuleListEditor;
import com.neomechanical.neomoderation.commands.SubCommand;
import org.bukkit.command.CommandSender;

import java.util.List;

public class UrlCmd implements SubCommand {
    private final NeoModerationPlugin plugin;

    public UrlCmd(NeoModerationPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getName() {
        return "url";
    }

    @Override
    public void execute(CommandSender sender, String label, String[] args) {
        RuleListEditor.handle(plugin, sender, label, args, 1, RuleListEditor.RuleList.BANNED_URLS);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, String[] args) {
        if (args.length == 2) {
            return SubCommand.filterPrefix(args[1], "add", "remove", "list");
        }
        return List.of();
    }
}
