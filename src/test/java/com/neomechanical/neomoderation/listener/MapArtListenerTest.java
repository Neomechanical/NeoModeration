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
import com.neomechanical.neomoderation.moderation.ChatModerationCoordinator;
import com.neomechanical.neomoderation.moderation.DetectionNotifier;
import com.neomechanical.neomoderation.moderation.ModerationApiResult;
import com.neomechanical.neomoderation.moderation.ModerationApiClient;
import com.neomechanical.neomoderation.moderation.MapArtScanner;
import com.neomechanical.neomoderation.moderation.MonitorStats;
import com.neomechanical.neomoderation.messages.MessageService;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.MapMeta;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.withSettings;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

class MapArtListenerTest {
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
        assertFalse(MapArtListener.isCacheableResult(ModerationApiResult.insufficientCredits()));
        assertFalse(MapArtListener.isCacheableResult(ModerationApiResult.clientRequest()));
    }

    @Test
    void cachedCloudVerdictObeysCloudMonitorEvenWhenLocalRulesEnforce() throws Exception {
        assertCachedMapDecision(ModerationMode.ENFORCE, ModerationMode.MONITOR, false);
    }

    @Test
    void cachedCloudVerdictCanEnforceEvenWhenLocalRulesMonitor() throws Exception {
        assertCachedMapDecision(ModerationMode.MONITOR, ModerationMode.ENFORCE, true);
    }

    private static void assertCachedMapDecision(ModerationMode localMode, ModerationMode cloudMode,
                                                boolean shouldConfiscate) throws Exception {
        ModerationSettings settings = settings(localMode, cloudMode);
        try (ChatModerationCoordinator coordinator =
                     new ChatModerationCoordinator(Logger.getLogger("test"))) {

            MonitorStats stats = new MonitorStats();
            DetectionNotifier notifier = mock(DetectionNotifier.class);
            com.neomechanical.neomoderation.moderation.CaseLog caseLog =
                    mock(com.neomechanical.neomoderation.moderation.CaseLog.class);
            NeoModerationPlugin plugin = mock(NeoModerationPlugin.class);
            when(plugin.settings()).thenReturn(settings);
            when(plugin.coordinator()).thenReturn(coordinator);
            when(plugin.monitorStats()).thenReturn(stats);
            when(plugin.notifier()).thenReturn(notifier);
            when(plugin.caseLog()).thenReturn(caseLog);
            when(plugin.messages()).thenReturn(mock(MessageService.class));
            when(plugin.getLogger()).thenReturn(Logger.getLogger("test"));

            MapArtListener listener = new MapArtListener(plugin);


            MapMeta meta = mock(MapMeta.class, withSettings().extraInterfaces(LegacyMapId.class));
            when(((LegacyMapId) meta).getMapId()).thenReturn(7);
            ItemStack mapItem = mock(ItemStack.class);
            when(mapItem.getType()).thenReturn(Material.MAP);
            when(mapItem.hasItemMeta()).thenReturn(true);
            when(mapItem.getItemMeta()).thenReturn(meta);

            PlayerInventory inventory = mock(PlayerInventory.class);
            when(inventory.getItem(0)).thenReturn(mapItem);
            Player player = mock(Player.class);
            when(player.getInventory()).thenReturn(inventory);
            when(player.getName()).thenReturn("Tester");
            when(player.getUniqueId()).thenReturn(UUID.randomUUID());

            PlayerItemHeldEvent event = mock(PlayerItemHeldEvent.class);
            when(event.getPlayer()).thenReturn(player);
            when(event.getNewSlot()).thenReturn(0);

            ModerationApiClient client = mock(ModerationApiClient.class);
            when(plugin.apiClient()).thenReturn(client);
            when(client.moderateImage(anyString(), anyString(), anyString(), any(), any()))
                    .thenReturn(ModerationApiResult.flagged());
            doAnswer(call -> { ((Runnable) call.getArgument(0)).run(); return null; })
                    .when(plugin).runAsync(any(Runnable.class));
            doAnswer(call -> { ((Runnable) call.getArgument(1)).run(); return null; })
                    .when(plugin).runForEntity(any(), any(Runnable.class));
            try (var scanner = mockStatic(MapArtScanner.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
                scanner.when(() -> MapArtScanner.getBase64Image(7)).thenReturn("first-image");
                listener.onItemHeld(event);
                coordinator.recordApiResult(ModerationApiResult.transientTransport());
                coordinator.recordApiResult(ModerationApiResult.transientTransport());
                coordinator.recordApiResult(ModerationApiResult.transientTransport());
                assertFalse(coordinator.isRemoteCallAllowed());
                listener.onItemHeld(event);
            }
            verify(client, times(1)).moderateImage(anyString(), anyString(), anyString(), any(), any());

            String playerUuid = player.getUniqueId().toString();
            if (shouldConfiscate) {
                verify(inventory, times(2)).remove(mapItem);
                verify(notifier, times(2)).notifyDetection(player, "map_art", "map_art:7",
                        "(map 7)", settings, "confiscate", false);
                verify(caseLog, times(2)).record(org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.eq(playerUuid),
                        org.mockito.ArgumentMatchers.eq("Tester"),
                        org.mockito.ArgumentMatchers.eq("map_art"),
                        org.mockito.ArgumentMatchers.eq("map_art:7"),
                        org.mockito.ArgumentMatchers.eq("confiscate"),
                        org.mockito.ArgumentMatchers.eq("enforce"),
                        org.mockito.ArgumentMatchers.eq(""));
                assertEquals(0, stats.total());
            } else {
                assertEquals(2, stats.total());
                assertEquals(2L, stats.byReason().get("map_art"));
                verify(inventory, never()).remove(mapItem);
                verify(notifier, times(2)).notifyDetection(player, "map_art", "map_art:7",
                        "(map 7)", settings, "confiscate", true);
                verify(caseLog, times(2)).record(org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.eq(playerUuid),
                        org.mockito.ArgumentMatchers.eq("Tester"),
                        org.mockito.ArgumentMatchers.eq("map_art"),
                        org.mockito.ArgumentMatchers.eq("map_art:7"),
                        org.mockito.ArgumentMatchers.eq("confiscate"),
                        org.mockito.ArgumentMatchers.eq("monitor"),
                        org.mockito.ArgumentMatchers.eq(""));
            }
        }
    }

    @Test
    void changingASafeMapRequiresAnotherCloudVerdict() {
        ModerationSettings settings = settings(ModerationMode.ENFORCE, ModerationMode.ENFORCE);
        NeoModerationPlugin plugin = mock(NeoModerationPlugin.class);
        when(plugin.settings()).thenReturn(settings);
        when(plugin.messages()).thenReturn(mock(MessageService.class));
        when(plugin.notifier()).thenReturn(mock(DetectionNotifier.class));
        ModerationApiClient client = mock(ModerationApiClient.class);
        when(plugin.apiClient()).thenReturn(client);
        when(client.moderateImage(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(ModerationApiResult.clear(), ModerationApiResult.flagged());
        doAnswer(call -> { ((Runnable) call.getArgument(0)).run(); return null; })
                .when(plugin).runAsync(any(Runnable.class));
        doAnswer(call -> { ((Runnable) call.getArgument(1)).run(); return null; })
                .when(plugin).runForEntity(any(), any(Runnable.class));
        MapMeta meta = mock(MapMeta.class, withSettings().extraInterfaces(LegacyMapId.class));
        when(((LegacyMapId) meta).getMapId()).thenReturn(7);
        ItemStack item = mock(ItemStack.class);
        when(item.getType()).thenReturn(Material.MAP);
        when(item.hasItemMeta()).thenReturn(true);
        when(item.getItemMeta()).thenReturn(meta);
        Player player = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getItem(0)).thenReturn(item);
        when(player.getName()).thenReturn("Tester");
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        PlayerItemHeldEvent event = mock(PlayerItemHeldEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getNewSlot()).thenReturn(0);
        try (ChatModerationCoordinator coordinator = new ChatModerationCoordinator(Logger.getLogger("test"));
             var scanner = mockStatic(MapArtScanner.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            when(plugin.coordinator()).thenReturn(coordinator);
            scanner.when(() -> MapArtScanner.getBase64Image(7))
                    .thenReturn("first-image", "second-image", "second-image");
            MapArtListener listener = new MapArtListener(plugin);
            listener.onItemHeld(event);
            listener.onItemHeld(event);
            listener.onItemHeld(event);
            verify(client, times(2)).moderateImage(anyString(), anyString(), anyString(), any(), any());
            verify(inventory, times(2)).remove(item);
        }
    }

    private static ModerationSettings settings(ModerationMode localMode, ModerationMode cloudMode) {
        return new ModerationSettings(
                true,
                localMode,
                cloudMode,
                new ModerationApiSettings("https://api.neomechanical.com/v1/events", "test-key", 100, 100),
                new OfflineModerationSettings(true, false, true, List.of(), List.of(), List.of(), List.of()),
                new ModerationCategorySettings(Map.of()),
                new MapArtSettings(true, true, true, true, 1000),
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
                false
        );
    }

    public interface LegacyMapId {
        int getMapId();
    }
}
