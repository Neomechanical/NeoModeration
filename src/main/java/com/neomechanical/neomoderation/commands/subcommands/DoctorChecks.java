package com.neomechanical.neomoderation.commands.subcommands;

import com.neomechanical.neomoderation.config.ModerationMode;
import com.neomechanical.neomoderation.config.ModerationSettings;
import com.neomechanical.neomoderation.moderation.CloudPlanNotices;
import com.neomechanical.neomoderation.moderation.CloudPlanWatch;
import com.neomechanical.neomoderation.moderation.CloudRecovery;
import com.neomechanical.neomoderation.moderation.DiscordAlerts;
import com.neomechanical.neomoderation.moderation.ModerationApiResult;
import com.neomechanical.neomoderation.moderation.ModerationCoverage;
import com.neomechanical.neomoderation.moderation.NeoMechanicalUsageClient;
import com.neomechanical.neomoderation.moderation.UsageSummary;
import com.neomechanical.neomoderation.platform.GameLanguage;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The checks /nmod doctor runs, as data: each names its locale keys
 * ({@code doctor.check.*}, {@code doctor.detail.*}) so the command only renders.
 */
final class DoctorChecks {
    private static final long CHAT_WAIT_CAP_MS = 2500L;

    enum Level {
        OK,
        WARN,
        FAIL
    }

    record Check(Level level, String check, String detail, Map<String, String> values) {
        static Check of(Level level, String check, String detail) {
            return new Check(level, check, detail, Map.of());
        }
    }

    private DoctorChecks() {
    }

    /**
     * Everything that can be said without asking the cloud. {@code discordProblem}
     * is why the last Discord post failed, or {@code null}.
     */
    static List<Check> local(ModerationSettings settings, boolean caseLogAvailable,
                             DiscordAlerts.Problem discordProblem) {
        ModerationCoverage coverage = ModerationCoverage.from(settings);
        List<Check> checks = new ArrayList<>();
        checks.add(settings.enabled()
                ? Check.of(Level.OK, "moderation", "on")
                : Check.of(Level.FAIL, "moderation", "off"));
        checks.add(localMode(settings, coverage));
        checks.add(rules(settings, coverage));
        checks.add(Check.of(Level.OK, "server-ads", !settings.offline().blockServerAds() ? "ads-off"
                : settings.offline().allowedUrls().isEmpty() ? "ads-on-hint" : "ads-on"));
        // The action list is put into words when the check is shown, in the reader's language.
        checks.add(Check.of(Level.OK, "actions", settings.actions().isEmpty() ? "actions-none" : "actions-list"));
        checks.add(spam(settings, coverage));
        checks.add(settings.strikes().enabled()
                ? new Check(Level.OK, "strikes", "strikes-on", Map.of(
                        "rungs", String.valueOf(settings.strikes().escalation().size()),
                        "minutes", String.valueOf(settings.strikes().decayMinutes())))
                : Check.of(Level.OK, "strikes", "strikes-off"));
        checks.add(settings.surfaces().enabledCount() == 0
                ? Check.of(Level.OK, "surfaces", "surfaces-none")
                : new Check(Level.OK, "surfaces", "surfaces-some",
                        Map.of("count", String.valueOf(settings.surfaces().enabledCount()))));
        checks.add(cases(settings, caseLogAvailable));
        String webhook = settings.alerts().discordWebhookUrl();
        if (!webhook.isBlank()) {
            if (!DiscordAlerts.isWebhookUrl(webhook)) {
                checks.add(Check.of(Level.FAIL, "discord", "discord-bad-url"));
            } else if (discordProblem != null) {
                checks.add(Check.of(Level.FAIL, "discord", switch (discordProblem) {
                    case GONE -> "discord-gone";
                    case RATE_LIMITED -> "discord-rate-limited";
                    case UNREACHABLE -> "discord-unreachable";
                }));
            } else {
                checks.add(Check.of(Level.OK, "discord", "discord-on"));
            }
        }
        return checks;
    }

    static List<Check> cloudSetup(ModerationSettings settings, boolean callsAllowed, ModerationApiResult.Kind last,
                                  long busySkipped) {
        return cloudSetup(settings, callsAllowed, last, busySkipped, CloudPlanWatch.Snapshot.UNKNOWN,
                System.currentTimeMillis());
    }

