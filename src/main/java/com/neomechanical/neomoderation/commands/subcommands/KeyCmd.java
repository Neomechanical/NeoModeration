package com.neomechanical.neomoderation.commands.subcommands;

import com.neomechanical.neomoderation.NeoModerationPlugin;
import com.neomechanical.neomoderation.commands.ApiKeyArgument;
import com.neomechanical.neomoderation.commands.SubCommand;
import com.neomechanical.neomoderation.config.ConfigPaths;
import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.Map;

public class KeyCmd implements SubCommand {

    private final NeoModerationPlugin plugin;

    public KeyCmd(NeoModerationPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getName() {
        return "key";
    }

    @Override
    public void execute(CommandSender sender, String label, String[] args) {
        if (args.length < 2) {
            plugin.messages().send(sender, "key.usage", Map.of("label", label));
            return;
        }
        if ("clear".equalsIgnoreCase(args[1])) {
            plugin.setConfig(ConfigPaths.API_KEY, "");
            plugin.messages().send(sender, "key.cleared");
            return;
        }

        String apiKey = ApiKeyArgument.read(plugin.messages(), sender, args, "set".equalsIgnoreCase(args[1]) ? 2 : 1,
                () -> plugin.messages().send(sender, "key.usage", Map.of("label", label)));
        if (apiKey == null) {
            return;
        }
        plugin.setConfig(ConfigPaths.API_KEY, apiKey);
        plugin.messages().send(sender, "key.saved");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, String[] args) {
        if (args.length == 2) {
            return SubCommand.filterPrefix(args[1], "set", "clear");
        }
        return List.of();
    }
}
