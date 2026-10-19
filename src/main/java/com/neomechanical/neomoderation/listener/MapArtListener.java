package com.neomechanical.neomoderation.listener;

import com.neomechanical.neomoderation.Permissions;
import com.neomechanical.neomoderation.NeoModerationPlugin;
import com.neomechanical.neomoderation.config.MapArtSettings;
import com.neomechanical.neomoderation.config.ModerationApiSettings;
import com.neomechanical.neomoderation.config.ModerationCategorySettings;
import com.neomechanical.neomoderation.config.ModerationMode;
import com.neomechanical.neomoderation.config.ModerationSettings;
import com.neomechanical.neomoderation.moderation.DetectionHandler;
import com.neomechanical.neomoderation.moderation.MapArtScanner;
import com.neomechanical.neomoderation.moderation.ModerationApiResult;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * Checks map art with the cloud when a player holds a filled map, puts one in
 * an item frame, or right-clicks a framed one. A picture is checked once: its
 * verdict is cached by its pixels and the cloud settings, so editing a map
 * means a new check.
 *
 * <p>A player holding or placing a flagged map is its owner and is handled
 * like any detection (alert, case, strike and actions in enforce mode, the map
 * taken away). A flagged map already hanging in a frame belongs to nobody we
 * know: it is taken down and staff are told where, and the player who clicked
 * it is not punished.</p>
 */
public final class MapArtListener implements Listener {
    private static final String SURFACE = "map_art";
    private static final String FRAME_SURFACE = "map_frame";

    private final NeoModerationPlugin plugin;
    private final Set<CacheKey> cleanMaps;
    private final Map<CacheKey, String> flaggedMaps;
    private final Set<Integer> pendingMaps = ConcurrentHashMap.newKeySet();
    private final Semaphore scanSlots = new Semaphore(2);
    private final Material filledMap = MapArtScanner.resolveFilledMapMaterial();

    public MapArtListener(NeoModerationPlugin plugin) {
        this.plugin = plugin;
        int cacheSize = plugin.settings().mapArt().cacheSize();
        this.cleanMaps = Collections.newSetFromMap(bounded(cacheSize));
        this.flaggedMaps = bounded(cacheSize);
    }

    /** Where a checked map is: in the player's hands (theirs) or already hanging in a frame (nobody's). */
    private record Sighting(Player player, ItemStack item, ItemFrame frame, boolean hanging) {
    }

