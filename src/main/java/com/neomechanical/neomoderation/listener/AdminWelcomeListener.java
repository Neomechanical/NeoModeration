package com.neomechanical.neomoderation.listener;

import com.neomechanical.neomoderation.NeoModerationPlugin;
import com.neomechanical.neomoderation.messages.MenuRenderer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One-time first-join setup card for operators with {@code neomoderation.admin}
 * when the server has no configured API key. Once shown, a marker file is persisted
 * so the notice never repeats. Disk access runs off the main thread; after the
 * first check the answer is cached, so later joins touch no files.
 */
public final class AdminWelcomeListener implements Listener {

    public static final String ADMIN_PERMISSION = "neomoderation.admin";
    public static final String MARKER_FILE = ".admin-welcomed";

    private final NeoModerationPlugin plugin;
    /** Set once the card was shown or the marker was found; never cleared. */
    private final AtomicBoolean done = new AtomicBoolean(false);

    public AdminWelcomeListener(NeoModerationPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (done.get() || event == null) {
            return;
        }
        Player player = event.getPlayer();
        if (player == null || !player.hasPermission(ADMIN_PERMISSION)) {
            return;
        }
        if (plugin == null || plugin.settings() == null || !plugin.settings().enabled()) {
            return;
        }
        if (plugin.settings().api() == null || !plugin.settings().api().apiKey().isBlank()) {
            return;
        }
        File dataFolder = plugin.getDataFolder();
        if (dataFolder == null) {
            return;
        }
        plugin.runAsync(() -> {
            if (claimMarker(dataFolder)) {
                plugin.runForEntity(player, () -> showCard(player));
            }
        });
    }

    /** Off the main thread. True only for the one call that creates the marker. */
    private boolean claimMarker(File dataFolder) {
        if (done.get()) {
            return false;
        }
        File marker = new File(dataFolder, MARKER_FILE);
        try {
            if (!dataFolder.exists()) {
                dataFolder.mkdirs();
            }
            boolean created = marker.createNewFile();
            done.set(true);
            return created;
        } catch (IOException e) {
            if (plugin.getLogger() != null) {
                plugin.getLogger().warning("Could not persist admin welcome marker: " + e.getMessage());
            }
            return false;
        }
    }

    private void showCard(Player player) {
        if (plugin.messages() == null) {
            return;
        }
        plugin.messages().send(player, "welcome.title");
        String surfacesText = plugin.messages().format("welcome.surfaces-btn", Map.of());
        String trialText = plugin.messages().format("trial.cta-button", Map.of());
        plugin.messages().sendMenu(player, List.of(
                MenuRenderer.clickableLine(surfacesText, "/nmod surface", "&b/nmod surface", MenuRenderer.ClickAction.RUN),
                MenuRenderer.clickableLine(trialText, "/nmod trial", "&b/nmod trial", MenuRenderer.ClickAction.RUN)
        ));
    }
}
