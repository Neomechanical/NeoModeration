package com.neomechanical.neomoderation.listener;

import com.neomechanical.neomoderation.NeoModerationPlugin;
import com.neomechanical.neomoderation.messages.MenuRenderer;
import com.neomechanical.neomoderation.messages.MessageService;
import com.neomechanical.neomoderation.moderation.UpdateChecker;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * What an admin ({@code neomoderation.admin}) sees on joining, never anyone else:
 *
 * <ul>
 *   <li>once per install, while it has no cloud key, a card that says what is
 *   running and offers the two next steps (a marker file keeps it to once);</li>
 *   <li>once per admin per server start, a line when a newer release exists.</li>
 * </ul>
 */
public final class AdminJoinListener implements Listener {

    public static final String ADMIN_PERMISSION = "neomoderation.admin";
    public static final String MARKER_FILE = ".admin-welcomed";

    private final NeoModerationPlugin plugin;
    /** Set once the card was shown or the marker was found; never cleared. */
    private final AtomicBoolean welcomed = new AtomicBoolean(false);
    private final Set<UUID> toldAboutUpdate = ConcurrentHashMap.newKeySet();

    public AdminJoinListener(NeoModerationPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!player.hasPermission(ADMIN_PERMISSION)) {
            return;
        }
        String newer = plugin.newerVersion();
        if (newer != null && toldAboutUpdate.add(player.getUniqueId())) {
            plugin.runForEntity(player, () -> tellAboutUpdate(player, newer));
        }
        if (welcomed.get() || !plugin.settings().enabled() || !plugin.settings().api().apiKey().isBlank()) {
            return;
        }
        File dataFolder = plugin.getDataFolder();
        plugin.runAsync(() -> {
            if (claimMarker(dataFolder)) {
                plugin.runForEntity(player, () -> showCard(player));
            }
        });
    }

    /** Off the main thread. True only for the one call that creates the marker. */
    private boolean claimMarker(File dataFolder) {
        if (welcomed.get()) {
            return false;
        }
        try {
            if (!dataFolder.exists()) {
                dataFolder.mkdirs();
            }
            boolean created = new File(dataFolder, MARKER_FILE).createNewFile();
            welcomed.set(true);
            return created;
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save the admin welcome marker: " + e.getMessage());
            return false;
        }
    }

    private void showCard(Player player) {
        MessageService messages = plugin.messages();
        messages.send(player, "welcome.title");
        messages.sendMenu(player, List.of(
                MenuRenderer.clickableLine(messages.text(player, "welcome.test-btn"), "/nmod test badword",
                        messages.text(player, "welcome.test-hover"), MenuRenderer.ClickAction.RUN),
                MenuRenderer.clickableLine(messages.text(player, "welcome.surfaces-btn"), "/nmod surface",
                        messages.text(player, "welcome.surfaces-hover"), MenuRenderer.ClickAction.RUN),
                MenuRenderer.clickableLine(messages.text(player, "trial.cta-button"), "/nmod trial",
                        messages.text(player, "trial.cta-hover"), MenuRenderer.ClickAction.RUN)));
    }

    private void tellAboutUpdate(Player player, String newer) {
        MessageService messages = plugin.messages();
        String current = plugin.getDescription().getVersion();
        messages.sendMenu(player, List.of(MenuRenderer.buttons(List.of(
                new MenuRenderer.Button(messages.format(player, "update.available",
                        Map.of("latest", newer, "current", current)), "", "", MenuRenderer.ClickAction.NONE),
                new MenuRenderer.Button(messages.text(player, "update.button"), UpdateChecker.RELEASES_PAGE,
                        messages.format(player, "update.hover", Map.of("url", UpdateChecker.RELEASES_PAGE)),
                        MenuRenderer.ClickAction.OPEN_URL)))));
    }
}
