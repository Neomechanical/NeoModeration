package com.neomechanical.neomoderation.commands.subcommands;

import com.neomechanical.neomoderation.NeoModerationPlugin;
import com.neomechanical.neomoderation.commands.ModeSubCommand;
import com.neomechanical.neomoderation.messages.MessageService;
import org.bukkit.command.CommandSender;

import java.time.Duration;
import java.util.Map;

/** /nmod mode: whether local rules block or only alert staff, and what they caught since startup. */
public class ModeCmd extends ModeSubCommand {
    public ModeCmd(NeoModerationPlugin plugin) {
        super(plugin, "moderation.mode", "mode");
    }

    @Override
    public String getName() {
        return "mode";
    }

    @Override
    protected void showCurrent(CommandSender sender) {
        MessageService messages = plugin.messages();
        messages.send(sender, "mode.current",
                Map.of("value", StatusCmd.modeLabel(messages, sender, plugin.settings().mode())));
        long hours = Math.max(0, Duration.between(plugin.monitorStats().since(), java.time.Instant.now()).toHours());
        messages.send(sender, "mode.stats", Map.of(
                "total", String.valueOf(plugin.monitorStats().total()), "hours", String.valueOf(hours)));
        for (Map.Entry<String, Long> entry : plugin.monitorStats().byReason().entrySet()) {
            messages.send(sender, "mode.stats-line", Map.of(
                    "count", String.valueOf(entry.getValue()),
                    "what", messages.text(sender, "stat." + entry.getKey())));
        }
    }
}
