package com.neomechanical.neomoderation.commands.subcommands;

import com.neomechanical.neomoderation.config.ModerationMode;
import com.neomechanical.neomoderation.config.BukkitConfigView;
import com.neomechanical.neomoderation.config.ModerationSettings;
import com.neomechanical.neomoderation.moderation.DetectionHandler;
import com.neomechanical.neomoderation.moderation.CloudPlanWatch;
import com.neomechanical.neomoderation.moderation.CloudRecovery;
import com.neomechanical.neomoderation.moderation.ModerationApiResult;
import com.neomechanical.neomoderation.moderation.NeoMechanicalUsageClient;
import com.neomechanical.neomoderation.moderation.TrialClient;
import org.junit.jupiter.api.Test;
import org.bukkit.configuration.file.YamlConfiguration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestCmdDecisionTest {
    @Test
    void cleanMessagesAreAllowedAndDetectionsRespectTheActiveMode() {
        assertEquals(TestCmd.Result.ALLOWED, TestCmd.outcome(true, false, ModerationMode.ENFORCE, false));
        assertEquals(TestCmd.Result.ALLOWED, TestCmd.outcome(true, false, ModerationMode.MONITOR, false));
        assertEquals(TestCmd.Result.ENFORCED, TestCmd.outcome(true, true, ModerationMode.ENFORCE, false));
        assertEquals(TestCmd.Result.MONITORED, TestCmd.outcome(true, true, ModerationMode.MONITOR, false));
        assertEquals(TestCmd.Result.ALLOWED, TestCmd.outcome(false, true, ModerationMode.ENFORCE, false));
        assertEquals(TestCmd.Result.CENSORED, TestCmd.outcome(true, true, ModerationMode.ENFORCE, true));
        assertEquals(TestCmd.Result.MONITORED, TestCmd.outcome(true, true, ModerationMode.MONITOR, true));
    }

    @Test
    void previewUsesTheModeOfTheEngineThatFlaggedTheMessage() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("moderation.enabled", true);
        config.set("moderation.mode", "enforce");
        config.set("moderation.cloudMode", "monitor");
        ModerationSettings settings = ModerationSettings.from(new BukkitConfigView(config));

        assertEquals(TestCmd.Result.ENFORCED,
                TestCmd.outcome(settings, true, DetectionHandler.Source.LOCAL, false));
        assertEquals(TestCmd.Result.MONITORED,
                TestCmd.outcome(settings, true, DetectionHandler.Source.CLOUD, false));
    }

    @Test
    void testAndStatusChooseSpecificRecoveryMessages() {
        assertEquals("test.cloud-auth", TestCmd.cloudMessageKey(ModerationApiResult.Kind.CLIENT_AUTH));
        assertEquals("test.cloud-credits",
                TestCmd.cloudMessageKey(ModerationApiResult.Kind.INSUFFICIENT_CREDITS));
        assertEquals("test.cloud-request-error",
                TestCmd.cloudMessageKey(ModerationApiResult.Kind.CLIENT_REQUEST));

        assertEquals("status.cloud-unverified", StatusCmd.cloudStatusKey(null));
        assertEquals("status.cloud-auth", StatusCmd.cloudStatusKey(ModerationApiResult.Kind.CLIENT_AUTH));
        assertEquals("status.cloud-credits",
                StatusCmd.cloudStatusKey(ModerationApiResult.Kind.INSUFFICIENT_CREDITS));
    }

    @Test
    void statusNamesARefusalByItsCauseAndSaysNothingTwiceAfterARestart() {
        CloudPlanWatch.Snapshot ended = new CloudPlanWatch.Snapshot(CloudPlanWatch.State.TRIAL_ENDED,
                new TrialClient.TrialStatusResult(true, "expired", "2026-10-01T00:00:00Z", 0, "https://signup"), null);
        CloudPlanWatch.Snapshot running = new CloudPlanWatch.Snapshot(CloudPlanWatch.State.TRIAL_ACTIVE,
                new TrialClient.TrialStatusResult(true, "active", "2026-10-30T00:00:00Z", 9, "https://claim"), null);
        // After a restart no check has run: the plan line already says the trial ended, so no last-check line.
        assertEquals(null, StatusCmd.cloudStatusKey(null, ended));
        assertEquals("status.cloud-key-rejected", StatusCmd.cloudStatusKey(null,
                new CloudPlanWatch.Snapshot(CloudPlanWatch.State.KEY_REJECTED, null, null)),
                "a key the trial service does not know is said to be rejected, and no check is said to have run");
        assertEquals("status.cloud-unverified", StatusCmd.cloudStatusKey(null, running));
        // The plan line says why the trial's checks are refused; the last check only says they were.
        assertEquals("status.cloud-refused", StatusCmd.cloudStatusKey(ModerationApiResult.Kind.CLIENT_AUTH, ended));
        assertEquals("status.cloud-refused",
                StatusCmd.cloudStatusKey(ModerationApiResult.Kind.INSUFFICIENT_CREDITS, running));
        assertEquals("status.cloud-auth", StatusCmd.cloudStatusKey(ModerationApiResult.Kind.CLIENT_AUTH, running),
                "a running trial whose key is refused has no plan line saying why");
        assertEquals("status.cloud-ready", StatusCmd.cloudStatusKey(ModerationApiResult.Kind.CLEAR, ended),
                "a check that worked is said as such; the watch then asks again");
    }

    @Test
    void doctorSendsAuthenticationAndCreditFailuresToDifferentRecoveryPages() {
        NeoMechanicalUsageClient.UsageException auth = new NeoMechanicalUsageClient.UsageException(
                ModerationApiResult.Kind.CLIENT_AUTH, "HTTP 401");
        NeoMechanicalUsageClient.UsageException credits = new NeoMechanicalUsageClient.UsageException(
                ModerationApiResult.Kind.INSUFFICIENT_CREDITS, "HTTP 402");

        DoctorChecks.Check authCheck = DoctorChecks.accountFailure(auth, 10);
        DoctorChecks.Check creditsCheck = DoctorChecks.accountFailure(credits, 10);

        assertEquals("account", authCheck.check());
        assertEquals("credits", creditsCheck.check());
        assertEquals(CloudRecovery.API_KEYS_URL, authCheck.values().get("url"));
        assertEquals(CloudRecovery.BILLING_URL, creditsCheck.values().get("url"));
        assertEquals(DoctorChecks.Level.FAIL, authCheck.level());
        assertEquals(DoctorChecks.Level.FAIL, creditsCheck.level());
    }

    @Test
    void usageUsesTheSameSpecificFailureClassification() {
        assertEquals("usage.error-auth", UsageCmd.usageErrorKey(ModerationApiResult.Kind.CLIENT_AUTH));
        assertEquals("usage.error-credits",
                UsageCmd.usageErrorKey(ModerationApiResult.Kind.INSUFFICIENT_CREDITS));
        assertEquals("usage.error-request",
                UsageCmd.usageErrorKey(ModerationApiResult.Kind.CLIENT_REQUEST));
    }
}
