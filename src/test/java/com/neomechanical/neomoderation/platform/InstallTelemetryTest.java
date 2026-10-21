package com.neomechanical.neomoderation.platform;

import com.neomechanical.neomoderation.config.BukkitConfigView;
import com.neomechanical.neomoderation.config.ModerationSettings;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The bStats chart values. These are the only way the next release can tell an
 * armed install from an inert one: until 1.6.0 the three registered custom
 * charts had no counterpart on bstats.org, so every value ever sent for them was
 * discarded and no retention theory could be tested against real installs.
 */
class InstallTelemetryTest {

    private static ModerationSettings settings(String... keyValues) {
        YamlConfiguration config = new YamlConfiguration();
        config.set("moderation.enabled", true);
        for (int index = 0; index < keyValues.length; index += 2) {
            String raw = keyValues[index + 1];
            Object value = "true".equals(raw) ? Boolean.TRUE : "false".equals(raw) ? Boolean.FALSE : raw;
            config.set(keyValues[index], value);
        }
        return ModerationSettings.from(new BukkitConfigView(config));
    }

    @Test
    void separatesAnArmedInstallFromAnInertOne() {
        assertEquals("enforce_local_only",
                InstallTelemetry.protectionState(settings("moderation.mode", "enforce",
                        "moderation.cloudMode", "enforce")));
        assertEquals("monitor_local_only",
                InstallTelemetry.protectionState(settings("moderation.mode", "monitor",
                        "moderation.cloudMode", "monitor")));
    }

    @Test
    void reportsMixedLocalAndCloudModesWhenAKeyIsConfigured() {
        assertEquals("enforce_local_monitor_cloud",
                InstallTelemetry.protectionState(settings("moderation.mode", "enforce",
                        "moderation.cloudMode", "monitor",
                        "moderation.api.apiKey", "test-key")));
    }

    @Test
    void reportsCloudOnlyAndMixedModesWhenThoseChecksAreArmed() {
        assertEquals("monitor_cloud_only", InstallTelemetry.protectionState(settings(
                "moderation.offline.enabled", "false",
                "moderation.spam.enabled", "false",
                "moderation.api.apiKey", "test-key",
                "moderation.cloudMode", "monitor")));
        assertEquals("monitor_local_enforce_cloud", InstallTelemetry.protectionState(settings(
                "moderation.mode", "monitor",
                "moderation.cloudMode", "enforce",
                "moderation.api.apiKey", "test-key")));
    }

    @Test
    void emptyLocalRulesAndDisabledSpamAreNotCountedAsProtection() {
        assertEquals("nothing_armed", InstallTelemetry.protectionState(settings(
                "moderation.spam.enabled", "false",
                "moderation.offline.blockServerAds", "false")));
    }

    @Test
    void serverAdBlockingAloneCountsAsLocalProtection() {
        assertEquals("enforce_local_only", InstallTelemetry.protectionState(settings(
                "moderation.spam.enabled", "false")));
    }

    @Test
    void antiSpamOnlyStillCountsAsLocalProtection() {
        assertEquals("enforce_local_only", InstallTelemetry.protectionState(settings(
                "moderation.offline.enabled", "false")));
    }

    @Test
    void mapScanningWithNoEventTriggerIsNotArmed() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("moderation.enabled", true);
        config.set("moderation.offline.enabled", false);
        config.set("moderation.spam.enabled", false);
        config.set("moderation.api.apiKey", "test-key");
        config.set("moderation.mapArt.scanOnHold", false);
        config.set("moderation.mapArt.scanOnFrameInteract", false);
        for (String category : com.neomechanical.neomoderation.config.ModerationCategorySettings.categoryKeys()) {
            config.set("moderation.categories." + category, false);
        }
        ModerationSettings settings = ModerationSettings.from(new BukkitConfigView(config));

