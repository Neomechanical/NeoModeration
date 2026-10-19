package com.neomechanical.neomoderation.listener;

import com.neomechanical.neomoderation.NeoModerationPlugin;
import com.neomechanical.neomoderation.config.ModerationApiSettings;
import com.neomechanical.neomoderation.config.ModerationSettings;
import com.neomechanical.neomoderation.messages.MessageService;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AdminWelcomeListenerTest {

    @TempDir
    Path tempDir;

    private NeoModerationPlugin plugin;
    private MessageService messages;
    private ModerationSettings settings;
    private ModerationApiSettings apiSettings;
    private Player admin;
    private PlayerJoinEvent adminJoinEvent;
    private AdminWelcomeListener listener;

    @BeforeEach
    void setUp() {
        plugin = mock(NeoModerationPlugin.class);
        messages = mock(MessageService.class);
        settings = mock(ModerationSettings.class);
        apiSettings = mock(ModerationApiSettings.class);

        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());
        when(plugin.messages()).thenReturn(messages);
        when(plugin.settings()).thenReturn(settings);
        when(settings.enabled()).thenReturn(true);
        when(settings.api()).thenReturn(apiSettings);
        when(apiSettings.apiKey()).thenReturn("");

        doAnswer(invocation -> {
            Runnable task = invocation.getArgument(1);
            task.run();
            return null;
        }).when(plugin).runForEntity(any(), any());

        admin = mock(Player.class);
        when(admin.hasPermission(AdminWelcomeListener.ADMIN_PERMISSION)).thenReturn(true);

        adminJoinEvent = mock(PlayerJoinEvent.class);
        when(adminJoinEvent.getPlayer()).thenReturn(admin);

        listener = new AdminWelcomeListener(plugin);
    }

    @Test
    void firstJoinByAdminWithoutApiKeySendsWelcomeAndCreatesMarker() {
        File marker = new File(tempDir.toFile(), AdminWelcomeListener.MARKER_FILE);
        assertFalse(marker.exists());

        listener.onPlayerJoin(adminJoinEvent);

        assertTrue(marker.exists(), "The marker file must be created on first admin join");
        verify(messages).send(admin, "welcome.title");
        verify(messages).sendMenu(eq(admin), anyList());
    }

    @Test
    void secondJoinDoesNotSendWelcomeBecauseMarkerExists() {
        File marker = new File(tempDir.toFile(), AdminWelcomeListener.MARKER_FILE);

        // First join creates marker and sends notice
        listener.onPlayerJoin(adminJoinEvent);
        assertTrue(marker.exists());
        verify(messages, times(1)).send(admin, "welcome.title");

        // Second join should see the marker and return early
        listener.onPlayerJoin(adminJoinEvent);
        verify(messages, times(1)).send(admin, "welcome.title");
    }

    @Test
    void nonAdminPlayerDoesNotTriggerWelcomeNorCreateMarker() {
        File marker = new File(tempDir.toFile(), AdminWelcomeListener.MARKER_FILE);

        Player regularPlayer = mock(Player.class);
        when(regularPlayer.hasPermission(AdminWelcomeListener.ADMIN_PERMISSION)).thenReturn(false);

        PlayerJoinEvent regularJoinEvent = mock(PlayerJoinEvent.class);
        when(regularJoinEvent.getPlayer()).thenReturn(regularPlayer);

        listener.onPlayerJoin(regularJoinEvent);

        assertFalse(marker.exists(), "Marker must not be created for non-admin players");
        verifyNoInteractions(messages);
    }

    @Test
    void serverWithApiKeyConfiguredDoesNotTriggerWelcomeNorCreateMarker() {
        File marker = new File(tempDir.toFile(), AdminWelcomeListener.MARKER_FILE);
        when(apiSettings.apiKey()).thenReturn("nm_live_12345");

        listener.onPlayerJoin(adminJoinEvent);

        assertFalse(marker.exists(), "Marker must not be created when an API key is already present");
        verifyNoInteractions(messages);
    }

    @Test
    void disabledPluginDoesNotTriggerWelcome() {
        File marker = new File(tempDir.toFile(), AdminWelcomeListener.MARKER_FILE);
        when(settings.enabled()).thenReturn(false);

        listener.onPlayerJoin(adminJoinEvent);

        assertFalse(marker.exists());
        verifyNoInteractions(messages);
    }
}