    /**
     * The cloud checks that need no network: key, mode, endpoint, circuit, last result, and the cloud plan as the
     * trial service said it, with a trial's days left counted at {@code nowMillis}.
     */
    static List<Check> cloudSetup(ModerationSettings settings, boolean callsAllowed, ModerationApiResult.Kind last,
                                  long busySkipped, CloudPlanWatch.Snapshot plan, long nowMillis) {
        ModerationCoverage coverage = ModerationCoverage.from(settings);
        List<Check> checks = new ArrayList<>();
        if (settings.api().apiKey().isBlank()) {
            checks.add(new Check(Level.WARN, "cloud", "no-key", Map.of("url", CloudRecovery.SIGNUP_URL)));
            return checks;
        }
        if (!coverage.hasCloudChecks()) {
            checks.add(Check.of(Level.WARN, "cloud-mode", "cloud-nothing"));
        } else {
            checks.add(settings.cloudMode() == ModerationMode.MONITOR
                    ? Check.of(Level.WARN, "cloud-mode", "cloud-monitor")
                    : Check.of(Level.OK, "cloud-mode", "cloud-enforce"));
        }
        checks.add(Check.of(Level.OK, "fail-policy", settings.failOpen() ? "fail-open" : "fail-closed"));
        long budget = (long) settings.api().connectTimeoutMs() + settings.api().readTimeoutMs() + 400L;
        checks.add(budget > CHAT_WAIT_CAP_MS
                ? Check.of(Level.OK, "timeouts", "timeouts-capped")
                : new Check(Level.OK, "timeouts", "timeouts-ok", Map.of(
                        "connect", String.valueOf(settings.api().connectTimeoutMs()),
                        "read", String.valueOf(settings.api().readTimeoutMs()))));
        if (!isHttpUri(settings.api().endpoint())) {
            checks.add(new Check(Level.FAIL, "endpoint", "endpoint-bad", Map.of("endpoint", settings.api().endpoint())));
            return checks;
        }
        checks.add(callsAllowed
                ? Check.of(Level.OK, "circuit", "circuit-closed")
                : Check.of(Level.WARN, "circuit", "circuit-open"));
        checks.add(events(last, plan));
        Check planCheck = plan(plan, last, nowMillis);
        if (planCheck != null) {
            checks.add(planCheck);
        }
        if (busySkipped > 0) {
            checks.add(new Check(Level.WARN, "busy", "events-busy", Map.of("count", String.valueOf(busySkipped))));
        }
        return checks;
    }

    /**
     * A hint when players here play in another language than the server's and English, and their game languages
     * are not trusted: another language's banned word that is everyday in theirs gets their lines caught. On 902
     * clean test lines from such players, a default English server caught 223, against 25 with the setting on.
     * Null when there is nothing to say; English counts as everyone's, as the word lists treat it.
     */
    static Check playerLanguages(ModerationSettings settings, List<String> onlineGameLanguages) {
        if (settings.offline().trustPlayerLanguage() || onlineGameLanguages.size() < 2) {
            return null;
        }
        long other = GameLanguage.inAnotherLanguage(settings.offline().locale(), onlineGameLanguages);
        if (other < 2 || other * 4 < onlineGameLanguages.size()) {
            return null;
        }
        return new Check(Level.WARN, "player-languages", "player-languages-other", Map.of(
                "count", String.valueOf(other), "online", String.valueOf(onlineGameLanguages.size())));
    }

    static boolean readyForAccountCheck(List<Check> cloudSetup) {
        return cloudSetup.stream().noneMatch(check -> check.check().equals("cloud") || check.check().equals("endpoint"));
    }

    static List<Check> account(UsageSummary usage, long ms) {
        return account(usage, ms, CloudPlanWatch.Snapshot.UNKNOWN);
    }

    /**
     * The account the key belongs to and its credits; a running trial with none is kept first, never sent to billing,
     * and when the cloud plan's line already says so, the credits line only says none are left.
     */
    static List<Check> account(UsageSummary usage, long ms, CloudPlanWatch.Snapshot plan) {
        return account(usage, ms, plan, null);
    }

    static List<Check> account(UsageSummary usage, long ms, CloudPlanWatch.Snapshot plan,
                               ModerationApiResult.Kind lastCheck) {
        String elapsed = String.valueOf(ms);
        Check credits = usage.creditsRemaining() > 0
                ? new Check(Level.OK, "credits", "credits-left", Map.of("value", String.valueOf(usage.creditsRemaining())))
                : CloudPlanNotices.creditsUsedUp(plan, lastCheck)
                ? new Check(Level.FAIL, "credits", "credits-none", Map.of("ms", elapsed))
                : CloudPlanNotices.keepsTrialFirst(plan)
                ? new Check(Level.FAIL, "credits", "account-trial-credits", Map.of("ms", elapsed))
                : new Check(Level.FAIL, "credits", "account-credits", Map.of("ms", elapsed, "url", CloudRecovery.BILLING_URL));
        return List.of(new Check(Level.OK, "account", "account-ok", Map.of(
                "ms", elapsed, "workspace", usage.workspace(), "plan", usage.tier())), credits);
    }

    static Check accountFailure(NeoMechanicalUsageClient.UsageException error, long ms) {
        return accountFailure(error, ms, CloudPlanWatch.Snapshot.UNKNOWN);
    }

