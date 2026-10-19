package com.neomechanical.neomoderation.commands.subcommands;

import com.neomechanical.neomoderation.NeoModerationPlugin;
import com.neomechanical.neomoderation.commands.SubCommand;
import com.neomechanical.neomoderation.config.ModerationSettings;
import com.neomechanical.neomoderation.messages.MessageService;
import com.neomechanical.neomoderation.moderation.DiscordAlerts;
import com.neomechanical.neomoderation.moderation.ModerationCoverage;
import org.bukkit.command.CommandSender;

import java.util.Map;

/** One screen on exactly what stays on the server, what leaves it, and where it goes. */
public class PrivacyCmd implements SubCommand {
    private final NeoModerationPlugin plugin;

    public PrivacyCmd(NeoModerationPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getName() {
        return "privacy";
    }

    @Override
    public void execute(CommandSender sender, String label, String[] args) {
        ModerationSettings settings = plugin.settings();
        ModerationCoverage coverage = ModerationCoverage.from(settings);
        MessageService messages = plugin.messages();

        messages.send(sender, "privacy.title");
        messages.send(sender, "privacy.local");
        if (coverage.hasCloudChecks()) {
            String data = coverage.cloudText()
                    ? (coverage.mapArt() ? "privacy.data-both" : "privacy.data-text")
                    : "privacy.data-maps";
            messages.send(sender, "privacy.cloud", Map.of(
                    "data", messages.text(sender, data), "endpoint", settings.api().endpoint()));
            messages.send(sender, "privacy.retention");
            messages.send(sender, "privacy.timeout", Map.of("policy",
                    messages.text(sender, settings.failOpen() ? "privacy.policy-open" : "privacy.policy-closed")));
        } else {
            messages.send(sender, settings.api().apiKey().isBlank() ? "privacy.no-cloud" : "privacy.cloud-off");
        }
        if (settings.cases().enabled()) {
            messages.send(sender, "privacy.cases", Map.of("preview", messages.text(sender,
                    settings.cases().storeContent() ? "privacy.cases-preview" : "privacy.cases-no-preview")));
        }
        if (DiscordAlerts.isWebhookUrl(settings.alerts().discordWebhookUrl())) {
            messages.send(sender, "privacy.discord", Map.of("message", messages.text(sender,
                    settings.alerts().includeMessage() ? "privacy.discord-message" : "privacy.discord-no-message")));
        }
        messages.send(sender, "privacy.metrics");
    }
}
