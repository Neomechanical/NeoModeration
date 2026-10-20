package com.neomechanical.neomoderation.listener;

import com.neomechanical.neomoderation.NeoModerationPlugin;
import com.neomechanical.neomoderation.config.CaseSettings;
import com.neomechanical.neomoderation.config.MapArtSettings;
import com.neomechanical.neomoderation.config.ModerationApiSettings;
import com.neomechanical.neomoderation.config.ModerationCategorySettings;
import com.neomechanical.neomoderation.config.ModerationMode;
import com.neomechanical.neomoderation.config.ModerationSettings;
import com.neomechanical.neomoderation.config.OfflineModerationSettings;
import com.neomechanical.neomoderation.config.SpamSettings;
import com.neomechanical.neomoderation.config.StrikeSettings;
import com.neomechanical.neomoderation.config.SurfaceSettings;
import com.neomechanical.neomoderation.messages.MessageService;
import com.neomechanical.neomoderation.moderation.ChatModerationCoordinator;
import com.neomechanical.neomoderation.moderation.DetectionHandler;
import com.neomechanical.neomoderation.moderation.MapArtScanner;
import com.neomechanical.neomoderation.moderation.ModerationApiClient;
import com.neomechanical.neomoderation.moderation.ModerationApiResult;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.MapMeta;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class MapArtListenerTest {
    private static final int MAP_ID = 7;

    @Test
    void bypassPermissionSkipsMapChecks() {
        Player player = mock(Player.class);
        when(player.hasPermission("neomoderation.bypass")).thenReturn(true);

        assertTrue(MapArtListener.shouldBypass(player));
    }

    @Test
    void onlyCompletedModerationResultsAreCached() {
        assertTrue(MapArtListener.isCacheableResult(ModerationApiResult.clear()));
        assertTrue(MapArtListener.isCacheableResult(ModerationApiResult.flagged()));
        assertFalse(MapArtListener.isCacheableResult(ModerationApiResult.transientTransport()));
        assertFalse(MapArtListener.isCacheableResult(ModerationApiResult.clientAuth()));
    }

    @Test
    void aHeldFlaggedMapIsTakenAndItsHolderHandledLikeAnyDetection() {
        try (Fixture f = new Fixture(ModerationMode.ENFORCE, ModerationApiResult.flagged("sexual"))) {
            f.listener.onItemHeld(f.heldEvent);

            verify(f.handler).handle(eq(f.player), eq("map_art"), eq("map_art:sexual"), anyString(),
                    eq(DetectionHandler.Disposition.BLOCK), eq(DetectionHandler.Source.CLOUD));
            verify(f.inventory).setItem(0, null);
            verify(f.messages).send(f.player, "mapart.confiscated");
        }
    }

    @Test
    void cloudMonitorOnlyAlertsAndLeavesTheMapAlone() {
        try (Fixture f = new Fixture(ModerationMode.MONITOR, ModerationApiResult.flagged("sexual"))) {
            f.listener.onItemHeld(f.heldEvent);

            verify(f.handler).handle(eq(f.player), eq("map_art"), anyString(), anyString(), any(), any());
            verify(f.inventory, never()).setItem(0, null);
            verify(f.messages, never()).send(eq(f.player), anyString());
        }
    }

    @Test
    void theSamePictureIsCheckedOnceAndAnEditedOneAgain() {
        try (Fixture f = new Fixture(ModerationMode.ENFORCE, ModerationApiResult.clear())) {
            f.listener.onItemHeld(f.heldEvent);
            f.listener.onItemHeld(f.heldEvent);
            verify(f.client, times(1)).moderateImage(anyString(), anyString(), anyString(), any(), any());

            f.pixels[0] = 12;
            f.listener.onItemHeld(f.heldEvent);
            verify(f.client, times(2)).moderateImage(anyString(), anyString(), anyString(), any(), any());
        }
    }

    @Test
    void eachPlayerHasAnHourlyAllowanceOfNewPictures() {
        try (Fixture f = new Fixture(ModerationMode.ENFORCE, ModerationApiResult.clear(), 1)) {
            f.listener.onItemHeld(f.heldEvent);
            f.pixels[0] = 12;
            f.listener.onItemHeld(f.heldEvent);

            verify(f.client, times(1)).moderateImage(anyString(), anyString(), anyString(), any(), any());
        }
    }

    @Test
    void aFlaggedMapFoundInAFrameIsTakenDownWithoutBlamingTheFinder() {
        try (Fixture f = new Fixture(ModerationMode.ENFORCE, ModerationApiResult.flagged("sexual"))) {
            ItemFrame frame = mock(ItemFrame.class);
            UUID frameId = UUID.randomUUID();
            when(frame.getUniqueId()).thenReturn(frameId);
            when(frame.getItem()).thenReturn(f.map);
            when(frame.getLocation()).thenReturn(new Location(null, 10, 64, -20));
            when(f.inventory.getItemInHand()).thenReturn(null);
            PlayerInteractEntityEvent event = mock(PlayerInteractEntityEvent.class);
            when(event.getPlayer()).thenReturn(f.player);
            when(event.getRightClicked()).thenReturn(frame);

            f.listener.onPlayerInteractEntity(event);

            verify(f.handler).handleFound(eq(f.player), eq("map_frame"), eq("map_art:sexual"), eq("#7 @ 10 64 -20"),
                    eq(DetectionHandler.Disposition.BLOCK), eq(DetectionHandler.Source.CLOUD));
            verify(f.handler, never()).handle(any(), anyString(), anyString(), anyString(), any(), any());
            verify(frame).setItem(null);
            // The cloud hears about the frame, not about the player who happened to click it.
            verify(f.client).moderateImage(eq("item_frame"), eq(frameId.toString()), anyString(), any(), any());
        }
    }

    /** A plugin with one held map, a cloud answering {@code answer}, and the real listener. */
    private static final class Fixture implements AutoCloseable {
        final NeoModerationPlugin plugin = mock(NeoModerationPlugin.class);
        final MessageService messages = mock(MessageService.class);
        final DetectionHandler handler = mock(DetectionHandler.class);
        final ModerationApiClient client = mock(ModerationApiClient.class);
        final Player player = mock(Player.class);
        final PlayerInventory inventory = mock(PlayerInventory.class);
        final ItemStack map = mock(ItemStack.class);
        final PlayerItemHeldEvent heldEvent = mock(PlayerItemHeldEvent.class);
        final byte[] pixels = new byte[128 * 128];
        final ChatModerationCoordinator coordinator = new ChatModerationCoordinator(Logger.getLogger("test"));
        final MockedStatic<MapArtScanner> scanner = mockStatic(MapArtScanner.class, CALLS_REAL_METHODS);
        final MapArtListener listener;

        Fixture(ModerationMode cloudMode, ModerationApiResult answer) {
            this(cloudMode, answer, 30);
        }

        Fixture(ModerationMode cloudMode, ModerationApiResult answer, int checksPerPlayerPerHour) {
            ModerationSettings settings = settings(cloudMode, checksPerPlayerPerHour);
            when(plugin.settings()).thenReturn(settings);
            when(plugin.messages()).thenReturn(messages);
            when(plugin.detectionHandler()).thenReturn(handler);
            when(plugin.coordinator()).thenReturn(coordinator);
            when(plugin.apiClient()).thenReturn(client);
            when(plugin.getLogger()).thenReturn(Logger.getLogger("test"));
            when(client.moderateImage(anyString(), anyString(), anyString(), any(), any())).thenReturn(answer);
            doAnswer(call -> { ((Runnable) call.getArgument(0)).run(); return null; })
                    .when(plugin).runAsync(any(Runnable.class));
            doAnswer(call -> { ((Runnable) call.getArgument(1)).run(); return null; })
                    .when(plugin).runForEntity(any(), any(Runnable.class));
            boolean blocks = cloudMode == ModerationMode.ENFORCE;
            when(handler.handle(any(), anyString(), anyString(), anyString(), any(), any())).thenReturn(blocks
                    ? DetectionHandler.Disposition.BLOCK : DetectionHandler.Disposition.ALLOW);
            when(handler.handleFound(any(), anyString(), anyString(), anyString(), any(), any())).thenReturn(blocks
                    ? DetectionHandler.Disposition.BLOCK : DetectionHandler.Disposition.ALLOW);

            MapMeta meta = mock(MapMeta.class, withSettings().extraInterfaces(LegacyMapId.class));
            when(((LegacyMapId) meta).getMapId()).thenReturn(MAP_ID);
            when(map.getType()).thenReturn(Material.MAP);
            when(map.hasItemMeta()).thenReturn(true);
            when(map.getItemMeta()).thenReturn(meta);
            when(inventory.getItem(0)).thenReturn(map);
            when(inventory.getContents()).thenReturn(new ItemStack[]{map});
            when(player.getInventory()).thenReturn(inventory);
            when(player.getName()).thenReturn("Tester");
            when(player.getUniqueId()).thenReturn(UUID.randomUUID());
            when(heldEvent.getPlayer()).thenReturn(player);
            when(heldEvent.getNewSlot()).thenReturn(0);
            scanner.when(() -> MapArtScanner.savedPixels(MAP_ID)).thenAnswer(call -> Arrays.copyOf(pixels, pixels.length));
            scanner.when(() -> MapArtScanner.pngBase64(any())).thenReturn("image");
            listener = new MapArtListener(plugin);
        }

        @Override
        public void close() {
            scanner.close();
            coordinator.close();
        }
    }

    private static ModerationSettings settings(ModerationMode cloudMode, int checksPerPlayerPerHour) {
        return new ModerationSettings(
                true,
                ModerationMode.ENFORCE,
                cloudMode,
                new ModerationApiSettings("https://api.neomechanical.com/v1/events", "test-key", 100, 100),
                new OfflineModerationSettings(true, false, true, List.of(), List.of(), List.of(), List.of()),
                new ModerationCategorySettings(Map.of()),
                new MapArtSettings(true, true, true, true, 1000, checksPerPlayerPerHour),
                List.of(),
                true,
                true,
                new ModerationSettings.AlertSettings(true, true),
                new SpamSettings(false, 0, 0, 0.9D, 0, 0, 0, 0),
                new StrikeSettings(false, 30, List.of()),
                new SurfaceSettings(
                        SurfaceSettings.SurfaceMode.OFF,
                        SurfaceSettings.SurfaceMode.OFF,
                        SurfaceSettings.SurfaceMode.OFF,
                        SurfaceSettings.SurfaceMode.OFF,
                        List.of()),
                new CaseSettings(true, false),
                false,
                true
        );
    }

    public interface LegacyMapId {
        int getMapId();
    }
}
