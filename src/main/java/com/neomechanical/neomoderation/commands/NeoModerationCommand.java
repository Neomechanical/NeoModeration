package com.neomechanical.neomoderation.commands;

import com.neomechanical.neomoderation.NeoModerationPlugin;
import com.neomechanical.neomoderation.Permissions;
import com.neomechanical.neomoderation.commands.subcommands.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class NeoModerationCommand implements CommandExecutor, TabCompleter {
    private final NeoModerationPlugin plugin;
    private final Map<String, SubCommand> subCommands = new LinkedHashMap<>();

    public NeoModerationCommand(NeoModerationPlugin plugin) {
        this.plugin = plugin;
        registerSubCommands();
    }

    private void registerSubCommands() {
        registerCommand(new SetupCmd(plugin));
        registerCommand(new TrialCmd(plugin));
        registerCommand(new EnableCmd(plugin));
        registerCommand(new DisableCmd(plugin));
        registerCommand(new KeyCmd(plugin));
        registerCommand(new StatusCmd(plugin));
        registerCommand(new ReloadCmd(plugin));
        registerCommand(new WordCmd(plugin));
        registerCommand(new UrlCmd(plugin));
        registerCommand(new ActionCmd(plugin));
        registerCommand(new SurfaceCmd(plugin));
        registerCommand(new UsageCmd(plugin));
        registerCommand(new ModeCmd(plugin));
        registerCommand(new CloudModeCmd(plugin));
        registerCommand(new AllowCmd(plugin));
        registerCommand(new TestCmd(plugin));
        registerCommand(new DoctorCmd(plugin));
        registerCommand(new PresetCmd(plugin));
        registerCommand(new PrivacyCmd(plugin));
        registerCommand(new CasesCmd(plugin));
        registerCommand(new MuteCmd(plugin, false));
        registerCommand(new MuteCmd(plugin, true));
        registerCommand(new DiscordCmd(plugin));

        // Help command needs reference to other commands
        registerCommand(new HelpCmd(plugin, subCommands));
    }

    private void registerCommand(SubCommand command) {
        subCommands.put(command.getName().toLowerCase(Locale.ROOT), command);
        for (String alias : command.getAliases()) {
            subCommands.put(alias.toLowerCase(Locale.ROOT), command);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!mayUse(sender)) {
            plugin.messages().send(sender, "error.no-permission");
            return true;
        }

        if (args.length == 0) {
            subCommands.get("help").execute(sender, label, args);
            return true;
        }

        String subCommandName = args[0].toLowerCase(Locale.ROOT);
        SubCommand subCommand = subCommands.get(subCommandName);

        if (subCommand == null) {
            if (!subCommandName.matches("\\d+")) {
                // "/nmod 2" is a help page; anything else is a typo worth naming.
                plugin.messages().send(sender, "error.unknown-command", Map.of("command", args[0], "label", label));
            }
            subCommands.get("help").execute(sender, label, args);
            return true;
        }

        if (!sender.hasPermission(subCommand.getPermission())) {
            // Only moderators get here: the command they tried is one of the admins'.
            if (sender.hasPermission(Permissions.ADMIN)) {
                plugin.messages().send(sender, "error.no-permission");
            } else {
                plugin.messages().send(sender, "error.admin-only", Map.of("command", subCommandName, "label", label));
            }
            return true;
        }

        subCommand.execute(sender, label, args);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!mayUse(sender)) {
            return List.of();
        }

        if (args.length == 1) {
            List<String> completions = new ArrayList<>();
            String prefix = args[0].toLowerCase(Locale.ROOT);
            for (String cmd : subCommands.keySet()) {
                if (cmd.startsWith(prefix) && sender.hasPermission(subCommands.get(cmd).getPermission())) {
                    completions.add(cmd);
                }
            }
            return completions.stream().sorted().distinct().toList();
        }

        String subCommandName = args[0].toLowerCase(Locale.ROOT);
        SubCommand subCommand = subCommands.get(subCommandName);
        if (subCommand != null && sender.hasPermission(subCommand.getPermission())) {
            return subCommand.onTabComplete(sender, args);
        }

        return List.of();
    }

    /** Admins and moderators may use /nmod; each subcommand then checks its own permission. */
    private static boolean mayUse(CommandSender sender) {
        return sender.hasPermission(Permissions.ADMIN) || sender.hasPermission(Permissions.MODERATE);
    }
}
