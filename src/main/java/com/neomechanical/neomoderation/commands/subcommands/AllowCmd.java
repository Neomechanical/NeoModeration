package com.neomechanical.neomoderation.commands.subcommands;

import com.neomechanical.neomoderation.NeoModerationPlugin;
import com.neomechanical.neomoderation.commands.RuleListEditor;
import com.neomechanical.neomoderation.commands.SubCommand;
import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.Locale;
import java.util.Map;

public class AllowCmd implements SubCommand {
    private final NeoModerationPlugin plugin;

    public AllowCmd(NeoModerationPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getName() {
        return "allow";
    }

    @Override
    public void execute(CommandSender sender, String label, String[] args) {
        if (args.length < 2) {
            plugin.messages().send(sender, "allow.usage", Map.of("label", label));
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "word" -> RuleListEditor.handle(plugin, sender, label, args, 2, RuleListEditor.RuleList.ALLOWED_WORDS);
            case "url" -> RuleListEditor.handle(plugin, sender, label, args, 2, RuleListEditor.RuleList.ALLOWED_URLS);
            default -> plugin.messages().send(sender, "allow.usage", Map.of("label", label));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, String[] args) {
        if (args.length == 2) {
            return SubCommand.filterPrefix(args[1], "word", "url");
        }
        if (args.length == 3) {
            return SubCommand.filterPrefix(args[2], "add", "remove", "list");
        }
        return List.of();
    }
}
