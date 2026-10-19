package com.neomechanical.neomoderation.commands.subcommands;

import com.neomechanical.neomoderation.NeoModerationPlugin;
import com.neomechanical.neomoderation.commands.SubCommand;
import com.neomechanical.neomoderation.config.ModerationAction;
import com.neomechanical.neomoderation.config.ModerationMode;
import com.neomechanical.neomoderation.config.ModerationSettings;
import com.neomechanical.neomoderation.moderation.CloudRecovery;
import com.neomechanical.neomoderation.moderation.DetectionHandler;
import com.neomechanical.neomoderation.moderation.ModerationApiResult;
import com.neomechanical.neomoderation.moderation.ModerationCoverage;
import com.neomechanical.neomoderation.moderation.OfflineModerationEngine;
import com.neomechanical.neomoderation.moderation.OfflineModerationResult;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.Map;

/**
 * Previews local content rules and, if reached, one cloud check. Rate, repeat,
 * command, and map checks need live event context and are not simulated. Never
 * executes actions. A cloud check consumes one API request.
 */
public class TestCmd implements SubCommand {
    private static final String CONSOLE_UUID = "00000000-0000-0000-0000-000000000000";

    enum Outcome {
        ALLOWED,
        MONITORED,
        CENSORED,
        ENFORCED
    }

    private final NeoModerationPlugin plugin;

