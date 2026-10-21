package com.neomechanical.neomoderation.commands.subcommands;

import com.neomechanical.neomoderation.NeoModerationPlugin;
import com.neomechanical.neomoderation.Permissions;
import com.neomechanical.neomoderation.commands.SubCommand;
import com.neomechanical.neomoderation.commands.TrialPromptSupport;
import com.neomechanical.neomoderation.config.ModerationMode;
import com.neomechanical.neomoderation.config.ModerationSettings;
import com.neomechanical.neomoderation.messages.ActionText;
import com.neomechanical.neomoderation.messages.DateText;
import com.neomechanical.neomoderation.messages.MessageService;
import com.neomechanical.neomoderation.messages.Reasons;
import com.neomechanical.neomoderation.moderation.CloudPlanNotices;
import com.neomechanical.neomoderation.moderation.CloudRecovery;
import com.neomechanical.neomoderation.moderation.DetectionHandler;
import com.neomechanical.neomoderation.moderation.ModerationApiResult;
import com.neomechanical.neomoderation.moderation.ModerationCoverage;
import com.neomechanical.neomoderation.moderation.OfflineModerationEngine;
import com.neomechanical.neomoderation.moderation.OfflineModerationResult;
import com.neomechanical.neomoderation.platform.GameLanguage;
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

    /** What the previewed message would get. */
    enum Result {
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
    public String getPermission() {
        return Permissions.MODERATE;
    }

    @Override
    public void execute(CommandSender sender, String label, String[] args) {
        MessageService messages = plugin.messages();
        if (args.length < 2) {
            messages.send(sender, "test.usage", Map.of("label", label));
            return;
        }
        String message = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        ModerationSettings settings = plugin.settings();

        sender.sendMessage(StatusCmd.dashboard(plugin, sender));
        messages.send(sender, "test.title", Map.of("message", message));
        messages.send(sender, "test.scope");

        if (!settings.enabled() || !settings.scanAsyncChat()) {
            messages.send(sender, settings.enabled() ? "test.chat-disabled" : "test.disabled");
            sendOutcome(sender, settings, false, DetectionHandler.Source.LOCAL, false);
            messages.sendFooter(sender);
            return;
        }

        // Read as the tester's own chat would be: in their game language.
        String speaker = sender instanceof Player player ? GameLanguage.of(player) : null;
        OfflineModerationResult local = OfflineModerationEngine.evaluate(message, settings.offline(), speaker);
        if (local.flagged()) {
            messages.send(sender, "test.local-flagged",
                    Map.of("reason", Reasons.describe(messages, sender, local.reason())));
            Reasons.languageWordHint(messages, sender, local.reason(), message,
                            settings.offline().trustPlayerLanguage())
                    .ifPresent(sender::sendMessage);
            messages.send(sender, "test.cloud-skipped-local");
            boolean censored = settings.chatCensorLocal()
                    && OfflineModerationEngine.censorCompletely(message, settings.offline(), speaker).isPresent();
            sendOutcome(sender, settings, true, DetectionHandler.Source.LOCAL, censored);
            messages.sendFooter(sender);
            return;
        }
        messages.send(sender, "test.local-clear");

        if (settings.api().apiKey().isBlank()) {
            // Only admins can start a trial, so only they are pointed at it.
            messages.send(sender, sender.hasPermission(Permissions.ADMIN)
                    ? "test.cloud-skipped-key" : "test.cloud-skipped-key-staff", Map.of("url", CloudRecovery.SIGNUP_URL));
            TrialPromptSupport.sendTrialCtaIfPlayer(plugin, sender);
            sendOutcome(sender, settings, false, DetectionHandler.Source.LOCAL, false);
            messages.sendFooter(sender);
            return;
        }
        if (!ModerationCoverage.from(settings).cloudText()) {
            messages.send(sender, "test.cloud-skipped-categories");
            sendOutcome(sender, settings, false, DetectionHandler.Source.CLOUD, false);
            messages.sendFooter(sender);
            return;
        }
        if (!plugin.coordinator().isRemoteCallAllowed()) {
            messages.send(sender, "test.cloud-skipped-circuit");
            sendCloudOutcome(sender, settings, ModerationApiResult.transientTransport());
            messages.sendFooter(sender);
            return;
        }

        messages.send(sender, "test.cloud-checking");
        String senderName = sender.getName();
        String senderUuid = sender instanceof Player player ? player.getUniqueId().toString() : CONSOLE_UUID;
        plugin.runAsync(() -> {
            long start = System.nanoTime();
            ModerationApiResult result = plugin.apiClient().moderateText(
                    senderName, senderUuid, message, settings.api(), settings.categories());
            plugin.coordinator().recordApiResult(result, settings.api());
            String ms = String.valueOf((System.nanoTime() - start) / 1_000_000L);
            plugin.runSync(() -> {
                // A refusal in the words its cause needs: an ended trial is not a bad key.
                CloudPlanNotices.Reason refused = CloudPlanNotices.refusal(plugin.cloudPlan().current(), result.kind());
                Map<String, String> values = refused != null
                        ? Map.of("ms", ms, "url", refused.url(), "date", DateText.of(messages, sender, refused.dateIso()))
                        : result.kind() == ModerationApiResult.Kind.FLAGGED
                        ? Map.of("ms", ms, "reason", Reasons.describe(messages, sender, result.reason()))
                        : Map.of("ms", ms);
                messages.send(sender, refused != null ? "test.cloud-" + refused.name() : cloudMessageKey(result.kind()),
                        values);
                sendCloudOutcome(sender, settings, result);
                messages.send(sender, "test.note");
                messages.sendFooter(sender);
            });
        });
    }

    private void sendOutcome(CommandSender sender, ModerationSettings settings, boolean detected,
                             DetectionHandler.Source source, boolean censored) {
        MessageService messages = plugin.messages();
        switch (outcome(settings, detected, source, censored)) {
            case ALLOWED -> messages.send(sender, "test.would-allowed");
            case MONITORED -> messages.send(sender, "test.would-monitor");
            case CENSORED -> messages.send(sender, "test.would-censor");
            case ENFORCED -> messages.send(sender, "test.would-enforce", Map.of("then", settings.actions().isEmpty()
                    ? ""
                    : messages.format(sender, "test.would-then",
                            Map.of("actions", ActionText.describe(messages, sender, settings.actions())))));
        }
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

    static Result outcome(boolean enabled, boolean detected, ModerationMode mode, boolean censored) {
        if (!enabled || !detected) {
            return Result.ALLOWED;
        }
        if (mode == ModerationMode.MONITOR) {
            return Result.MONITORED;
        }
        return censored ? Result.CENSORED : Result.ENFORCED;
    }

    static Result outcome(ModerationSettings settings, boolean detected, DetectionHandler.Source source,
                           boolean censored) {
        return outcome(settings.enabled(), detected, source.modeIn(settings),
                source == DetectionHandler.Source.LOCAL && censored);
    }
}