    static Check accountFailure(NeoMechanicalUsageClient.UsageException error, long ms, CloudPlanWatch.Snapshot plan) {
        String elapsed = String.valueOf(ms);
        CloudPlanNotices.Reason ended = CloudPlanNotices.knownProblem(plan);
        if (error.kind() == ModerationApiResult.Kind.CLIENT_AUTH && ended != null && !ended.name().equals("auth")) {
            // The plan's own line says why and what to do; this one only says the key stopped working.
            return new Check(Level.FAIL, "account", "account-trial-ended", Map.of("ms", elapsed));
        }
        return switch (error.kind()) {
            case CLIENT_AUTH -> new Check(Level.FAIL, "account", "account-auth",
                    Map.of("ms", elapsed, "url", CloudRecovery.API_KEYS_URL));
            case INSUFFICIENT_CREDITS -> new Check(Level.FAIL, "credits", "account-credits",
                    Map.of("ms", elapsed, "url", CloudRecovery.BILLING_URL));
            case CLIENT_REQUEST -> new Check(Level.FAIL, "account", "account-request", Map.of("ms", elapsed));
            case TRANSIENT_TRANSPORT, OVERLOADED, FLAGGED, CLEAR ->
                    new Check(Level.WARN, "account", "account-down", Map.of("ms", elapsed));
        };
    }

    private static Check localMode(ModerationSettings settings, ModerationCoverage coverage) {
        if (!coverage.hasLocalChecks()) {
            return Check.of(Level.WARN, "local-mode", "local-none");
        }
        if (settings.mode() == ModerationMode.MONITOR) {
            return Check.of(Level.WARN, "local-mode", "local-monitor");
        }
        return coverage.localEnforces()
                ? Check.of(Level.OK, "local-mode", "local-enforce")
                : Check.of(Level.WARN, "local-mode", "local-surfaces");
    }

    private static Check rules(ModerationSettings settings, ModerationCoverage coverage) {
        if (coverage.localRules()) {
            return new Check(Level.OK, "rules", "rules", settings.offline().ruleCounts());
        }
        if (coverage.spam()) {
            return Check.of(Level.WARN, "rules", "rules-spam-only");
        }
        return coverage.hasCloudChecks()
                ? Check.of(Level.WARN, "rules", "rules-cloud-only")
                : Check.of(Level.FAIL, "rules", "rules-none");
    }

    private static Check spam(ModerationSettings settings, ModerationCoverage coverage) {
        if (coverage.chatSpam()) {
            return new Check(Level.OK, "spam", "spam-on", Map.of(
                    "rate", String.valueOf(settings.spam().messagesPer10s()),
                    "repeats", String.valueOf(settings.spam().duplicateLimit()),
                    "caps", String.valueOf(settings.spam().capsPercent())));
        }
        if (coverage.commandSpam()) {
            return new Check(Level.OK, "spam", "spam-commands",
                    Map.of("rate", String.valueOf(settings.spam().commandsPer10s())));
        }
        return settings.spam().enabled()
                ? Check.of(Level.WARN, "spam", "spam-idle")
                : Check.of(Level.OK, "spam", "off-plain");
    }

    private static Check cases(ModerationSettings settings, boolean caseLogAvailable) {
        if (!settings.cases().enabled()) {
            return Check.of(Level.OK, "cases", "off-plain");
        }
        if (!caseLogAvailable) {
            return Check.of(Level.WARN, "cases", "cases-missing");
        }
        return Check.of(Level.OK, "cases", settings.cases().storeContent() ? "cases-preview" : "cases-on");
    }

    /**
     * What the trial service said about the key, when it said anything; a date value is ISO until shown. A running
     * trial whose last check was refused for credits has used them up, and this line says what to do.
     */
    private static Check plan(CloudPlanWatch.Snapshot plan, ModerationApiResult.Kind last, long nowMillis) {
        CloudPlanNotices.PlanLine line = CloudPlanNotices.planLine(plan, last, nowMillis);
        if (line == null) {
            return null;
        }
        boolean fine = line.name().equals("trial") || line.name().equals("standard");
        return new Check(fine ? Level.OK : Level.FAIL, "cloud-plan", "plan-" + line.name(), line.values());
    }

    /**
     * The last cloud check; a refusal in the words its cause needs ({@link CloudPlanNotices#refusal}), or only as
     * refused when the cloud plan's line says why ({@link CloudPlanNotices#lastCheck}).
     */
    private static Check events(ModerationApiResult.Kind kind, CloudPlanWatch.Snapshot plan) {
        if (kind == null) {
            return Check.of(Level.WARN, "events", "events-none");
        }
        return switch (kind) {
            case FLAGGED, CLEAR -> Check.of(Level.OK, "events", "events-ok");
            case CLIENT_AUTH, INSUFFICIENT_CREDITS -> Check.of(Level.FAIL, "events",
                    "events-" + CloudPlanNotices.lastCheck(CloudPlanNotices.refusal(plan, kind)));
            case CLIENT_REQUEST -> Check.of(Level.FAIL, "events", "events-request");
            // A busy server is its own check, never recorded as the cloud's result.
            case TRANSIENT_TRANSPORT, OVERLOADED -> Check.of(Level.WARN, "events", "events-transient");
        };
    }

    private static boolean isHttpUri(String endpoint) {
        try {
            String scheme = URI.create(endpoint).getScheme();
            return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
