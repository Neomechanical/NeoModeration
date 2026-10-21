package com.neomechanical.neomoderation.commands.subcommands;

import com.neomechanical.neomoderation.NeoModerationPlugin;
import com.neomechanical.neomoderation.commands.RuleListEditor;
import com.neomechanical.neomoderation.commands.SubCommand;
import org.bukkit.command.CommandSender;

import java.util.List;

public class WordCmd implements SubCommand {
    private final NeoModerationPlugin plugin;

    public WordCmd(NeoModerationPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getName() {
        return "word";
    }

    @Override
    public void execute(CommandSender sender, String label, String[] args) {
        RuleListEditor.handle(plugin, sender, label, args, 1, RuleListEditor.RuleList.BANNED_WORDS);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, String[] args) {
        if (args.length == 2) {
            return RuleListEditor.completeAction(args[1]);
        }
        return List.of();
    }
}
