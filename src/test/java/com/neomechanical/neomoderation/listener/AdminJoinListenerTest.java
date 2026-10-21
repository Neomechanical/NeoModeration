package com.neomechanical.neomoderation.listener;

import com.neomechanical.neomoderation.NeoModerationPlugin;
import com.neomechanical.neomoderation.Permissions;
import com.neomechanical.neomoderation.messages.MessageService;
import com.neomechanical.neomoderation.moderation.ModerationApiResult;
import com.neomechanical.neomoderation.moderation.TrialClient;
import com.neomechanical.neomoderation.testing.TestPlugin;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminJoinListenerTest {
    private static final List<String> WELCOME = List.of(
            "[NeoMod] NeoModeration blocks swearing, slurs, scam links, server ads and spam. "
                    + "It does not mute or kick anyone unless you set that up.",
            "  [Try a message]",
            "  [Let players share your server's address]",
            "  [Check signs, books and commands too]",
            "  [Free 14-day cloud trial]");

    @TempDir
    Path tempDir;

    private NeoModerationPlugin plugin(boolean enabled, String apiKey) {
        YamlConfiguration config = new YamlConfiguration();
        config.set("moderation.enabled", enabled);
        config.set("moderation.api.apiKey", apiKey);
        NeoModerationPlugin plugin = TestPlugin.with(config);
        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());
        return plugin;
    }

    private static Player player(boolean admin) {
        Player player = mock(Player.class);
        when(player.hasPermission(Permissions.ADMIN)).thenReturn(admin);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.isOnline()).thenReturn(true);
        return player;
    }

    private static void join(AdminJoinListener listener, Player player) {
        PlayerJoinEvent event = mock(PlayerJoinEvent.class);
        when(event.getPlayer()).thenReturn(player);
        listener.onPlayerJoin(event);
    }

    private File marker() {
        return new File(tempDir.toFile(), AdminJoinListener.MARKER_FILE);
    }

    @Test
    void firstJoinByAdminWithoutApiKeySendsWelcomeAndCreatesMarker() {
        Player admin = player(true);

        join(new AdminJoinListener(plugin(true, "")), admin);

        assertTrue(marker().exists(), "The marker file must be created on first admin join");
        assertEquals(WELCOME, TestPlugin.lines(admin));
    }

    @Test
    void theCardIsShownOncePerInstallEvenAcrossRestarts() {
        Player admin = player(true);
        NeoModerationPlugin plugin = plugin(true, "");

        join(new AdminJoinListener(plugin), admin);
        join(new AdminJoinListener(plugin), admin);

        assertEquals(WELCOME, TestPlugin.lines(admin));
    }

    @Test
    void nonAdminPlayerDoesNotTriggerWelcomeNorCreateMarker() {
        Player regular = player(false);

        join(new AdminJoinListener(plugin(true, "")), regular);

        assertFalse(marker().exists(), "Marker must not be created for non-admin players");
        assertTrue(TestPlugin.lines(regular).isEmpty());
    }

    @Test
    void serverWithApiKeyConfiguredDoesNotTriggerWelcomeNorCreateMarker() {
        Player admin = player(true);

        join(new AdminJoinListener(plugin(true, "nm_live_12345")), admin);

        assertFalse(marker().exists(), "Marker must not be created when an API key is already present");
        assertTrue(TestPlugin.lines(admin).isEmpty());
    }

    @Test
    void anAdminHearsAboutANewerReleaseOncePerServerStart() {
        NeoModerationPlugin plugin = plugin(true, "nm_live_12345");
        when(plugin.newerVersion()).thenReturn("9.9.9");
        AdminJoinListener listener = new AdminJoinListener(plugin);
        Player admin = player(true);

        join(listener, admin);
        join(listener, admin);

        assertEquals(List.of("[NeoMod] NeoModeration 9.9.9 is out; this server runs " + TestPlugin.VERSION
                + ". [Download]"), TestPlugin.lines(admin));
    }

    @Test
    void playersWithoutAdminNeverHearAboutUpdates() {
        NeoModerationPlugin plugin = plugin(true, "");
        when(plugin.newerVersion()).thenReturn("9.9.9");
        Player regular = player(false);

        join(new AdminJoinListener(plugin), regular);

        assertTrue(TestPlugin.lines(regular).isEmpty());
    }

    @Test
    void disabledPluginDoesNotTriggerWelcome() {
        Player admin = player(true);

        join(new AdminJoinListener(plugin(false, "")), admin);

        assertFalse(marker().exists());
        assertTrue(TestPlugin.lines(admin).isEmpty());
    }

    /** A plugin with a key whose trial service answered {@code status}, then refused it {@code refused}. */
    private NeoModerationPlugin pluginWithPlan(TrialClient.TrialStatusResult status, ModerationApiResult.Kind... refused) {
        NeoModerationPlugin plugin = plugin(true, "nm_key");
        TestPlugin.plan(plugin, status, refused);
        return plugin;
    }

    @Test
    void anAdminHearsOnceThatTheTrialEndsSoonWithTheLinkToKeepIt() {
        NeoModerationPlugin plugin = pluginWithPlan(new TrialClient.TrialStatusResult(true, "active",
                "2026-10-22T00:00:00Z", 9, "https://neomechanical.com/signup?trial=t&src=neomoderation_trial"));
        Player admin = player(true);

        join(new AdminJoinListener(plugin), admin);
        join(new AdminJoinListener(plugin), admin);

        assertEquals(List.of(
                "[NeoMod] Your free cloud trial ends on 22 October 2026. Keep it in a free account:",
                "  [Keep cloud moderation]"), TestPlugin.lines(admin), "two days left by the clock, whatever was sent");
        assertTrue(new File(tempDir.toFile(), ".join-trial-ending").exists());
    }

    @Test
    void anAdminHearsOnceThatTheTrialEndedAndWhatToDoNext() {
        NeoModerationPlugin plugin = pluginWithPlan(new TrialClient.TrialStatusResult(true, "expired",
                "2026-10-22T00:00:00Z", 0, "https://neomechanical.com/signup?src=neomoderation_trial"));
        Player admin = player(true);

        join(new AdminJoinListener(plugin), admin);
        join(new AdminJoinListener(plugin), admin);

        assertEquals(List.of(
                "[NeoMod] The free cloud trial ended on 22 October 2026. Local rules still run. "
                        + "To keep cloud checks, create a free account, then a key, and run /nmod setup <key>.",
                "  [Create a free account]"), TestPlugin.lines(admin));
    }

    @Test
    void aTrialWhoseEndDatePassedSinceTheLastAnswerWaitsForTheServiceBeforeAnyNotice() {
        // An answer from two days before the end, and the first admin joins after it: the trial may have been kept
        // in its last hour, and each notice is once per install, so neither "ending" nor "ended" is used up yet.
        NeoModerationPlugin plugin = pluginWithPlan(new TrialClient.TrialStatusResult(true, "active",
                "2026-10-19T00:00:00Z", 2, "https://neomechanical.com/signup?trial=t&src=neomoderation_trial"));
        Player admin = player(true);

        join(new AdminJoinListener(plugin), admin);

        assertEquals(List.of(), TestPlugin.lines(admin));
        assertFalse(new File(tempDir.toFile(), ".join-trial-ending").exists());
        assertFalse(new File(tempDir.toFile(), ".join-trial-ended").exists());
    }

    @Test
    void anAdminWhoLeftBeforeTheNoticeArrivedLeavesItForTheNextAdmin() {
        NeoModerationPlugin plugin = pluginWithPlan(new TrialClient.TrialStatusResult(true, "expired",
                "2026-10-19T00:00:00Z", 0, "https://neomechanical.com/signup?src=neomoderation_trial"));
        Player gone = player(true);
        when(gone.isOnline()).thenReturn(false);
        Player next = player(true);

        join(new AdminJoinListener(plugin), gone);
        assertFalse(new File(tempDir.toFile(), ".join-trial-ended").exists(), "the marker is given back");
        join(new AdminJoinListener(plugin), next);

        assertEquals(List.of(), TestPlugin.lines(gone));
        assertEquals(2, TestPlugin.lines(next).size());
        assertTrue(new File(tempDir.toFile(), ".join-trial-ended").exists());
    }

    @Test
    void aNoticeFoliaDroppedBecauseTheAdminLeftIsLeftForTheNextAdmin() {
        // Folia runs the retired work instead when the player left before their task ran (PlatformScheduler).
        NeoModerationPlugin plugin = pluginWithPlan(new TrialClient.TrialStatusResult(true, "expired",
                "2026-10-19T00:00:00Z", 0, "https://neomechanical.com/signup?src=neomoderation_trial"));
        doAnswer(call -> {
            call.<Runnable>getArgument(2).run();
            return null;
        }).when(plugin).runForEntity(any(), any(Runnable.class), any());
        Player gone = player(true);

        join(new AdminJoinListener(plugin), gone);

        assertEquals(List.of(), TestPlugin.lines(gone));
        assertFalse(new File(tempDir.toFile(), ".join-trial-ended").exists(), "the marker is given back");
    }

    @Test
    void aWelcomeCardForAnAdminWhoLeftIsLeftForTheNextAdmin() {
        AdminJoinListener listener = new AdminJoinListener(plugin(true, ""));
        Player gone = player(true);
        when(gone.isOnline()).thenReturn(false);
        Player next = player(true);

        join(listener, gone);
        assertFalse(marker().exists(), "the marker is given back");
        join(listener, next);

        assertEquals(List.of(), TestPlugin.lines(gone));
        assertEquals(WELCOME, TestPlugin.lines(next));
        assertTrue(marker().exists());
    }

    @Test
    void whatTheCloudCaughtIsSaidOnlyWhenThereIsSomethingToSay() {
        Player reader = mock(Player.class);
        MessageService messages = TestPlugin.english();
        assertEquals("", AdminJoinListener.caughtClause(messages, reader, 0, 0));
        assertEquals("Caught by the cloud during the trial: 12. ", AdminJoinListener.caughtClause(messages, reader, 12, 3));
        assertEquals("Caught by the cloud since startup: 3. ", AdminJoinListener.caughtClause(messages, reader, -1, 3),
                "without the case history only this run is known");
    }

    @Test
    void anAdminHearsOnceThatARunningTrialUsedItsCreditsWithTheLinkToKeepIt() {
        NeoModerationPlugin plugin = pluginWithPlan(new TrialClient.TrialStatusResult(true, "active",
                "2026-10-30T00:00:00Z", 10, "https://neomechanical.com/signup?trial=t&src=neomoderation_trial"),
                ModerationApiResult.Kind.INSUFFICIENT_CREDITS);
        Player admin = player(true);

        join(new AdminJoinListener(plugin), admin);
        join(new AdminJoinListener(plugin), admin);

        assertEquals(List.of(
                "[NeoMod] The free cloud trial's credits are used up. Local rules still run. Keep the trial in a free "
                        + "account to add more:",
                "  [Keep cloud moderation]"), TestPlugin.lines(admin));
        assertTrue(new File(tempDir.toFile(), ".join-trial-credits").exists());
    }

    @Test
    void aStoppedTrialIsToldWithoutTheDateItWasMeantToEnd() {
        NeoModerationPlugin plugin = pluginWithPlan(new TrialClient.TrialStatusResult(true, "revoked",
                "2026-10-30T00:00:00Z", 0, "https://neomechanical.com/signup?src=neomoderation_trial"));
        Player admin = player(true);

        join(new AdminJoinListener(plugin), admin);

        assertEquals(List.of(
                "[NeoMod] The cloud trial was stopped. Local rules still run. To keep cloud checks, create a free "
                        + "account, then a key, and run /nmod setup <key>.",
                "  [Create a free account]"), TestPlugin.lines(admin));
    }

    @Test
    void aTrialWithDaysToGoAndPlayersWhoAreNotAdminsHearNothing() {
        NeoModerationPlugin plugin = pluginWithPlan(new TrialClient.TrialStatusResult(true, "active",
                "2026-10-30T00:00:00Z", 9, "https://claim"));
        Player admin = player(true);
        Player regular = player(false);

        join(new AdminJoinListener(plugin), admin);
        join(new AdminJoinListener(pluginWithPlan(new TrialClient.TrialStatusResult(true, "expired",
                "2026-10-22T00:00:00Z", 0, "https://signup"))), regular);

        assertEquals(List.of(), TestPlugin.lines(admin));
        assertEquals(List.of(), TestPlugin.lines(regular));
    }
}
