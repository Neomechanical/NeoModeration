package com.neomechanical.neomoderation.listener;

import com.neomechanical.neomoderation.NeoModerationPlugin;
import com.neomechanical.neomoderation.config.MapArtSettings;
import com.neomechanical.neomoderation.config.ModerationMode;
import com.neomechanical.neomoderation.moderation.DetectionHandler;
import com.neomechanical.neomoderation.config.ModerationSettings;
import com.neomechanical.neomoderation.moderation.MapArtScanner;
import com.neomechanical.neomoderation.moderation.ModerationApiResult;
import org.bukkit.Material;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapView;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import com.neomechanical.neomoderation.config.ModerationApiSettings;
import com.neomechanical.neomoderation.config.ModerationCategorySettings;

/**
 * Scans filled maps (map art) for NSFW content when players hold them or interact with
 * them in item frames. Verdicts are cached by saved image content and cloud settings;
 * editing a previously scanned map requires a new verdict.
 * Uses reflection so the plugin still compiles against older Bukkit APIs while running on
 * modern Paper.
 */
public final class MapArtListener implements Listener {
    private static final String BYPASS_PERMISSION = "neomoderation.bypass";

    private final NeoModerationPlugin plugin;
    private final Set<CacheKey> scannedMaps;
    private final Set<CacheKey> flaggedMaps;
    private final Set<Integer> pendingMaps = ConcurrentHashMap.newKeySet();
    private final Semaphore scanSlots = new Semaphore(2);
    private final Material filledMapMaterial = resolveFilledMapMaterial();

    public MapArtListener(NeoModerationPlugin plugin) {
        this.plugin = plugin;
        int cacheSize = plugin.settings().mapArt().cacheSize();
        this.scannedMaps = boundedSet(cacheSize);
        this.flaggedMaps = boundedSet(cacheSize);
    }