        assertEquals("nothing_armed", InstallTelemetry.protectionState(settings));
        assertEquals("off", InstallTelemetry.mapArtState(settings));
    }

    @Test
    void auxiliaryChartsDoNotTreatSavedFlagsAsActiveChecks() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("moderation.enabled", true);
        config.set("moderation.offline.enabled", false);
        config.set("moderation.spam.enabled", false);
        config.set("moderation.surfaces.sign", "monitor");
        config.set("moderation.api.apiKey", "test-key");
        config.set("moderation.mapArt.enabled", false);
        for (String category : com.neomechanical.neomoderation.config.ModerationCategorySettings.categoryKeys()) {
            config.set("moderation.categories." + category, false);
        }
        ModerationSettings inert = ModerationSettings.from(new BukkitConfigView(config));

        assertEquals("nothing_armed", InstallTelemetry.cloudEnabledState(inert));
        assertEquals("none", InstallTelemetry.surfacesArmed(inert));
        assertEquals("off", InstallTelemetry.spamState(inert));

        config.set("moderation.surfaces.command", "block");
        config.set("moderation.spam.enabled", true);
        ModerationSettings commandSpam = ModerationSettings.from(new BukkitConfigView(config));
        assertEquals("local_only", InstallTelemetry.cloudEnabledState(commandSpam));
        assertEquals("1", InstallTelemetry.surfacesArmed(commandSpam));
        assertEquals("on", InstallTelemetry.spamState(commandSpam));
    }

    @Test
    void monitorOnlySignRuleIsNotReportedAsEnforcement() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("moderation.enabled", true);
        config.set("moderation.mode", "enforce");
        config.set("moderation.chat.scanAsyncChat", false);
        config.set("moderation.spam.enabled", false);
        config.set("moderation.offline.bannedWords", java.util.List.of("badword"));
        config.set("moderation.surfaces.sign", "monitor");

        assertEquals("monitor_local_only", InstallTelemetry.protectionState(
                ModerationSettings.from(new BukkitConfigView(config))));
    }

    @Test
    void reportsAServerWhereEverythingIsSwitchedOff() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("moderation.enabled", false);
        assertEquals("disabled",
                InstallTelemetry.protectionState(ModerationSettings.from(new BukkitConfigView(config))));
    }

    @Test
    void reportsAnInstallWithEveryCheckDisarmed() {
        assertEquals("nothing_armed", InstallTelemetry.protectionState(settings(
                "moderation.offline.enabled", "false",
                "moderation.spam.enabled", "false")));
    }

    @Test
    void distinguishesTheCloudFailuresThatNeedOppositeAnswers() {
        ModerationSettings withKey = settings("moderation.api.apiKey", "k");
        assertEquals("no_key", InstallTelemetry.cloudState(settings(), false, null));
        assertEquals("key_untested", InstallTelemetry.cloudState(withKey, false, null));
        assertEquals("key_working", InstallTelemetry.cloudState(withKey, false, "CLEAR"));
        assertEquals("key_working", InstallTelemetry.cloudState(withKey, false, "FLAGGED"));
        assertEquals("key_rejected", InstallTelemetry.cloudState(withKey, false, "CLIENT_AUTH"));
        assertEquals("no_credits", InstallTelemetry.cloudState(withKey, false, "INSUFFICIENT_CREDITS"));
        assertEquals("key_failing", InstallTelemetry.cloudState(withKey, true, "TRANSIENT_TRANSPORT"));
        assertEquals("transport_flaky", InstallTelemetry.cloudState(withKey, false, "TRANSIENT_TRANSPORT"));
    }

    @Test
    void anEndedTrialIsNotABadKey() {
        ModerationSettings withKey = settings("moderation.api.apiKey", "k");
        assertEquals("trial_ended", InstallTelemetry.cloudState(withKey, false, "CLIENT_AUTH", "TRIAL_ENDED"));
        assertEquals("trial_ended", InstallTelemetry.cloudState(withKey, false, null, "TRIAL_ENDED"),
                "known at startup, before any chat");
        assertEquals("trial_working", InstallTelemetry.cloudState(withKey, false, "CLEAR", "TRIAL_ACTIVE"));
        assertEquals("key_working", InstallTelemetry.cloudState(withKey, false, "CLEAR", "STANDARD"));
        assertEquals("key_rejected", InstallTelemetry.cloudState(withKey, false, "CLIENT_AUTH", "KEY_REJECTED"));
        assertEquals("no_credits", InstallTelemetry.cloudState(withKey, false, "INSUFFICIENT_CREDITS", "STANDARD"));
        assertEquals("no_key", InstallTelemetry.cloudState(settings(), false, null, "TRIAL_ENDED"));
    }

    @Test
    void bucketsTheChatDetectionRatePerThousandMessagesOnlyOnceThereIsChatToJudge() {
        assertEquals("too_little_chat", InstallTelemetry.chatDetectionRate(99, 50));
        assertEquals("0", InstallTelemetry.chatDetectionRate(100, 0));
        assertEquals("under_5", InstallTelemetry.chatDetectionRate(1000, 4));
        assertEquals("5_to_20", InstallTelemetry.chatDetectionRate(1000, 5));
        assertEquals("20_to_50", InstallTelemetry.chatDetectionRate(1000, 20));
        assertEquals("50_plus", InstallTelemetry.chatDetectionRate(1000, 50));
    }

    @Test
    void bucketsTheAllowListAndTheTrustSetting() {
        assertEquals("0", InstallTelemetry.allowListSize(settings()));
        YamlConfiguration twoAllowed = new YamlConfiguration();
        twoAllowed.set("moderation.offline.allowedWords", java.util.List.of("scunthorpe", "cockpit"));
        assertEquals("1_to_5", InstallTelemetry.allowListSize(ModerationSettings.from(new BukkitConfigView(twoAllowed))));
        assertEquals("off", InstallTelemetry.playerLanguageTrust(settings()));
        assertEquals("on", InstallTelemetry.playerLanguageTrust(
                settings("moderation.offline.trustPlayerLanguage", "true")));
    }

    @Test
    void countsPlayersInALanguageNeitherTheServersNorEnglish() {
        assertEquals("no_players", InstallTelemetry.playersInAnotherLanguage("en_US", java.util.List.of()));
        assertEquals("none", InstallTelemetry.playersInAnotherLanguage("fr_FR", java.util.List.of("fr_fr", "en_gb")));
        assertEquals("under_25_percent", InstallTelemetry.playersInAnotherLanguage("en_US",
                java.util.List.of("da_dk", "en_us", "en_us", "en_us", "en_us")));
        assertEquals("25_to_50_percent", InstallTelemetry.playersInAnotherLanguage("en_US",
                java.util.List.of("da_dk", "en_us", "en_us")));
        assertEquals("50_percent_plus", InstallTelemetry.playersInAnotherLanguage("en_US",
                java.util.List.of("da_dk", "nb_no", "en_us")));
    }

    @Test
    void bucketsDetectionVolumeByOrderOfMagnitude() {
        assertEquals("0", InstallTelemetry.detectionsBucket(0));
        assertEquals("1-9", InstallTelemetry.detectionsBucket(1));
        assertEquals("1-9", InstallTelemetry.detectionsBucket(9));
        assertEquals("10-99", InstallTelemetry.detectionsBucket(10));
        assertEquals("100-999", InstallTelemetry.detectionsBucket(999));
        assertEquals("1000+", InstallTelemetry.detectionsBucket(1000));
    }

    @Test
    void answersWhetherTheInstallHasEverDoneAnything() {
        assertEquals("no", InstallTelemetry.hasEverDetected(0));
        assertEquals("yes", InstallTelemetry.hasEverDetected(1));
    }

    private static ModerationSettings withWords(java.util.List<String> words) {
        YamlConfiguration config = new YamlConfiguration();
        config.set("moderation.enabled", true);
        config.set("moderation.offline.bannedWords", words);
        return ModerationSettings.from(new BukkitConfigView(config));
    }

    @Test
    void tellsTheBundledWordListApartFromAnEditedOne() {
        assertEquals("bundled_default",
                InstallTelemetry.wordListState(withWords(java.util.List.of("a", "b")), 2));
        assertEquals("extended",
                InstallTelemetry.wordListState(withWords(java.util.List.of("a", "b", "c")), 2));
        assertEquals("trimmed",
                InstallTelemetry.wordListState(withWords(java.util.List.of("a")), 2));
        assertEquals("empty",
                InstallTelemetry.wordListState(withWords(java.util.List.of()), 2));
        assertEquals("offline_disabled", InstallTelemetry.wordListState(
                settings("moderation.offline.enabled", "false"), 38));
    }

    @Test
    void reportsFoliaSeparatelyFromPaper() {
        assertEquals("Folia", InstallTelemetry.platformFamily("Paper", true));
        assertEquals("Paper", InstallTelemetry.platformFamily("Paper", false));
        assertEquals("unknown", InstallTelemetry.platformFamily("  ", false));
    }

    @Test
    void mapArtScanningReportsWhenItIsOnButUnusable() {
        assertEquals("on_but_no_key", InstallTelemetry.mapArtState(settings()));
        assertEquals("on", InstallTelemetry.mapArtState(settings("moderation.api.apiKey", "k")));
        assertEquals("off", InstallTelemetry.mapArtState(settings("moderation.mapArt.enabled", "false")));
    }

    @Test
    void chatMessagesADayAreCountedFromTheRunSoFarOnceTheServerHasBeenUpAnHour() {
        long hour = 3_600_000L;
        assertEquals("under_1_hour_up", InstallTelemetry.chatMessagesPerDay(5_000, hour - 1));
        assertEquals("0", InstallTelemetry.chatMessagesPerDay(0, hour));
        assertEquals("under_100", InstallTelemetry.chatMessagesPerDay(4, hour), "96 a day");
        assertEquals("100_to_1k", InstallTelemetry.chatMessagesPerDay(5, hour), "120 a day");
        assertEquals("1k_to_10k", InstallTelemetry.chatMessagesPerDay(2_000, 24 * hour));
        assertEquals("10k_to_50k", InstallTelemetry.chatMessagesPerDay(10_000, 24 * hour));
        assertEquals("50k_plus", InstallTelemetry.chatMessagesPerDay(50_000, 24 * hour));
    }
}