    public TestCmd(NeoModerationPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getName() {
        return "test";
    }

    @Override
    public String getDescription() {
        return "Preview how a message would be moderated.";
    }

    @Override
    public String getUsage() {
        return "/nmod test <message>";
    }

    @Override
    public void execute(CommandSender sender, String label, String[] args) {
        if (args.length < 2) {
            plugin.messages().send(sender, "test.usage", Map.of("label", label));
            return;
        }
        String message = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        ModerationSettings settings = plugin.settings();

        plugin.messages().sendDashboard(sender,
                plugin.getDescription().getVersion(),
                settings.mode().name(),
                ModerationCoverage.cloudDashboardLabel(settings),
                plugin.monitorStats().total());
        plugin.messages().send(sender, "test.title", Map.of("message", message));
        plugin.messages().send(sender, "test.scope");

        if (!settings.enabled()) {
            plugin.messages().send(sender, "test.disabled");
            sendOutcome(sender, settings, false, DetectionHandler.Source.LOCAL, false);
            plugin.messages().sendFooter(sender);
            return;
        }
        if (!settings.scanAsyncChat()) {
            plugin.messages().send(sender, "test.chat-disabled");
            sendOutcome(sender, settings, false, DetectionHandler.Source.LOCAL, false);
            plugin.messages().sendFooter(sender);
            return;
        }

        OfflineModerationResult local = OfflineModerationEngine.evaluate(message, settings.offline());
        if (local.flagged()) {
            plugin.messages().send(sender, "test.local-flagged", Map.of("reason", local.reason()));
        } else {
            plugin.messages().send(sender, "test.local-clear");
        }

        if (!shouldCheckCloud(local.flagged())) {
            plugin.messages().send(sender, "test.cloud-skipped-local");
            boolean censored = settings.chatCensorLocal()
                    && !OfflineModerationEngine.censor(message, settings.offline()).equals(message);
            sendOutcome(sender, settings, true, DetectionHandler.Source.LOCAL, censored);
            plugin.messages().sendFooter(sender);
            return;
        }

        if (settings.api().apiKey().isBlank()) {
            plugin.messages().send(sender, "test.cloud-skipped-key", Map.of(
                    "url", CloudRecovery.SIGNUP_URL
            ));
            com.neomechanical.neomoderation.commands.TrialPromptSupport.sendTrialCtaIfPlayer(plugin, sender);
            sendOutcome(sender, settings, false, DetectionHandler.Source.LOCAL, false);
            plugin.messages().sendFooter(sender);
            return;
        }
        if (!ModerationCoverage.from(settings).cloudText()) {
            plugin.messages().send(sender, "test.cloud-skipped-categories");
            sendOutcome(sender, settings, false, DetectionHandler.Source.CLOUD, false);
            plugin.messages().sendFooter(sender);
            return;
        }
        if (!plugin.coordinator().isRemoteCallAllowed()) {
            plugin.messages().send(sender, "test.cloud-skipped-circuit");
            sendCloudOutcome(sender, settings, ModerationApiResult.transientTransport());
            plugin.messages().sendFooter(sender);
            return;
        }

        plugin.messages().send(sender, "test.cloud-checking");
        String senderName = sender.getName();
        String senderUuid = sender instanceof Player player ? player.getUniqueId().toString() : CONSOLE_UUID;
        plugin.runAsync(() -> {
            long start = System.nanoTime();
            ModerationApiResult result = plugin.apiClient().moderateText(
                    senderName, senderUuid, message, settings.api(), settings.categories());
            plugin.coordinator().recordApiResult(result);
            String ms = String.valueOf((System.nanoTime() - start) / 1_000_000L);
            plugin.runSync(() -> {
                Map<String, String> placeholders = switch (result.kind()) {
                    case CLIENT_AUTH -> Map.of("ms", ms, "url", CloudRecovery.API_KEYS_URL);
                    case INSUFFICIENT_CREDITS -> Map.of("ms", ms, "url", CloudRecovery.BILLING_URL);
                    default -> Map.of("ms", ms);
                };
                plugin.messages().send(sender, cloudMessageKey(result.kind()), placeholders);
                sendCloudOutcome(sender, settings, result);
                plugin.messages().send(sender, "test.note");
                plugin.messages().sendFooter(sender);
            });
        });
    }

    private void sendOutcome(CommandSender sender, ModerationSettings settings, boolean detected,
                             DetectionHandler.Source source, boolean censored) {
        switch (outcome(settings, detected, source, censored)) {
            case ALLOWED -> plugin.messages().send(sender, "test.would-allowed");
            case MONITORED -> plugin.messages().send(sender, "test.would-monitor");
            case CENSORED -> plugin.messages().send(sender, "test.would-censor");
            case ENFORCED -> plugin.messages().send(sender, "test.would-enforce", Map.of(
                    "actions", ModerationAction.describe(settings.actions())
            ));
        }
    }

    static boolean shouldCheckCloud(boolean locallyFlagged) {
        return !locallyFlagged;
    }

    private void sendCloudOutcome(CommandSender sender, ModerationSettings settings, ModerationApiResult result) {
        if (result.kind() != ModerationApiResult.Kind.CLEAR && !result.isFlagged()
                && !settings.failOpen() && settings.cloudMode() == ModerationMode.ENFORCE) {
            plugin.messages().send(sender, "test.would-block-unavailable");
        } else {
            sendOutcome(sender, settings, result.isFlagged(), DetectionHandler.Source.CLOUD, false);
        }
    }

    static String cloudMessageKey(ModerationApiResult.Kind kind) {
        return switch (kind) {
            case FLAGGED -> "test.cloud-flagged";
            case CLEAR -> "test.cloud-clear";
            case CLIENT_AUTH -> "test.cloud-auth";
            case INSUFFICIENT_CREDITS -> "test.cloud-credits";
            case CLIENT_REQUEST -> "test.cloud-request-error";
            case TRANSIENT_TRANSPORT, OVERLOADED -> "test.cloud-error";
        };
    }

    static Outcome outcome(boolean enabled, boolean detected, ModerationMode mode, boolean censored) {
        if (!enabled || !detected) {
            return Outcome.ALLOWED;
        }
        if (mode == ModerationMode.MONITOR) {
            return Outcome.MONITORED;
        }
        return censored ? Outcome.CENSORED : Outcome.ENFORCED;
    }

    static Outcome outcome(ModerationSettings settings, boolean detected, DetectionHandler.Source source,
                           boolean censored) {
        return outcome(settings.enabled(), detected, source.modeIn(settings),
                source == DetectionHandler.Source.LOCAL && censored);
    }
}
