package com.neomechanical.neomoderation.commands;

import com.neomechanical.neomoderation.NeoModerationPlugin;
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
        if (!sender.hasPermission("neomoderation.admin")) {
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
            subCommands.get("help").execute(sender, label, args);
            return true;
        }

        if (!sender.hasPermission(subCommand.getPermission())) {
            plugin.messages().send(sender, "error.no-permission");
            return true;
        }

        subCommand.execute(sender, label, args);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("neomoderation.admin")) {
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
}
