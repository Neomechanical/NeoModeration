package com.neomechanical.neomoderation.commands.subcommands;

import com.neomechanical.neomoderation.NeoModerationPlugin;
import com.neomechanical.neomoderation.config.BukkitConfigView;
import com.neomechanical.neomoderation.config.ModerationSettings;
import com.neomechanical.neomoderation.moderation.CloudPlanWatch;
import com.neomechanical.neomoderation.moderation.ModerationApiResult;
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
import static org.mockito.ArgumentMatchers.anyBoolean;
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

        verify(trialClient, never()).activate(any(), any(), any(), anyBoolean());
        assertTrue(lines().contains("[NeoMod] Cloud trial until 22 October 2026. Days left: 12"),
                String.join("\n", lines()));
    }

    @Test
    void trialExplainsWhatItSendsBeforeAnythingIsSent() {
        NeoModerationPlugin plugin = plugin("");

        new TrialCmd(plugin, trialClient).execute(sender, "nmod", new String[]{"trial"});

        verifyNoInteractions(trialClient);
        String transcript = String.join("\n", lines());
        assertTrue(transcript.contains("Each chat message and map picture goes to Neomechanical"), transcript);
        assertTrue(transcript.contains("Starting accepts the terms at https://neomechanical.com/terms"), transcript);
        assertTrue(transcript.contains("Cloud decisions only alert staff until you run /nmod cloudmode enforce"),
                transcript);
        assertTrue(lines().contains("  Run /nmod trial start to begin."), transcript);
    }

    @Test
    void startingTheTrialSavesTheKeyAndLeavesModerationAsTheAdminSetIt() throws Exception {
        NeoModerationPlugin plugin = plugin("");
        when(trialClient.activate(any(), any(), any(), anyBoolean()))
                .thenReturn(new TrialClient.TrialResult("nmt_trial_key_999", "2026-10-15T00:00:00Z", 14));

        new TrialCmd(plugin, trialClient).execute(sender, "nmod", new String[]{"trial", "start"});

        verify(trialClient).activate(any(), any(), eq("Paper 1.21.4"), eq(false));
        verify(plugin).editConfig(any());
        assertEquals(null, config.get("moderation.enabled"), "a trial never switches moderation on by itself");
        assertEquals("nmt_trial_key_999", config.get("moderation.api.apiKey"));
        assertEquals("monitor", config.get("moderation.cloudMode"));
        assertEquals(List.of(
                "[NeoMod] Starting free cloud checks...",
                "[NeoMod] Your 14-day cloud trial is on until 15 October 2026.",
                "[NeoMod] Cloud decisions alert staff first. Run /nmod cloudmode enforce when you trust them.",
                "[NeoMod] Moderation is off, so nothing is checked yet. Run /nmod on when you are ready.",
                KEEP_IN_GAME), lines());
    }

    @Test
    void activationDoesNotOverwriteAKeyConfiguredDuringTheRequest() throws Exception {
        NeoModerationPlugin plugin = plugin("");
        when(trialClient.activate(any(), any(), any(), anyBoolean())).thenAnswer(call -> {
            config.set("moderation.api.apiKey", "owner-configured-key");
            when(plugin.settings()).thenReturn(ModerationSettings.from(new BukkitConfigView(config)));
            return new TrialClient.TrialResult("nmt_trial_key_999", "2026-10-15T00:00:00Z", 14);
        });

        new TrialCmd(plugin, trialClient).execute(sender, "nmod", new String[]{"trial", "start"});

        assertEquals("owner-configured-key", config.get("moderation.api.apiKey"));
        assertEquals(null, config.get("moderation.cloudMode"), "nothing of the trial was written");
        assertTrue(lines().get(lines().size() - 1).startsWith("[NeoMod] The cloud settings changed while cloud checks started"),
                String.join("\n", lines()));
        verify(plugin).finishTrialActivation();
    }

    @Test
    void concurrentActivationDoesNotStartAnotherRequest() {
        NeoModerationPlugin plugin = plugin("");
        when(plugin.tryStartTrialActivation()).thenReturn(false);

        new TrialCmd(plugin, trialClient).execute(sender, "nmod", new String[]{"trial", "start"});

        verifyNoInteractions(trialClient);
        assertEquals(List.of("[NeoMod] Cloud checks are already starting. Wait for them to finish."), lines());
    }

    @Test
    void alreadyClaimedSendsTheAdminToSignUpNotToBilling() throws Exception {
        NeoModerationPlugin plugin = plugin("");
        when(trialClient.activate(any(), any(), any(), anyBoolean()))
                .thenThrow(new TrialClient.TrialException(TrialClient.TrialError.ALREADY_CLAIMED, "claimed"));

        new TrialCmd(plugin, trialClient).execute(sender, "nmod", new String[]{"trial", "start"});

        String last = lines().get(lines().size() - 1);
        assertEquals("[NeoMod] This server already used its free trial. Create a free account at "
                + "https://neomechanical.com/signup?src=neomoderation for a key.", last,
                "an install that used its trial has no account to sign in to");
        verify(plugin).finishTrialActivation();
    }

    @Test
    void rateLimitedShowsRateLimitMessage() throws Exception {
        NeoModerationPlugin plugin = plugin("");
        when(trialClient.activate(any(), any(), any(), anyBoolean()))
                .thenThrow(new TrialClient.TrialException(TrialClient.TrialError.RATE_LIMITED, "rate limit"));

        new TrialCmd(plugin, trialClient).execute(sender, "nmod", new String[]{"trial", "start"});

        assertTrue(lines().get(lines().size() - 1).startsWith("[NeoMod] Too many trials started from this network today."),
                String.join("\n", lines()));
    }

    @Test
    void otherFailuresSayWhatWentWrongInWords() throws Exception {
        NeoModerationPlugin plugin = plugin("");
        when(trialClient.activate(any(), any(), any(), anyBoolean()))
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
            doThrow(new TrialClient.TrialException(error, "test")).when(trialClient).activate(any(), any(), any(), anyBoolean());

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
                "[NeoMod] Checking the cloud plan...",
                "[NeoMod] Cloud trial until 22 October 2026. Days left: 12",
                KEEP_IN_GAME), lines(), "nothing caught yet says nothing");
    }

    @Test
    void statusSubcommandReportsExpiredTrial() throws Exception {
        NeoModerationPlugin plugin = plugin("nmt_trial_key_999");
        when(trialClient.fetchTrialStatus(any())).thenReturn(
                new TrialClient.TrialStatusResult(true, "expired", "2026-10-01T00:00:00Z", 0, BILLING));

        new TrialCmd(plugin, trialClient).execute(sender, "nmod", new String[]{"trial", "status"});

        assertEquals(List.of(
                "[NeoMod] Checking the cloud plan...",
                "[NeoMod] The cloud trial ended on 1 October 2026. Local rules still run. To keep cloud checks, "
                        + "create a free account, then a key, and run /nmod setup <key>.",
                "  [Create a free account] https://neomechanical.com/signup?src=neomoderation"), lines());
    }

    @Test
    void statusSubcommandPromptsForTrialWhenNoKeyConfigured() {
        NeoModerationPlugin plugin = plugin("");

        new TrialCmd(plugin, trialClient).execute(sender, "nmod", new String[]{"trial", "status"});

        assertEquals(List.of("[NeoMod] No cloud key yet. Run /nmod trial for free cloud checks."), lines());
        verifyNoInteractions(trialClient);
    }

    @Test
    void statusSaysARevokedTrialWasStopped() throws Exception {
        NeoModerationPlugin plugin = plugin("nmt_trial_key_999");
        when(trialClient.fetchTrialStatus(any())).thenReturn(
                new TrialClient.TrialStatusResult(true, "revoked", "2026-10-01T00:00:00Z", 0, BILLING));

        new TrialCmd(plugin, trialClient).execute(sender, "nmod", new String[]{"trial", "status"});

        assertEquals(List.of(
                "[NeoMod] Checking the cloud plan...",
                "[NeoMod] The cloud trial was stopped. Local rules still run. To keep cloud checks, create a free "
                        + "account, then a key, and run /nmod setup <key>.",
                "  [Create a free account] https://neomechanical.com/signup?src=neomoderation"), lines());
    }

    @Test
    void theStatusItFetchesIsWhatTheRestOfThePluginKnows() throws Exception {
        NeoModerationPlugin plugin = plugin("nmt_trial_key_999");
        // The test plugin's watch never sends its own questions, so only the status the command fetched can tell it.
        when(trialClient.fetchTrialStatus(any())).thenReturn(
                new TrialClient.TrialStatusResult(true, "expired", "2026-10-01T00:00:00Z", 0, BILLING));

        new TrialCmd(plugin, trialClient).execute(sender, "nmod", new String[]{"trial", "status"});

        assertEquals(CloudPlanWatch.State.TRIAL_ENDED, plugin.cloudPlan().state());
    }

    @Test
    void aStatusTheServiceRefusesSaysTheKeyWasRejectedAndTellsThePlugin() throws Exception {
        NeoModerationPlugin plugin = plugin("nmt_trial_key_999");
        when(trialClient.fetchTrialStatus(any()))
                .thenThrow(new TrialClient.TrialException(TrialClient.TrialError.FORBIDDEN, "API key rejected"));

        new TrialCmd(plugin, trialClient).execute(sender, "nmod", new String[]{"trial", "status"});

        assertEquals(List.of(
                "[NeoMod] Checking the cloud plan...",
                "[NeoMod] Could not read the cloud trial: the key was rejected."), lines(),
                "nothing was started, so it is not \"the trial didn't start\"");
        assertEquals(CloudPlanWatch.State.KEY_REJECTED, plugin.cloudPlan().state());
    }

    @Test
    void aRunningTrialThatRanOutOfCreditsIsToldToKeepItBeforeAddingAnyAndTheConsoleNeverGetsItsLink() throws Exception {
        // The console's lines are the server log, and whoever opens the claim link first owns the trial: 1.7.0 printed
        // it there.
        NeoModerationPlugin plugin = plugin("nmt_trial_key_999");
        plugin.cloudPlan().refused(ModerationApiResult.Kind.INSUFFICIENT_CREDITS);
        when(trialClient.fetchTrialStatus(any())).thenReturn(
                new TrialClient.TrialStatusResult(true, "active", "2026-10-22T00:00:00Z", 12, CLAIM));

        new TrialCmd(plugin, trialClient).execute(sender, "nmod", new String[]{"trial", "status"});

        assertEquals(List.of(
                "[NeoMod] Checking the cloud plan...",
                "[NeoMod] Cloud trial until 22 October 2026. Days left: 12",
                "[NeoMod] The trial's credits are used up. Keep it in a free account first, then add credits there.",
                KEEP_IN_GAME), lines());
        assertTrue(lines().stream().noneMatch(line -> line.contains("trial=")), String.join("\n", lines()));
    }

    @Test
    void startingTheTrialFromTheConsoleNeverPrintsItsClaimLink() throws Exception {
        NeoModerationPlugin plugin = plugin("");
        when(trialClient.activate(any(), any(), any(), anyBoolean()))
                .thenReturn(new TrialClient.TrialResult("nmt_trial_key_999", "2026-10-15T00:00:00Z", 14, CLAIM));

        new TrialCmd(plugin, trialClient).execute(sender, "nmod", new String[]{"trial", "start"});

        assertTrue(lines().stream().noneMatch(line -> line.contains("trial=")), String.join("\n", lines()));
        assertEquals(KEEP_IN_GAME, lines().get(lines().size() - 1));
    }

    @Test
    void anAdminInGameGetsTheClaimLinkAsAButtonOnly() throws Exception {
        NeoModerationPlugin plugin = plugin("nmt_trial_key_999");
        org.bukkit.entity.Player admin = TestPlugin.player("Ana", "en_us");
        org.bukkit.entity.Player.Spigot spigot = mock(org.bukkit.entity.Player.Spigot.class);
        when(admin.spigot()).thenReturn(spigot);
        when(trialClient.fetchTrialStatus(any())).thenReturn(
                new TrialClient.TrialStatusResult(true, "active", "2026-10-22T00:00:00Z", 12, CLAIM));

        new TrialCmd(plugin, trialClient).execute(admin, "nmod", new String[]{"trial", "status"});

        org.mockito.ArgumentCaptor<net.md_5.bungee.api.chat.BaseComponent[]> sent =
                org.mockito.ArgumentCaptor.forClass(net.md_5.bungee.api.chat.BaseComponent[].class);
        verify(admin.spigot()).sendMessage(sent.capture());
        assertEquals(List.of(CLAIM), clicks(sent.getValue()), "the button opens the claim link");
        assertTrue(TestPlugin.lines(admin).stream().noneMatch(line -> line.contains("trial=")),
                "and no chat line spells it out");
    }

    private static List<String> clicks(net.md_5.bungee.api.chat.BaseComponent[] components) {
        List<String> urls = new java.util.ArrayList<>();
        for (net.md_5.bungee.api.chat.BaseComponent component : components) {
            collectClicks(component, urls);
        }
        return urls;
    }

    private static void collectClicks(net.md_5.bungee.api.chat.BaseComponent component, List<String> urls) {
        if (component.getClickEvent() != null) {
            urls.add(component.getClickEvent().getValue());
        }
        if (component.getExtra() != null) {
            component.getExtra().forEach(extra -> collectClicks(extra, urls));
        }
    }

    private static final String CLAIM = "https://neomechanical.com/signup?trial=t&src=neomoderation_trial";
    private static final String KEEP_IN_GAME = "  To keep it in an account, run /nmod trial status in game and click [Keep cloud moderation]. Whoever opens its "
                        + "link first owns it, so the server log never shows it.";
}
