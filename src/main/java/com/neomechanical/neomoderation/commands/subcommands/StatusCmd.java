package com.neomechanical.neomoderation.commands.subcommands;

import com.neomechanical.neomoderation.NeoModerationPlugin;
import com.neomechanical.neomoderation.commands.SubCommand;
import com.neomechanical.neomoderation.config.ModerationAction;
import com.neomechanical.neomoderation.config.ModerationMode;
import com.neomechanical.neomoderation.config.ModerationSettings;
import com.neomechanical.neomoderation.moderation.CloudRecovery;
import com.neomechanical.neomoderation.moderation.ModerationApiResult;
import com.neomechanical.neomoderation.moderation.ModerationCoverage;
import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.Map;

public class StatusCmd implements SubCommand {
    private final NeoModerationPlugin plugin;

    public StatusCmd(NeoModerationPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getName() {
        return "status";
    }

    @Override
    public String getDescription() {
        return "View moderation settings and status.";
    }

    @Override
    public String getUsage() {
        return "/nmod status";
    }

    @Override
    public List<String> getAliases() {
        return List.of("info");
    }

    @Override
    public void execute(CommandSender sender, String label, String[] args) {
        ModerationSettings settings = plugin.settings();
        ModerationCoverage coverage = ModerationCoverage.from(settings);
        boolean hasKey = !settings.api().apiKey().isBlank();
        boolean monitor = settings.mode() == ModerationMode.MONITOR;
        plugin.messages().sendDashboard(sender,
                plugin.getDescription().getVersion(),
                monitor ? "MONITOR" : "ENFORCE",
                ModerationCoverage.cloudDashboardLabel(settings),
                plugin.monitorStats().total());
        plugin.messages().send(sender, "status.enabled", Map.of(
                "value", settings.enabled() ? "ON" : "OFF"
        ));
        plugin.messages().send(sender, "status.mode", Map.of(
                "value", !coverage.hasLocalChecks() ? settings.mode().name() + " (no local checks armed)"
                        : monitor ? "MONITOR (local detections alert only)"
                        : !coverage.localEnforces() ? "ENFORCE (armed surface checks monitor only)"
                        : "ENFORCE (local detections act)"
        ));
        plugin.messages().send(sender, "status.cloud", Map.of(
                "value", !hasKey ? (coverage.hasLocalChecks()
                        ? "local only (no API key)" : "no checks armed (no API key)")
                        : !coverage.hasCloudChecks() ? "key saved, but no checks armed"
                        : "text " + (coverage.cloudText() ? "on" : "off")
                                + ", map art " + (coverage.mapArt() ? "on" : "off")
                                + "; mode " + settings.cloudMode().name()
        ));
        if (!hasKey) {
            plugin.messages().send(sender, "status.cloud-no-key", Map.of(
                    "url", CloudRecovery.SIGNUP_URL
            ));
            com.neomechanical.neomoderation.commands.TrialPromptSupport.sendTrialCtaIfPlayer(plugin, sender);
        } else {
            plugin.messages().send(sender, cloudStatusKey(plugin.coordinator().lastCloudResultKind()), Map.of(
                    "keysUrl", CloudRecovery.API_KEYS_URL,
                    "billingUrl", CloudRecovery.BILLING_URL
            ));
        }
        plugin.messages().send(sender, "status.rules", Map.of(
                "words", String.valueOf(settings.offline().bannedWords().size()),
                "urls", String.valueOf(settings.offline().bannedUrls().size())
        ));
        plugin.messages().send(sender, "status.allow", Map.of(
                "words", String.valueOf(settings.offline().allowedWords().size()),
                "urls", String.valueOf(settings.offline().allowedUrls().size())
        ));
        plugin.messages().send(sender, "status.actions", Map.of(
                "value", ModerationAction.describe(settings.actions())
        ));
        plugin.messages().send(sender, "status.alerts", Map.of(
                "value", settings.alerts().enabled()
                        ? (settings.alerts().includeMessage() ? "on (with message preview)" : "on (content hidden)")
                        : "off"
        ));
        plugin.messages().send(sender, "status.coverage", Map.of(
                "spam", coverage.spam() ? "on" : "off",
                "strikes", settings.strikes().enabled() ? "on" : "off",
                "surfaces", String.valueOf(settings.surfaces().enabledCount()),
                "cases", settings.cases().enabled() && plugin.caseLog().isAvailable() ? "on" : "off"
        ));
        plugin.messages().send(sender, "status.detections", Map.of(
                "value", String.valueOf(plugin.monitorStats().total())
        ));
    }

    static String cloudStatusKey(ModerationApiResult.Kind kind) {
        if (kind == null) {
            return "status.cloud-unverified";
        }
        return switch (kind) {
            case FLAGGED, CLEAR -> "status.cloud-ready";
            case CLIENT_AUTH -> "status.cloud-auth";
            case INSUFFICIENT_CREDITS -> "status.cloud-credits";
            case CLIENT_REQUEST -> "status.cloud-request-error";
            case TRANSIENT_TRANSPORT, OVERLOADED -> "status.cloud-transient";
        };
    }
}
