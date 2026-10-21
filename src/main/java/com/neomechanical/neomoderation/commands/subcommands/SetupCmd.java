package com.neomechanical.neomoderation.commands.subcommands;

import com.neomechanical.neomoderation.NeoModerationPlugin;
import com.neomechanical.neomoderation.commands.ApiKeyArgument;
import com.neomechanical.neomoderation.commands.SubCommand;
import com.neomechanical.neomoderation.config.ConfigPaths;
import com.neomechanical.neomoderation.moderation.CloudRecovery;
import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.Map;

public class SetupCmd implements SubCommand {

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
        String apiKey = ApiKeyArgument.read(plugin.messages(), sender, args, 1, () -> plugin.messages().send(sender,
                "setup.usage", Map.of("label", label, "url", CloudRecovery.SIGNUP_URL)));
        if (apiKey == null) {
            return;
        }
        plugin.editConfig(config -> {
            config.set(ConfigPaths.ENABLED, true);
            config.set(ConfigPaths.API_KEY, apiKey);
            return true;
        });
        plugin.messages().send(sender, "setup.done");
    }
}
