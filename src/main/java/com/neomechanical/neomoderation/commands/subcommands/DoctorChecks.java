package com.neomechanical.neomoderation.commands.subcommands;

import com.neomechanical.neomoderation.config.ModerationMode;
import com.neomechanical.neomoderation.config.ModerationSettings;
import com.neomechanical.neomoderation.moderation.CloudRecovery;
import com.neomechanical.neomoderation.moderation.DiscordAlerts;
import com.neomechanical.neomoderation.moderation.ModerationApiResult;
import com.neomechanical.neomoderation.moderation.ModerationCoverage;
import com.neomechanical.neomoderation.moderation.NeoMechanicalUsageClient;
import com.neomechanical.neomoderation.moderation.UsageSummary;

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

    /** The cloud checks that need no network: key, mode, endpoint, circuit, last result. */
    static List<Check> cloudSetup(ModerationSettings settings, boolean callsAllowed, ModerationApiResult.Kind last,
                                  long busySkipped) {
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
        checks.add(events(last));
        if (busySkipped > 0) {
            checks.add(new Check(Level.WARN, "busy", "events-busy", Map.of("count", String.valueOf(busySkipped))));
        }
        return checks;
    }

    static boolean readyForAccountCheck(List<Check> cloudSetup) {
        return cloudSetup.stream().noneMatch(check -> check.check().equals("cloud") || check.check().equals("endpoint"));
    }

    static List<Check> account(UsageSummary usage, long ms) {
        return List.of(
                new Check(Level.OK, "account", "account-ok", Map.of(
                        "ms", String.valueOf(ms), "workspace", usage.workspace(), "plan", usage.tier())),
                usage.creditsRemaining() > 0
                        ? new Check(Level.OK, "credits", "credits-left",
                                Map.of("value", String.valueOf(usage.creditsRemaining())))
                        : new Check(Level.FAIL, "credits", "account-credits",
                                Map.of("ms", String.valueOf(ms), "url", CloudRecovery.BILLING_URL)));
    }

    static Check accountFailure(NeoMechanicalUsageClient.UsageException error, long ms) {
        String elapsed = String.valueOf(ms);
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
            return new Check(Level.OK, "rules", "rules", Map.of(
                    "words", String.valueOf(settings.offline().effectiveWords().size()),
                    "urls", String.valueOf(settings.offline().bannedUrls().size()),
                    "exceptions", String.valueOf(settings.offline().allowedWords().size()
                            + settings.offline().allowedUrls().size())));
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

    private static Check events(ModerationApiResult.Kind kind) {
        if (kind == null) {
            return Check.of(Level.WARN, "events", "events-none");
        }
        return switch (kind) {
            case FLAGGED, CLEAR -> Check.of(Level.OK, "events", "events-ok");
            case CLIENT_AUTH -> Check.of(Level.FAIL, "events", "events-auth");
            case INSUFFICIENT_CREDITS -> Check.of(Level.FAIL, "events", "events-credits");
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
