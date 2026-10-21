package com.neomechanical.neomoderation.commands.subcommands;

import com.neomechanical.neomoderation.NeoModerationPlugin;
import com.neomechanical.neomoderation.commands.RuleListEditor;
import com.neomechanical.neomoderation.commands.SubCommand;
import org.bukkit.command.CommandSender;

import java.util.List;

/** {@code /nmod word} and {@code /nmod url}: a banned list, named by its own one-word command. */
public class BannedListCmd implements SubCommand {
    private final NeoModerationPlugin plugin;
    private final RuleListEditor.RuleList list;

    public BannedListCmd(NeoModerationPlugin plugin, RuleListEditor.RuleList list) {
        this.plugin = plugin;
        this.list = list;
    }

    @Override
    public String getName() {
        return list.command();
    }

    @Override
    public void execute(CommandSender sender, String label, String[] args) {
        RuleListEditor.handle(plugin, sender, label, args, 1, list);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, String[] args) {
        if (args.length == 2) {
            return RuleListEditor.completeAction(args[1]);
        }
        return List.of();
    }
}
