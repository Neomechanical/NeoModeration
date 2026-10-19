package com.neomechanical.neomoderation.commands.subcommands;

import com.neomechanical.neomoderation.NeoModerationPlugin;
import com.neomechanical.neomoderation.config.BukkitConfigView;
import com.neomechanical.neomoderation.config.ModerationSettings;
import com.neomechanical.neomoderation.moderation.TrialClient;
import com.neomechanical.neomoderation.testing.TestPlugin;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TrialCmdTest {
    private static final String BILLING = "https://neomechanical.com/billing?src=neomoderation_trial_expired";
    private static final Pattern RAW_KEY = Pattern.compile("\\btrial\\.[a-z]");

    @TempDir
    Path tempDir;

    private final YamlConfiguration config = new YamlConfiguration();
    private final CommandSender sender = mock(CommandSender.class);
    private final TrialClient trialClient = mock(TrialClient.class);

    private NeoModerationPlugin plugin(String apiKey) {
        config.set("moderation.api.apiKey", apiKey);
        NeoModerationPlugin plugin = TestPlugin.with(config);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());
        when(plugin.tryStartTrialActivation()).thenReturn(true);
        Server server = mock(Server.class);
        when(server.getName()).thenReturn("Paper");
        when(server.getVersion()).thenReturn("1.21.4");
        when(plugin.getServer()).thenReturn(server);
        return plugin;
    }

    private List<String> lines() {
        return TestPlugin.lines(sender);
    }

    @Test
    void withAKeySetTrialShowsHowTheTrialIsGoingInsteadOfStartingAnother() throws Exception {
        NeoModerationPlugin plugin = plugin("existing_key_123");
        when(trialClient.fetchTrialStatus(any())).thenReturn(
                new TrialClient.TrialStatusResult(true, "active", "2026-10-22T00:00:00Z", 12, BILLING));

        new TrialCmd(plugin, trialClient).execute(sender, "nmod", new String[]{"trial"});

        verify(trialClient, never()).activateTrial(any(), any(), any());
        assertTrue(lines().contains("[NeoMod] Cloud trial until 22 October 2026. Days left: 12"),
                String.join("\n", lines()));
    }

    @Test
    void trialExplainsWhatItSendsBeforeAnythingIsSent() {
        NeoModerationPlugin plugin = plugin("");

        new TrialCmd(plugin, trialClient).execute(sender, "nmod", new String[]{"trial"});

        verifyNoInteractions(trialClient);
        String transcript = String.join("\n", lines());
        assertTrue(transcript.contains("each chat message and map picture goes to Neomechanical"), transcript);
        assertTrue(transcript.contains("Cloud decisions only alert staff until you run /nmod cloudmode enforce"),
                transcript);
        assertTrue(lines().contains("  Run /nmod trial start to start it."), transcript);
    }

    @Test
    void startingTheTrialSavesTheKeyAndLeavesModerationAsTheAdminSetIt() throws Exception {
        NeoModerationPlugin plugin = plugin("");
        when(trialClient.activateTrial(any(), any(), any()))
                .thenReturn(new TrialClient.TrialResult("nmt_trial_key_999", "2026-10-15T00:00:00Z", 14));

        new TrialCmd(plugin, trialClient).execute(sender, "nmod", new String[]{"trial", "start"});

        verify(trialClient).activateTrial(any(), any(), eq("Paper 1.21.4"));
        verify(plugin).saveAndReload();
        assertEquals(null, config.get("moderation.enabled"), "a trial never switches moderation on by itself");
        assertEquals("nmt_trial_key_999", config.get("moderation.api.apiKey"));
        assertEquals("monitor", config.get("moderation.cloudMode"));
        assertEquals(List.of(
                "[NeoMod] Starting your free 14-day cloud trial...",
                "[NeoMod] Your 14-day cloud trial is on until 15 October 2026.",
                "[NeoMod] Cloud decisions alert staff first. Run /nmod cloudmode enforce when you trust them.",
                "[NeoMod] Moderation is off, so nothing is checked yet. Run /nmod on when you are ready.",
                "  [Keep cloud moderation] https://neomechanical.com/signup?src=neomoderation"),
                lines());
    }

    @Test
    void activationDoesNotOverwriteAKeyConfiguredDuringTheRequest() throws Exception {
        NeoModerationPlugin plugin = plugin("");
        when(trialClient.activateTrial(any(), any(), any())).thenAnswer(call -> {
            config.set("moderation.api.apiKey", "owner-configured-key");
            when(plugin.settings()).thenReturn(ModerationSettings.from(new BukkitConfigView(config)));
            return new TrialClient.TrialResult("nmt_trial_key_999", "2026-10-15T00:00:00Z", 14);
        });

        new TrialCmd(plugin, trialClient).execute(sender, "nmod", new String[]{"trial", "start"});

        assertEquals("owner-configured-key", config.get("moderation.api.apiKey"));
        verify(plugin, never()).saveAndReload();
        assertTrue(lines().get(lines().size() - 1).startsWith("[NeoMod] The cloud settings changed while the trial"),
                String.join("\n", lines()));
        verify(plugin).finishTrialActivation();
    }

    @Test
    void concurrentActivationDoesNotStartAnotherRequest() {
        NeoModerationPlugin plugin = plugin("");
        when(plugin.tryStartTrialActivation()).thenReturn(false);

        new TrialCmd(plugin, trialClient).execute(sender, "nmod", new String[]{"trial", "start"});

        verifyNoInteractions(trialClient);
        assertEquals(List.of("[NeoMod] A trial is already starting. Wait for it to finish."), lines());
    }

    @Test
    void alreadyClaimedShowsBillingUrl() throws Exception {
        NeoModerationPlugin plugin = plugin("");
        when(trialClient.activateTrial(any(), any(), any()))
                .thenThrow(new TrialClient.TrialException(TrialClient.TrialError.ALREADY_CLAIMED, "claimed"));

        new TrialCmd(plugin, trialClient).execute(sender, "nmod", new String[]{"trial", "start"});

        String last = lines().get(lines().size() - 1);
        assertTrue(last.startsWith("[NeoMod] This server already used its free trial. Get a key at https://neomechanical.com/billing"),
                last);
        verify(plugin).finishTrialActivation();
    }

    @Test
    void rateLimitedShowsRateLimitMessage() throws Exception {
        NeoModerationPlugin plugin = plugin("");
        when(trialClient.activateTrial(any(), any(), any()))
                .thenThrow(new TrialClient.TrialException(TrialClient.TrialError.RATE_LIMITED, "rate limit"));

        new TrialCmd(plugin, trialClient).execute(sender, "nmod", new String[]{"trial", "start"});

        assertTrue(lines().get(lines().size() - 1).startsWith("[NeoMod] Too many trials started from this network today."),
                String.join("\n", lines()));
    }

    @Test
    void otherFailuresSayWhatWentWrongInWords() throws Exception {
        NeoModerationPlugin plugin = plugin("");
        when(trialClient.activateTrial(any(), any(), any()))
                .thenThrow(new TrialClient.TrialException(TrialClient.TrialError.TRANSPORT_ERROR, "timeout"));

        new TrialCmd(plugin, trialClient).execute(sender, "nmod", new String[]{"trial", "start"});

        assertEquals("[NeoMod] The trial didn't start: Neomechanical could not be reached.",
                lines().get(lines().size() - 1));
    }

    @Test
    void everyTrialErrorHasWords() throws Exception {
        for (TrialClient.TrialError error : TrialClient.TrialError.values()) {
            CommandSender reader = mock(CommandSender.class);
            NeoModerationPlugin plugin = plugin("");
            // doThrow, because when() would call the mock and meet the previous round's exception.
            doThrow(new TrialClient.TrialException(error, "test")).when(trialClient).activateTrial(any(), any(), any());

            new TrialCmd(plugin, trialClient).execute(reader, "nmod", new String[]{"trial", "start"});

            String transcript = TestPlugin.transcript(reader);
            assertFalse(RAW_KEY.matcher(transcript).find(), error + ": " + transcript);
        }
    }

    @Test
    void hmacGenerationIsDeterministicAndNonEmpty() {
        String hmac1 = TrialClient.computeHmac("11111111-2222-3333-4444-555555555555", 1700000000L);
        String hmac2 = TrialClient.computeHmac("11111111-2222-3333-4444-555555555555", 1700000000L);
        String hmacDiff = TrialClient.computeHmac("11111111-2222-3333-4444-555555555555", 1700000001L);

        assertEquals(64, hmac1.length());
        assertEquals(hmac1, hmac2);
        assertNotEquals(hmac1, hmacDiff);
    }

    @Test
    void statusSubcommandReportsActiveTrial() throws Exception {
        NeoModerationPlugin plugin = plugin("nmt_trial_key_999");
        when(trialClient.fetchTrialStatus(any())).thenReturn(
                new TrialClient.TrialStatusResult(true, "active", "2026-10-22T00:00:00Z", 12, BILLING));

        new TrialCmd(plugin, trialClient).execute(sender, "nmod", new String[]{"trial", "status"});

        assertEquals(List.of(
                "[NeoMod] Checking the cloud trial...",
                "[NeoMod] Cloud trial until 22 October 2026. Days left: 12",
                "[NeoMod] Caught by the cloud since startup: 0",
                "  [Keep cloud moderation] " + BILLING), lines());
    }

    @Test
    void statusSubcommandReportsExpiredTrial() throws Exception {
        NeoModerationPlugin plugin = plugin("nmt_trial_key_999");
        when(trialClient.fetchTrialStatus(any())).thenReturn(
                new TrialClient.TrialStatusResult(true, "expired", "2026-10-01T00:00:00Z", 0, BILLING));

        new TrialCmd(plugin, trialClient).execute(sender, "nmod", new String[]{"trial", "status"});

        assertEquals(List.of(
                "[NeoMod] Checking the cloud trial...",
                "[NeoMod] The cloud trial ended on 1 October 2026. Local rules still run.",
                "  [Keep cloud moderation] " + BILLING), lines());
    }

    @Test
    void statusSubcommandPromptsForTrialWhenNoKeyConfigured() {
        NeoModerationPlugin plugin = plugin("");

        new TrialCmd(plugin, trialClient).execute(sender, "nmod", new String[]{"trial", "status"});

        assertEquals(List.of("[NeoMod] No cloud key yet. Run /nmod trial for a free 14-day trial."), lines());
        verifyNoInteractions(trialClient);
    }
}
