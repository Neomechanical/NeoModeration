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
import com.neomechanical.neomoderation.moderation.CloudPlanNotices;
import com.neomechanical.neomoderation.moderation.CloudPlanWatch;
import com.neomechanical.neomoderation.moderation.CloudRecovery;
import com.neomechanical.neomoderation.moderation.DiscordAlerts;
import com.neomechanical.neomoderation.moderation.ModerationApiResult;
import com.neomechanical.neomoderation.moderation.ModerationCoverage;
import org.bukkit.command.CommandSender;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** /nmod status: what is protected right now, one fact per line. */
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
    public String getPermission() {
        return Permissions.MODERATE;
    }

    @Override
    public List<String> getAliases() {
        return List.of("info");
    }

    /** The one-line header every command page opens with, in the reader's language. */
    static String dashboard(NeoModerationPlugin plugin, CommandSender reader) {
        ModerationSettings settings = plugin.settings();
        MessageService messages = plugin.messages();
        return messages.format(reader, "help.dashboard", Map.of(
                "version", plugin.getDescription().getVersion(),
                "mode", modeLabel(messages, reader, settings.mode()),
                "cloud", settings.api().apiKey().isBlank()
                        ? messages.text(reader, "label.off")
                        : modeLabel(messages, reader, settings.cloudMode()),
                "total", String.valueOf(plugin.monitorStats().total())));
    }

    static String modeLabel(MessageService messages, CommandSender reader, ModerationMode mode) {
        return messages.text(reader, mode == ModerationMode.MONITOR ? "label.alert-only" : "label.blocking");
    }

    static String onOff(MessageService messages, CommandSender reader, boolean on) {
        return messages.text(reader, on ? "label.on" : "label.off");
    }

    @Override
    public void execute(CommandSender sender, String label, String[] args) {
        ModerationSettings settings = plugin.settings();
        ModerationCoverage coverage = ModerationCoverage.from(settings);
        MessageService messages = plugin.messages();
        sender.sendMessage(dashboard(plugin, sender));
        messages.send(sender, "status.enabled", Map.of("value", onOff(messages, sender, settings.enabled())));
        messages.send(sender, "status.local", Map.of("value", messages.text(sender, localKey(settings, coverage))));
        messages.send(sender, "status.rules", settings.offline().ruleCounts());
        messages.send(sender, "status.packs", Map.of("value", settings.offline().languagePacks().isEmpty()
                ? messages.text(sender, "label.none")
                : String.join(", ", settings.offline().languagePacks())));
        messages.send(sender, "status.player-language", Map.of("value", messages.text(sender,
                settings.offline().trustPlayerLanguage() ? "status.value.player-language-on"
                        : "status.value.player-language-off")));
        messages.send(sender, "status.server-ads", Map.of("value", messages.text(sender,
                settings.offline().blockServerAds() ? "status.value.server-ads-on" : "status.value.server-ads-off")));
        messages.send(sender, "status.surfaces", Map.of("value", settings.surfaces().enabledCount() == 0
                ? messages.text(sender, "status.value.surfaces-off")
                : messages.format(sender, "status.value.surfaces-on",
                        Map.of("count", String.valueOf(settings.surfaces().enabledCount())))));
        messages.send(sender, "status.checks", Map.of(
                "spam", onOff(messages, sender, coverage.spam()),
                "strikes", onOff(messages, sender, settings.strikes().enabled()),
                "cases", onOff(messages, sender, settings.cases().enabled() && plugin.caseLog().isAvailable())));
        messages.send(sender, "status.actions", Map.of("value", ActionText.describe(messages, sender, settings.actions())));
        messages.send(sender, "status.alerts", Map.of("value", !settings.alerts().enabled()
                ? messages.text(sender, "label.off")
                : messages.text(sender, settings.alerts().includeMessage()
                        ? "status.value.alerts-preview" : "status.value.alerts-hidden")));
        messages.send(sender, "status.discord", Map.of("value",
                onOff(messages, sender, DiscordAlerts.isWebhookUrl(settings.alerts().discordWebhookUrl()))));
        if (settings.api().apiKey().isBlank()) {
            // Only admins can start a trial, so only they are pointed at it.
            messages.send(sender, sender.hasPermission(Permissions.ADMIN)
                    ? "status.cloud-no-key" : "status.cloud-no-key-staff");
            TrialPromptSupport.sendTrialCtaIfPlayer(plugin, sender);
            return;
        }
        messages.send(sender, "status.cloud", Map.of("value", cloudValue(messages, sender, settings, coverage)));
        CloudPlanWatch.Snapshot plan = plugin.cloudPlan().current();
        ModerationApiResult.Kind last = plugin.coordinator().lastCloudResultKind();
        sendPlanLine(messages, sender, plan, last, plugin.cloudPlan().now());
        String lastCheck = cloudStatusKey(last, plan);
        if (lastCheck != null) {
            String backAt = plugin.coordinator().freeChecksBackAt().map(Instant::toString).orElse("");
            messages.send(sender, lastCheck, Map.of(
                    "keysUrl", CloudRecovery.API_KEYS_URL,
                    "billingUrl", CloudRecovery.BILLING_URL,
                    "issuesUrl", CloudRecovery.ISSUES_URL,
                    "date", DateText.of(messages, sender, backAt),
                    "time", DateText.utcTime(backAt)));
        }
        plugin.coordinator().freePicturesBackAt().ifPresent(back -> messages.send(sender, "status.cloud-free-pictures",
                Map.of("date", DateText.of(messages, sender, back.toString()), "time", DateText.utcTime(back.toString()))));
        long busy = plugin.coordinator().busySkipped();
        if (busy > 0) {
            messages.send(sender, "status.cloud-busy", Map.of("count", String.valueOf(busy)));
        }
        long overShare = plugin.coordinator().playerBudget().skipped();
        if (overShare > 0) {
            messages.send(sender, "status.cloud-player-share", Map.of("count", String.valueOf(overShare)));
        }
    }

    private static String localKey(ModerationSettings settings, ModerationCoverage coverage) {
        if (!coverage.hasLocalChecks()) {
            return "label.not-armed";
        }
        if (settings.mode() == ModerationMode.MONITOR) {
            return "status.value.local-monitor";
        }
        return coverage.localEnforces() ? "status.value.local-enforce" : "status.value.local-surfaces-only";
    }

    private static String cloudValue(MessageService messages, CommandSender sender, ModerationSettings settings,
                                     ModerationCoverage coverage) {
        if (!coverage.hasCloudChecks()) {
            return messages.text(sender, "status.value.cloud-nothing");
        }
        return messages.format(sender, "status.value.cloud-checks", Map.of(
                "categories", String.valueOf(coverage.cloudText() ? settings.categories().enabledCount() : 0),
                "maps", coverage.mapArt() ? messages.text(sender, "status.value.cloud-maps") : "",
                "mode", modeLabel(messages, sender, settings.cloudMode())));
    }

    /**
     * The cloud plan as the trial service said it last, with what to do when it needs anything: a running trial's
     * end and days left (counted now), an ended or stopped one with the way to a key, a trial out of credits (the
     * last check was refused for them) with the way to keep it. The last-check line beside it then only says the
     * check was refused. The trial's claim link never goes in text; {@code /nmod trial status} gives it in game.
     */
    private static void sendPlanLine(MessageService messages, CommandSender sender, CloudPlanWatch.Snapshot plan,
                                     ModerationApiResult.Kind last, long nowMillis) {
        CloudPlanNotices.PlanLine line = CloudPlanNotices.planLine(plan, last, nowMillis);
        // A key from an account needs no plan line here: the last check says all there is to say about it.
        if (line == null || line.name().equals("standard")) {
            return;
        }
        Map<String, String> values = new HashMap<>(line.values());
        values.computeIfPresent("date", (key, iso) -> DateText.of(messages, sender, iso));
        messages.send(sender, "status.cloud-plan-" + line.name(), values);
    }

    static String cloudStatusKey(ModerationApiResult.Kind kind) {
        return cloudStatusKey(kind, CloudPlanWatch.Snapshot.UNKNOWN);
    }

    /**
     * The last check, in the words its cause needs ({@link CloudPlanNotices#refusal}): an ended trial is not a bad
     * key, and a refusal the plan line explains is only said to be one. Before any check: nothing when the plan line
     * says the trial ended, and a key the trial service does not know is said to be rejected, as no check was.
     */
    static String cloudStatusKey(ModerationApiResult.Kind kind, CloudPlanWatch.Snapshot plan) {
        if (kind == null) {
            CloudPlanNotices.Reason known = CloudPlanNotices.knownProblem(plan);
            if (known == null) {
                return "status.cloud-unverified";
            }
            return known.name().equals("auth") ? "status.cloud-key-rejected" : null;
        }
        return switch (kind) {
            case FLAGGED, CLEAR -> "status.cloud-ready";
            case CLIENT_AUTH, INSUFFICIENT_CREDITS ->
                    "status.cloud-" + CloudPlanNotices.lastCheck(CloudPlanNotices.refusal(plan, kind));
            case CLIENT_REQUEST -> "status.cloud-request-error";
            // The free plan's line says when its checks come back; without one, this line does.
            case FREE_PLAN_CAPPED -> plan.state() == CloudPlanWatch.State.FREE_PLAN ? null : "status.cloud-free-capped";
            case FREE_PLAN_REQUEST_SHAPE -> "status.cloud-free-plan-bug";
            // A busy server is counted on its own line, never recorded as the cloud's result; nor are the free plan's
            // picture allowance (its own line) and busy minutes, which pass on their own.
            case TRANSIENT_TRANSPORT, OVERLOADED, FREE_PLAN_PICTURES, FREE_PLAN_BUSY -> "status.cloud-transient";
        };
    }
}
