package com.neomechanical.neomoderation.commands.subcommands;

import com.neomechanical.neomoderation.config.ModerationMode;
import com.neomechanical.neomoderation.config.BukkitConfigView;
import com.neomechanical.neomoderation.config.ModerationSettings;
import com.neomechanical.neomoderation.moderation.DetectionHandler;
import com.neomechanical.neomoderation.moderation.CloudRecovery;
import com.neomechanical.neomoderation.moderation.ModerationApiResult;
import com.neomechanical.neomoderation.moderation.NeoMechanicalUsageClient;
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
