package com.neomechanical.neomoderation.commands.subcommands;

import com.neomechanical.neomoderation.NeoModerationPlugin;
import com.neomechanical.neomoderation.commands.InputLimits;
import com.neomechanical.neomoderation.commands.SubCommand;
import org.bukkit.command.CommandSender;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class KeyCmd implements SubCommand {
    private static final String KEY_PATH = "moderation.api.apiKey";

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
            plugin.getConfig().set(KEY_PATH, "");
            plugin.saveAndReload();
            plugin.messages().send(sender, "key.cleared");
            return;
        }

        String apiKey;
        if ("set".equalsIgnoreCase(args[1])) {
            if (args.length < 3) {
                plugin.messages().send(sender, "key.usage", Map.of("label", label));
                return;
            }
            apiKey = String.join(" ", Arrays.copyOfRange(args, 2, args.length)).trim();
        } else {
            apiKey = String.join(" ", Arrays.copyOfRange(args, 1, args.length)).trim();
        }
        if (apiKey.isEmpty()) {
            plugin.messages().send(sender, "key.usage", Map.of("label", label));
            return;
        }
        if (!InputLimits.isApiKeyLengthValid(apiKey)) {
            plugin.messages().send(sender, "error.api-key-too-long", Map.of(
                    "max", String.valueOf(InputLimits.MAX_API_KEY_LENGTH)
            ));
            return;
        }
        plugin.getConfig().set(KEY_PATH, apiKey);
        plugin.saveAndReload();
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
