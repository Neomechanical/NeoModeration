package com.neomechanical.neomoderation.commands.subcommands;

import com.neomechanical.neomoderation.config.BukkitConfigView;
import com.neomechanical.neomoderation.config.ModerationCategorySettings;
import com.neomechanical.neomoderation.config.ModerationSettings;
import com.neomechanical.neomoderation.messages.MessageService;
import com.neomechanical.neomoderation.moderation.CloudPlanWatch;
import com.neomechanical.neomoderation.moderation.CloudRecovery;
import com.neomechanical.neomoderation.moderation.DiscordAlerts;
import com.neomechanical.neomoderation.moderation.ModerationApiResult;
import com.neomechanical.neomoderation.moderation.NeoMechanicalUsageClient;
import com.neomechanical.neomoderation.moderation.TrialClient;
import com.neomechanical.neomoderation.moderation.UsageSummary;
import com.neomechanical.neomoderation.testing.TestPlugin;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DoctorChecksTest {
    private static final YamlConfiguration ENGLISH = TestPlugin.locale(MessageService.DEFAULT_LOCALE);

    @Test
    void everyCheckEveryConfigurationCanProduceHasWords() {
        List<DoctorChecks.Check> checks = new ArrayList<>();
        for (Consumer<YamlConfiguration> setup : configurations()) {
            YamlConfiguration config = new YamlConfiguration();
            setup.accept(config);
            ModerationSettings settings = ModerationSettings.from(new BukkitConfigView(config));
            checks.addAll(DoctorChecks.local(settings, true, null));
            for (DiscordAlerts.Problem problem : DiscordAlerts.Problem.values()) {
                checks.addAll(DoctorChecks.local(settings, false, problem));
            }
            checks.addAll(DoctorChecks.cloudSetup(settings, false, null, 0));
            // Every plan the trial service can name, each with every last result: the plan line only appears with one.
            for (CloudPlanWatch.Snapshot plan : plans()) {
                checks.addAll(DoctorChecks.cloudSetup(settings, true, null, 0, plan, TestPlugin.NOW));
                for (ModerationApiResult.Kind kind : ModerationApiResult.Kind.values()) {
                    checks.addAll(DoctorChecks.cloudSetup(settings, true, kind, 3, plan, TestPlugin.NOW));
                }
            }
            DoctorChecks.Check languages = DoctorChecks.playerLanguages(settings, List.of("da_dk", "nb_no", "sv_se"));
            if (languages != null) {
                checks.add(languages);
            }
        }
        for (CloudPlanWatch.Snapshot plan : plans()) {
            checks.addAll(DoctorChecks.account(usage(100), 12, plan));
            checks.addAll(DoctorChecks.account(usage(0), 12, plan));
            for (ModerationApiResult.Kind kind : ModerationApiResult.Kind.values()) {
                checks.add(DoctorChecks.accountFailure(new NeoMechanicalUsageClient.UsageException(kind, "test"), 12,
                        plan));
            }
        }
        List<String> produced = checks.stream().map(DoctorChecks.Check::check).distinct().toList();
        assertTrue(produced.contains("cloud-plan") && produced.contains("player-languages"),
                "the words are checked only for checks this test makes: " + produced);

        List<String> missing = new ArrayList<>();
        for (DoctorChecks.Check check : checks) {
            for (String key : List.of("doctor." + check.level().name().toLowerCase(Locale.ROOT),
                    "doctor.check." + check.check(), "doctor.detail." + check.detail())) {
                if (!ENGLISH.isString(key)) {
                    missing.add(key);
                }
            }
        }
        assertTrue(missing.isEmpty(), "en_US.yml lacks " + missing.stream().distinct().toList());
    }

    private static UsageSummary usage(long credits) {
        return new UsageSummary("Test", "paid", 60, credits, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    /** Every plan the trial service can name: a running trial, with and without its credits, ended, stopped, and so on. */
    private static List<CloudPlanWatch.Snapshot> plans() {
        TrialClient.TrialStatusResult running = new TrialClient.TrialStatusResult(true, "active",
                "2026-10-30T00:00:00Z", 9, "https://claim");
        return List.of(
                CloudPlanWatch.Snapshot.UNKNOWN,
                new CloudPlanWatch.Snapshot(CloudPlanWatch.State.TRIAL_ACTIVE, running, null),
                new CloudPlanWatch.Snapshot(CloudPlanWatch.State.TRIAL_ACTIVE, running,
                        ModerationApiResult.Kind.INSUFFICIENT_CREDITS),
                new CloudPlanWatch.Snapshot(CloudPlanWatch.State.TRIAL_ENDED, new TrialClient.TrialStatusResult(true,
                        "expired", "2026-10-01T00:00:00Z", 0, "https://signup"), null),
                new CloudPlanWatch.Snapshot(CloudPlanWatch.State.TRIAL_ENDED, new TrialClient.TrialStatusResult(true,
                        "revoked", "2026-10-30T00:00:00Z", 0, "https://signup"), null),
                new CloudPlanWatch.Snapshot(CloudPlanWatch.State.STANDARD, new TrialClient.TrialStatusResult(false,
                        "standard_workspace", "", 0, "https://billing"), null),
                new CloudPlanWatch.Snapshot(CloudPlanWatch.State.KEY_REJECTED, null, null));
    }

    private static List<Consumer<YamlConfiguration>> configurations() {
        return List.of(
                config -> config.set("moderation.enabled", true),
                config -> config.set("moderation.enabled", false),
                config -> {
                    config.set("moderation.enabled", true);
                    config.set("moderation.mode", "monitor");
                    config.set("moderation.api.apiKey", "key");
                    config.set("moderation.cloudMode", "monitor");
                },
                config -> {
                    config.set("moderation.offline.enabled", false);
                    config.set("moderation.spam.enabled", false);
                    config.set("moderation.api.apiKey", "key");
                    config.set("moderation.api.endpoint", "not a url");
                },
                config -> {
                    config.set("moderation.offline.enabled", false);
                    config.set("moderation.spam.enabled", true);
                    config.set("moderation.spam.messagesPer10s", 0);
                    config.set("moderation.spam.duplicateLimit", 0);
                    config.set("moderation.spam.capsPercent", 0);
                    config.set("moderation.spam.maxCharRun", 0);
                    config.set("moderation.spam.commandsPer10s", 5);
                },
                config -> {
                    config.set("moderation.mode", "enforce");
                    config.set("moderation.offline.enabled", false);
                    config.set("moderation.spam.enabled", false);
                    config.set("moderation.surfaces.sign", "block");
                    config.set("moderation.offline.blockServerAds", false);
                    config.set("moderation.strikes.enabled", true);
                    config.set("moderation.cases.storeContent", true);
                    config.set("moderation.alerts.discordWebhookUrl", "https://example.com/not-discord");
                    config.set("moderation.chat.failOpen", false);
                    config.set("moderation.api.connectTimeoutMs", 2000);
                    config.set("moderation.api.readTimeoutMs", 2000);
                    config.set("moderation.actions", List.of(Map.of("type", "kick")));
                },
                config -> {
                    config.set("moderation.api.apiKey", "key");
                    config.set("moderation.mapArt.enabled", false);
                    for (String category : ModerationCategorySettings.categoryKeys()) {
                        config.set("moderation.categories." + category, false);
                    }
                    config.set("moderation.cases.enabled", false);
                    config.set("moderation.alerts.discordWebhookUrl", "https://discord.com/api/webhooks/1/a");
                });
    }

    @Test
    void playersInAnotherLanguageAreToldAboutWhenTheirLanguageIsNotTrusted() {
        ModerationSettings untrusted = ModerationSettings.from(new com.neomechanical.neomoderation.config.BukkitConfigView(
                new org.bukkit.configuration.file.YamlConfiguration()));
        org.bukkit.configuration.file.YamlConfiguration trustedConfig = new org.bukkit.configuration.file.YamlConfiguration();
        trustedConfig.set("moderation.offline.trustPlayerLanguage", true);
        ModerationSettings trusted = ModerationSettings.from(
                new com.neomechanical.neomoderation.config.BukkitConfigView(trustedConfig));
        org.bukkit.configuration.file.YamlConfiguration frenchConfig = new org.bukkit.configuration.file.YamlConfiguration();
        frenchConfig.set("locale", "fr_FR");
        ModerationSettings french = ModerationSettings.from(
                new com.neomechanical.neomoderation.config.BukkitConfigView(frenchConfig));

        DoctorChecks.Check hint = DoctorChecks.playerLanguages(untrusted,
                List.of("da_dk", "nb_no", "en_us", "en_gb"));
        assertEquals("player-languages-other", hint.detail());
        assertEquals(java.util.Map.of("count", "2", "online", "4"), hint.values());

        assertNull(DoctorChecks.playerLanguages(trusted, List.of("da_dk", "nb_no")), "trusted: nothing to say");
        assertNull(DoctorChecks.playerLanguages(untrusted, List.of("da_dk", "en_us", "en_us", "en_gb",
                "en_us")), "one player of five is no pattern");
        assertNull(DoctorChecks.playerLanguages(french, List.of("fr_fr", "fr_ca", "en_us")),
                "the server's own language and English are always read");
        assertNull(DoctorChecks.playerLanguages(untrusted, List.of("da_dk")), "one player online");
        assertNull(DoctorChecks.playerLanguages(untrusted, List.of()));
    }

    @Test
    void theDoctorNamesTheTrialsStateAndCountsItsDaysByTheClock() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("moderation.api.apiKey", "nm_key");
        ModerationSettings settings = ModerationSettings.from(new BukkitConfigView(config));
        long now = java.time.Instant.parse("2026-10-28T00:00:00Z").toEpochMilli();
        TrialClient.TrialStatusResult running = new TrialClient.TrialStatusResult(true, "active",
                "2026-10-30T00:00:00Z", 9, "https://claim");

        java.util.Map<String, DoctorChecks.Check> checks = byName(DoctorChecks.cloudSetup(settings, true, null, 0,
                new CloudPlanWatch.Snapshot(CloudPlanWatch.State.TRIAL_ACTIVE, running, null), now));
        assertEquals("plan-trial", checks.get("cloud-plan").detail());
        assertEquals("2", checks.get("cloud-plan").values().get("days"), "two days left now, not the nine once sent");

        checks = byName(DoctorChecks.cloudSetup(settings, true, ModerationApiResult.Kind.INSUFFICIENT_CREDITS, 0,
                new CloudPlanWatch.Snapshot(CloudPlanWatch.State.TRIAL_ACTIVE, running,
                        ModerationApiResult.Kind.INSUFFICIENT_CREDITS), now));
        assertEquals("plan-trial-credits", checks.get("cloud-plan").detail());
        assertEquals(null, checks.get("cloud-plan").values().get("url"), "the claim link is never text");
        assertEquals("events-refused", checks.get("events").detail(), "the plan line says why, once");

        checks = byName(DoctorChecks.cloudSetup(settings, true, ModerationApiResult.Kind.INSUFFICIENT_CREDITS, 0,
                new CloudPlanWatch.Snapshot(CloudPlanWatch.State.TRIAL_ACTIVE, running, null), now));
        assertEquals("plan-trial-credits", checks.get("cloud-plan").detail(),
                "a last check refused for credits is enough for the plan line to say what to do");

        checks = byName(DoctorChecks.cloudSetup(settings, true, ModerationApiResult.Kind.CLIENT_AUTH, 0,
                new CloudPlanWatch.Snapshot(CloudPlanWatch.State.TRIAL_ENDED, new TrialClient.TrialStatusResult(true,
                        "revoked", "2026-10-30T00:00:00Z", 0, "https://signup"), null), now));
        assertEquals("plan-trial-revoked", checks.get("cloud-plan").detail());
        assertEquals("events-refused", checks.get("events").detail());
        assertEquals(CloudRecovery.SIGNUP_URL, checks.get("cloud-plan").values().get("url"));

        // A running trial with no credits is told to keep the trial first, never sent to billing.
        java.util.Map<String, DoctorChecks.Check> account = byName(DoctorChecks.account(usage(0), 40,
                new CloudPlanWatch.Snapshot(CloudPlanWatch.State.TRIAL_ACTIVE, running, null)));
        assertEquals("account-trial-credits", account.get("credits").detail());
        assertEquals(null, account.get("credits").values().get("url"));
        account = byName(DoctorChecks.account(usage(0), 40, new CloudPlanWatch.Snapshot(CloudPlanWatch.State.TRIAL_ACTIVE,
                running, ModerationApiResult.Kind.INSUFFICIENT_CREDITS)));
        assertEquals("credits-none", account.get("credits").detail(), "the plan line already says how to add more");
        assertEquals("account-credits", byName(DoctorChecks.account(usage(0), 40)).get("credits").detail());
    }

    private static java.util.Map<String, DoctorChecks.Check> byName(List<DoctorChecks.Check> checks) {
        java.util.Map<String, DoctorChecks.Check> named = new java.util.HashMap<>();
        checks.forEach(check -> named.put(check.check(), check));
        return named;
    }
}
