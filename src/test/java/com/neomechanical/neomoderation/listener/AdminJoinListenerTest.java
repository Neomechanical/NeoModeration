package com.neomechanical.neomoderation.listener;

import com.neomechanical.neomoderation.NeoModerationPlugin;
import com.neomechanical.neomoderation.Permissions;
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
}