    @EventHandler
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        if (!config().enabled() || !config().scanOnFrameInteract()) {
            return;
        }
        if (!(event.getRightClicked() instanceof ItemFrame frame)) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack held = mainHand(player.getInventory());
        if (!isFilledMap(held)) {
            held = offHand(player.getInventory());
        }
        if (isFilledMap(held)) {
            handleMapItem(player, held);
            return;
        }
        ItemStack frameItem = frame.getItem();
        if (isFilledMap(frameItem)) {
            handleMapItem(player, frameItem);
        }
    }

    @EventHandler
    public void onItemHeld(PlayerItemHeldEvent event) {
        if (!config().enabled() || !config().scanOnHold()) {
            return;
        }
        ItemStack item = event.getPlayer().getInventory().getItem(event.getNewSlot());
        if (isFilledMap(item)) {
            handleMapItem(event.getPlayer(), item);
        }
    }

    private void handleMapItem(Player player, ItemStack mapItem) {
        if (shouldBypass(player)
                || !plugin.settings().enabled()
                || mapItem == null
                || !mapItem.hasItemMeta()) {
            return;
        }
        if (plugin.settings().api().apiKey().isBlank()) {
            return;
        }
        if (!(mapItem.getItemMeta() instanceof MapMeta meta)) {
            return;
        }
        Integer mapId = resolveMapId(meta);
        if (mapId == null) {
            return;
        }
        if (!scanSlots.tryAcquire()) {
            return;
        }
        if (!pendingMaps.add(mapId)) {
            scanSlots.release();
            return;
        }
        ModerationSettings settings = plugin.settings();
        String playerName = player.getName();
        String playerUuid = player.getUniqueId().toString();
        plugin.runAsync(() -> {
            try {
                String base64Image = MapArtScanner.getBase64Image(mapId);
                if (base64Image == null) {
                    return;
                }
                CacheKey key = new CacheKey(imageHash(base64Image), settings.api(), settings.categories());
                if (flaggedMaps.contains(key)) {
                    plugin.runForEntity(player, () -> applyFlaggedMap(player, mapItem, mapId, settings, "mapart.blocked"));
                    return;
                }
                if (scannedMaps.contains(key) || !plugin.coordinator().isRemoteCallAllowed()) {
                    return;
                }
                ModerationApiResult result = plugin.apiClient().moderateImage(
                        playerName,
                        playerUuid,
                        base64Image,
                        settings.api(),
                        settings.categories()
                );
                plugin.coordinator().recordApiResult(result);
                if (!isCacheableResult(result)) {
                    return;
                }
                scannedMaps.add(key);
                if (!result.isFlagged()) {
                    return;
                }
                // Folia: confiscation edits this player's inventory, so it must run
                // on that player's entity scheduler rather than any global thread.
                flaggedMaps.add(key);
                plugin.runForEntity(player, () -> applyFlaggedMap(player, mapItem, mapId, settings, "mapart.confiscated"));
            } finally {
                pendingMaps.remove(mapId);
                scanSlots.release();
            }
        });
    }

    private void applyFlaggedMap(Player player, ItemStack mapItem, int mapId,
                                 ModerationSettings scannedSettings, String messageKey) {
        ModerationSettings current = plugin.settings();
        if (current.enabled() && current.mapArt().enabled() && !shouldBypass(player)
                && current.api().equals(scannedSettings.api())
                && current.categories().equals(scannedSettings.categories())) {
            handleFlaggedMap(player, mapItem, mapId, messageKey);
        }
    }

    private record CacheKey(String imageHash, ModerationApiSettings api, ModerationCategorySettings categories) { }

    private static String imageHash(String image) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(image.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Main thread only. Monitor mode alerts staff instead of messaging/confiscating. */
    private void handleFlaggedMap(Player player, ItemStack mapItem, int mapId, String messageKey) {
        ModerationSettings settings = plugin.settings();
        String reason = "map_art:" + mapId;
        String preview = "(map " + mapId + ")";
        String actionText = config().confiscate() ? "confiscate" : "warn";
        boolean monitor = DetectionHandler.Source.CLOUD.modeIn(settings) == ModerationMode.MONITOR;
        if (monitor) {
            plugin.monitorStats().record(reason);
            if (plugin.notifier() != null) {
                plugin.notifier().notifyDetection(player, "map_art", reason, preview, settings, actionText, true);
            }
            if (plugin.getLogger() != null) {
                plugin.getLogger().info("MONITOR: map " + mapId + " held by " + player.getName()
                        + " would be flagged; no action taken.");
            }
            recordMapCase(player, reason, preview, settings, actionText, true);
            return;
        }
        confiscateIfEnabled(player, mapItem, messageKey);
        if (plugin.notifier() != null) {
            plugin.notifier().notifyDetection(player, "map_art", reason, preview, settings, actionText, false);
        }
        recordMapCase(player, reason, preview, settings, actionText, false);
    }

    private void recordMapCase(Player player, String reason, String preview,
                               ModerationSettings settings, String actionText, boolean monitor) {
        if (!settings.cases().enabled() || plugin.caseLog() == null) {
            return;
        }
        long now = System.currentTimeMillis();
        String uuid = player.getUniqueId().toString();
        String name = player.getName();
        String storedPreview = settings.cases().storeContent() ? preview : "";
        String mode = monitor ? "monitor" : "enforce";
        plugin.runAsync(() -> plugin.caseLog().record(now, uuid, name, "map_art", reason, actionText, mode, storedPreview));
    }

    private void confiscateIfEnabled(Player player, ItemStack mapItem, String messageKey) {
        plugin.messages().send(player, messageKey);
        if (config().confiscate()) {
            player.getInventory().remove(mapItem);
        }
    }

    private MapArtSettings config() {
        return plugin.settings().mapArt();
    }

    static boolean shouldBypass(Player player) {
        return player.hasPermission(BYPASS_PERMISSION);
    }

    static boolean isCacheableResult(ModerationApiResult result) {
        return result.kind() == ModerationApiResult.Kind.CLEAR
                || result.kind() == ModerationApiResult.Kind.FLAGGED;
    }

    private boolean isFilledMap(ItemStack item) {
        return item != null && item.getType() == filledMapMaterial;
    }

    private static Material resolveFilledMapMaterial() {
        try {
            return Material.valueOf("FILLED_MAP");
        } catch (IllegalArgumentException ignored) {
            return Material.MAP;
        }
    }

    /** A thread-safe, insertion-ordered set that evicts its oldest entry past {@code maxSize}. */
    private static <T> Set<T> boundedSet(int maxSize) {
        return Collections.synchronizedSet(Collections.newSetFromMap(
                new LinkedHashMap<>(16, 0.75f, false) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<T, Boolean> eldest) {
                        return size() > maxSize;
                    }
                }));
    }

    private static ItemStack mainHand(PlayerInventory inventory) {
        try {
            Method method = inventory.getClass().getMethod("getItemInMainHand");
            return (ItemStack) method.invoke(inventory);
        } catch (ReflectiveOperationException ignored) {
            return inventory.getItemInHand();
        }
    }

    private static ItemStack offHand(PlayerInventory inventory) {
        try {
            Method method = inventory.getClass().getMethod("getItemInOffHand");
            return (ItemStack) method.invoke(inventory);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static Integer resolveMapId(MapMeta meta) {
        try {
            Method getMapView = meta.getClass().getMethod("getMapView");
            Object view = getMapView.invoke(meta);
            if (view instanceof MapView mapView) {
                return (int) mapView.getId();
            }
        } catch (ReflectiveOperationException ignored) {
            // Fall through to legacy map id.
        }
        try {
            Method getMapId = meta.getClass().getMethod("getMapId");
            Object id = getMapId.invoke(meta);
            if (id instanceof Number number) {
                return number.intValue();
            }
        } catch (ReflectiveOperationException ignored) {
            // Unsupported on this server version.
        }
        return null;
    }
}
