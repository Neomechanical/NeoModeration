package com.neomechanical.neomoderation.commands;

import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.Locale;

public interface SubCommand {
    /** The subcommand word; its help text is {@code help.usage.<name>} and {@code help.desc.<name>}. */
    String getName();

    void execute(CommandSender sender, String label, String[] args);

    default String getPermission() {
        return "neomoderation.admin";
    }

    default List<String> getAliases() {
        return List.of();
    }

    default List<String> onTabComplete(CommandSender sender, String[] args) {
        return List.of();
    }

    /** Case-insensitive prefix filter for tab completions. */
    static List<String> filterPrefix(String typed, String... options) {
        String prefix = typed.toLowerCase(Locale.ROOT);
        return List.of(options).stream().filter(option -> option.startsWith(prefix)).toList();
    }
}
