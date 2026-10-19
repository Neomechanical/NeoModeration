package com.neomechanical.neomoderation.commands.subcommands;

import com.neomechanical.neomoderation.NeoModerationPlugin;
import com.neomechanical.neomoderation.commands.ModeSubCommand;
import com.neomechanical.neomoderation.config.ConfigPaths;
import org.bukkit.command.CommandSender;

import java.util.Map;

/**
 * Enforcement level for cloud decisions only.
 *
 * <p>Local rules and cloud decisions deserve different levels of trust: the
 * operator wrote the word list, but the cloud categories are a model's
 * judgement they have never seen. A new install therefore enforces local rules
 * and only alerts on cloud ones, and this is how the operator promotes the
 * cloud once they have watched it decide.
 */
public class CloudModeCmd extends ModeSubCommand {
    public CloudModeCmd(NeoModerationPlugin plugin) {
        super(plugin, ConfigPaths.CLOUD_MODE, "cloudmode");
    }

    @Override
    public String getName() {
        return "cloudmode";
    }

    @Override
    protected void showCurrent(CommandSender sender) {
        plugin.messages().send(sender, "cloudmode.current",
                Map.of("value", StatusCmd.modeLabel(plugin.messages(), sender, plugin.settings().cloudMode())));
    }
}