    private record CacheKey(String pixels, ModerationApiSettings api, ModerationCategorySettings categories) {
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        if (!config().enabled() || !config().scanOnFrameInteract()
                || !(event.getRightClicked() instanceof ItemFrame frame)) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack held = MapArtScanner.mainHand(player.getInventory());
        if (MapArtScanner.mapIdOf(held, filledMap) == null) {
            held = MapArtScanner.offHand(player.getInventory());
        }
        if (MapArtScanner.mapIdOf(held, filledMap) != null) {
            check(new Sighting(player, held, frame, false));
        } else if (MapArtScanner.mapIdOf(frame.getItem(), filledMap) != null) {
            check(new Sighting(player, frame.getItem(), frame, true));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemHeld(PlayerItemHeldEvent event) {
        if (!config().enabled() || !config().scanOnHold()) {
            return;
        }
        ItemStack item = event.getPlayer().getInventory().getItem(event.getNewSlot());
        if (MapArtScanner.mapIdOf(item, filledMap) != null) {
            check(new Sighting(event.getPlayer(), item, null, false));
        }
    }

    private void check(Sighting sighting) {
        ModerationSettings settings = plugin.settings();
        if (!settings.enabled() || settings.api().apiKey().isBlank()
                || (!sighting.hanging() && shouldBypass(sighting.player()))) {
            return;
        }
        Integer mapId = MapArtScanner.mapIdOf(sighting.item(), filledMap);
        if (mapId == null || !scanSlots.tryAcquire()) {
            return;
        }
        if (!pendingMaps.add(mapId)) {
            scanSlots.release();
            return;
        }
        // Read live pixels here, on the thread that owns the map; the saved file is the fallback.
        byte[] live = MapArtScanner.livePixels(MapArtScanner.mapView((MapMeta) sighting.item().getItemMeta()));
        plugin.runAsync(() -> {
            try {
                byte[] pixels = live != null ? live : MapArtScanner.savedPixels(mapId);
                if (pixels != null) {
                    scan(sighting, mapId, pixels, settings);
                }
            } finally {
                pendingMaps.remove(mapId);
                scanSlots.release();
            }
        });
    }

    /** Off the main thread: the cached verdict, else one cloud check. */
    private void scan(Sighting sighting, int mapId, byte[] pixels, ModerationSettings settings) {
        CacheKey key = new CacheKey(MapArtScanner.fingerprint(pixels), settings.api(), settings.categories());
        String cachedCategory = flaggedMaps.get(key);
        if (cachedCategory != null) {
            act(sighting, mapId, cachedCategory, settings);
            return;
        }
        if (cleanMaps.contains(key) || !plugin.coordinator().isRemoteCallAllowed()) {
            return;
        }
        String image = MapArtScanner.pngBase64(pixels);
        if (image == null) {
            return;
        }
        ModerationApiResult result = plugin.apiClient().moderateImage(sighting.player().getName(),
                sighting.player().getUniqueId().toString(), image, settings.api(), settings.categories());
        plugin.coordinator().recordApiResult(result);
        if (!isCacheableResult(result)) {
            return;
        }
        if (!result.isFlagged()) {
            cleanMaps.add(key);
            return;
        }
        String category = result.category() == null ? "" : result.category();
        flaggedMaps.put(key, category);
        act(sighting, mapId, category, settings);
    }

    private void act(Sighting sighting, int mapId, String category, ModerationSettings scanned) {
        plugin.runForEntity(sighting.player(), () -> {
            ModerationSettings current = plugin.settings();
            if (!current.enabled() || !current.mapArt().enabled()
                    || !current.api().equals(scanned.api()) || !current.categories().equals(scanned.categories())) {
                return;
            }
            String reason = category.isBlank() ? SURFACE : SURFACE + ":" + category;
            boolean take = current.mapArt().confiscate();
            DetectionHandler.Disposition requested = take
                    ? DetectionHandler.Disposition.BLOCK : DetectionHandler.Disposition.ALLOW;
            if (sighting.hanging()) {
                takeDownFramed(sighting, mapId, reason, requested);
            } else {
                takeFromPlayer(sighting, mapId, reason, requested, current);
            }
        });
    }

    private void takeFromPlayer(Sighting sighting, int mapId, String reason,
                                DetectionHandler.Disposition requested, ModerationSettings settings) {
        Player player = sighting.player();
        DetectionHandler.Disposition done = plugin.detectionHandler().handle(player, SURFACE, reason,
                "#" + mapId, requested, DetectionHandler.Source.CLOUD);
        boolean monitor = DetectionHandler.Source.CLOUD.modeIn(settings) == ModerationMode.MONITOR;
        if (done == DetectionHandler.Disposition.BLOCK) {
            MapArtScanner.removeMap(player.getInventory(), filledMap, mapId);
            if (sighting.frame() != null) {
                // The player was hanging it: it may already be in the frame.
                plugin.runForEntity(sighting.frame(), () -> clearFrame(sighting.frame(), mapId));
            }
            plugin.messages().send(player, "mapart.confiscated");
        } else if (!monitor) {
            plugin.messages().send(player, "mapart.blocked");
        }
    }

    private void takeDownFramed(Sighting sighting, int mapId, String reason, DetectionHandler.Disposition requested) {
        Location at = sighting.frame().getLocation();
        String where = "#" + mapId + " @ " + at.getBlockX() + " " + at.getBlockY() + " " + at.getBlockZ();
        DetectionHandler.Disposition done = plugin.detectionHandler().handleFound(sighting.player(), FRAME_SURFACE,
                reason, where, requested, DetectionHandler.Source.CLOUD);
        if (done == DetectionHandler.Disposition.BLOCK) {
            plugin.runForEntity(sighting.frame(), () -> clearFrame(sighting.frame(), mapId));
        }
    }

    private void clearFrame(ItemFrame frame, int mapId) {
        Integer framed = MapArtScanner.mapIdOf(frame.getItem(), filledMap);
        if (framed != null && framed == mapId) {
            frame.setItem(null);
        }
    }

    private MapArtSettings config() {
        return plugin.settings().mapArt();
    }

    static boolean shouldBypass(Player player) {
        return player.hasPermission(Permissions.BYPASS);
    }

    static boolean isCacheableResult(ModerationApiResult result) {
        return result.kind() == ModerationApiResult.Kind.CLEAR
                || result.kind() == ModerationApiResult.Kind.FLAGGED;
    }

    /** A thread-safe, insertion-ordered map that evicts its oldest entry past {@code maxSize}. */
    private static <K, V> Map<K, V> bounded(int maxSize) {
        return Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > maxSize;
            }
        });
    }
}
