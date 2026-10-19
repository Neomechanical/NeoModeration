package com.neomechanical.neomoderation.commands.subcommands;

import com.neomechanical.neomoderation.NeoModerationPlugin;
import com.neomechanical.neomoderation.commands.InputLimits;
import com.neomechanical.neomoderation.commands.SubCommand;
import com.neomechanical.neomoderation.moderation.CloudRecovery;
import org.bukkit.command.CommandSender;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class SetupCmd implements SubCommand {
    private static final String KEY_PATH = "moderation.api.apiKey";
    private static final String ENABLED_PATH = "moderation.enabled";

    private final NeoModerationPlugin plugin;

    public SetupCmd(NeoModerationPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getName() {
        return "setup";
    }

    @Override
    public List<String> getAliases() {
        return List.of("cloud");
    }

    @Override
    public void execute(CommandSender sender, String label, String[] args) {
        if (args.length < 2) {
            plugin.messages().send(sender, "setup.usage", Map.of(
                    "label", label,
                    "url", CloudRecovery.SIGNUP_URL
            ));
            return;
        }
        if (args.length == 2 && "trial".equalsIgnoreCase(args[1])) {
            new TrialCmd(plugin).execute(sender, label, args);
            return;
        }
        String apiKey = String.join(" ", Arrays.copyOfRange(args, 1, args.length)).trim();
        if (apiKey.isEmpty()) {
            plugin.messages().send(sender, "setup.usage", Map.of(
                    "label", label,
                    "url", CloudRecovery.SIGNUP_URL
            ));
            return;
        }
        if (!InputLimits.isApiKeyLengthValid(apiKey)) {
            plugin.messages().send(sender, "error.api-key-too-long", Map.of(
                    "max", String.valueOf(InputLimits.MAX_API_KEY_LENGTH)
            ));
            return;
        }
        plugin.getConfig().set(ENABLED_PATH, true);
        plugin.getConfig().set(KEY_PATH, apiKey);
        plugin.saveAndReload();
        plugin.messages().send(sender, "setup.done");
    }
}
