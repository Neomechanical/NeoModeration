package com.neomechanical.neomoderation.commands.subcommands;

import com.neomechanical.neomoderation.NeoModerationPlugin;
import com.neomechanical.neomoderation.commands.SubCommand;
import com.neomechanical.neomoderation.moderation.CloudRecovery;
import com.neomechanical.neomoderation.moderation.ModerationApiResult;
import com.neomechanical.neomoderation.moderation.NeoMechanicalUsageClient;
import com.neomechanical.neomoderation.moderation.UsageSummary;
import org.bukkit.command.CommandSender;

import java.util.Map;

public class UsageCmd implements SubCommand {
    private final NeoModerationPlugin plugin;
    private final NeoMechanicalUsageClient usageClient = new NeoMechanicalUsageClient();

    public UsageCmd(NeoModerationPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getName() {
        return "usage";
    }

    @Override
    public void execute(CommandSender sender, String label, String[] args) {
        if (plugin.settings().api().apiKey().isBlank()) {
            plugin.messages().send(sender, "error.no-api-key", Map.of(
                    "url", CloudRecovery.SIGNUP_URL
            ));
            return;
        }

        plugin.messages().send(sender, "usage.fetching");
        plugin.runAsync(() -> {
            try {
                UsageSummary summary = usageClient.fetchUsage(plugin.settings().api());
                plugin.runSync(() -> render(sender, summary));
            } catch (NeoMechanicalUsageClient.UsageException e) {
                plugin.runSync(() -> plugin.messages().send(sender, usageErrorKey(e.kind()), Map.of(
                        "keysUrl", CloudRecovery.API_KEYS_URL,
                        "billingUrl", CloudRecovery.BILLING_URL
                )));
            }
        });
    }

    private void render(CommandSender sender, UsageSummary s) {
        plugin.messages().send(sender, "usage.title");
        plugin.messages().send(sender, "usage.workspace", Map.of("value", s.workspace()));
        plugin.messages().send(sender, "usage.plan", Map.of(
                "value", s.tier(),
                "rpm", String.valueOf(s.requestsPerMinuteLimit())
        ));
        if (s.creditsRemaining() > 0) {
            plugin.messages().send(sender, "usage.credits", Map.of(
                    "value", String.valueOf(s.creditsRemaining())
            ));
        } else {
            plugin.messages().send(sender, "usage.credits-empty", Map.of(
                    "url", CloudRecovery.BILLING_URL
            ));
        }
        plugin.messages().send(sender, "usage.requests", Map.of(
                "today", String.valueOf(s.requestsToday()),
                "week", String.valueOf(s.requestsLast7Days())
        ));
        if (s.nsfwImagesCap() > 0) {
            plugin.messages().send(sender, "usage.nsfw", Map.of(
                    "images", s.nsfwImagesRemaining() + "/" + s.nsfwImagesCap()));
        }
    }

    static String usageErrorKey(ModerationApiResult.Kind kind) {
        return switch (kind) {
            case CLIENT_AUTH -> "usage.error-auth";
            case INSUFFICIENT_CREDITS -> "usage.error-credits";
            case CLIENT_REQUEST -> "usage.error-request";
            case TRANSIENT_TRANSPORT, OVERLOADED, FLAGGED, CLEAR -> "usage.error";
        };
    }
}
