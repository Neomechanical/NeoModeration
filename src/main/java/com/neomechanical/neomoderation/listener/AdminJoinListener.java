package com.neomechanical.neomoderation.listener;

import com.neomechanical.neomoderation.NeoModerationPlugin;
import com.neomechanical.neomoderation.Permissions;
import com.neomechanical.neomoderation.commands.TrialPromptSupport;
import com.neomechanical.neomoderation.config.OfflineModerationSettings;
import com.neomechanical.neomoderation.messages.DateText;
import com.neomechanical.neomoderation.messages.MenuRenderer;
import com.neomechanical.neomoderation.messages.MessageService;
import com.neomechanical.neomoderation.moderation.CloudPlanNotices;
import com.neomechanical.neomoderation.moderation.CloudPlanWatch;
import com.neomechanical.neomoderation.moderation.UpdateChecker;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
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
 *   <li>once per admin per server start, a line when a newer release exists;</li>
 *   <li>once each, a line when the cloud trial is about to end, has ended or has
 *   used its credits ({@link CloudPlanNotices}).</li>
 * </ul>
 */
public final class AdminJoinListener implements Listener {

    public static final String MARKER_FILE = ".admin-welcomed";

    private final NeoModerationPlugin plugin;
    /** Set once the card was claimed or the marker was found; cleared only when it is given back. */
    private final AtomicBoolean welcomed = new AtomicBoolean(false);
    private final Set<UUID> toldAboutUpdate = ConcurrentHashMap.newKeySet();

    public AdminJoinListener(NeoModerationPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!player.hasPermission(Permissions.ADMIN)) {
            return;
        }
        String newer = plugin.newerVersion();
        if (newer != null && toldAboutUpdate.add(player.getUniqueId())) {
            plugin.runForEntity(player, () -> tellAboutUpdate(player, newer));
        }
        File dataFolder = plugin.getDataFolder();
        CloudPlanWatch.Snapshot plan = plugin.cloudPlan().current();
        List<CloudPlanNotices.Notice> planNotices = plugin.settings().api().apiKey().isBlank()
                ? List.of() : CloudPlanNotices.joinNotices(plan, plugin.cloudPlan().now());
        if (!planNotices.isEmpty()) {
            plugin.runAsync(() -> {
                // The first notice not yet shown on this install; one a join.
                for (CloudPlanNotices.Notice planNotice : planNotices) {
                    String marker = CloudPlanNotices.joinMarker(planNotice);
                    if (claimMarker(dataFolder, marker)) {
                        // Off the main thread: the count reads the case history.
                        long caught = TrialPromptSupport.caughtDuringTrial(plugin, plan.status());
                        showOnce(player, () -> showPlanNotice(player, planNotice, caught),
                                () -> plugin.runAsync(() -> new File(dataFolder, marker).delete()));
                        return;
                    }
                }
            });
        }
        if (welcomed.get() || !plugin.settings().enabled() || !plugin.settings().api().apiKey().isBlank()) {
            return;
        }
        plugin.runAsync(() -> {
            if (claimWelcomeMarker(dataFolder)) {
                showOnce(player, () -> showCard(player), () -> plugin.runAsync(() -> {
                    new File(dataFolder, MARKER_FILE).delete();
                    welcomed.set(false);
                }));
            }
        });
    }

    /**
     * Shows a once-per-install line to {@code player} on their own thread, or runs {@code giveBack} when they left
     * before it reached them: they have not seen it, so the next admin to join gets it.
     */
    private void showOnce(Player player, Runnable show, Runnable giveBack) {
        plugin.runForEntity(player, () -> {
            if (player.isOnline()) {
                show.run();
            } else {
                giveBack.run();
            }
        }, giveBack);
    }

    /** Off the main thread. True only for the one call that creates the marker. */
    private boolean claimWelcomeMarker(File dataFolder) {
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

    /** Off the main thread. True only for the one call that creates the marker file. */
    private boolean claimMarker(File dataFolder, String name) {
        try {
            if (!dataFolder.exists()) {
                dataFolder.mkdirs();
            }
            return new File(dataFolder, name).createNewFile();
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save the admin notice marker " + name + ": " + e.getMessage());
            return false;
        }
    }

    private void showPlanNotice(Player player, CloudPlanNotices.Notice notice, long caught) {
        MessageService messages = plugin.messages();
        messages.send(player, notice.key(), Map.of(
                "date", DateText.of(messages, player, notice.dateIso()),
                "caught", caughtClause(messages, player, caught, plugin.monitorStats().cloudTotal())));
        String label = CloudPlanNotices.joinButton(notice);
        if (CloudPlanNotices.runsCommand(notice)) {
            messages.sendMenu(player, List.of(MenuRenderer.clickableLine(messages.text(player, label), notice.url(),
                    messages.format(player, "trial.start-hover", Map.of("command", notice.url())),
                    MenuRenderer.ClickAction.RUN)));
        } else {
            TrialPromptSupport.sendLinkButton(messages, player, label, notice.url());
        }
    }

    /**
     * What the cloud caught, as the start of a sentence, or nothing at zero. From the case history it covers the
     * whole trial; without the history only this run is known, and it says so.
     */
    static String caughtClause(MessageService messages, Player player, long caughtDuringTrial, long sinceStartup) {
        TrialPromptSupport.Caught caught = TrialPromptSupport.caught(caughtDuringTrial, sinceStartup);
        if (caught == null) {
            return "";
        }
        return MessageService.plain(messages.format(player, caught.wholeTrial() ? "cloud-plan.caught-during-trial"
                : "cloud-plan.caught-since-startup", Map.of("count", String.valueOf(caught.count())))) + " ";
    }

    private void showCard(Player player) {
        MessageService messages = plugin.messages();
        messages.send(player, "welcome.title");
        List<MenuRenderer.Line> lines = new ArrayList<>();
        lines.add(MenuRenderer.clickableLine(messages.text(player, "welcome.test-btn"), "/nmod test badword",
                messages.text(player, "welcome.test-hover"), MenuRenderer.ClickAction.RUN));
        OfflineModerationSettings offline = plugin.settings().offline();
        if (offline.blockServerAds() && offline.allowedUrls().isEmpty()) {
            // Ad blocking also stops players sharing this server's own address until it is allowed.
            lines.add(MenuRenderer.clickableLine(messages.text(player, "welcome.ads-btn"), "/nmod allow url add ",
                    messages.text(player, "welcome.ads-hover"), MenuRenderer.ClickAction.SUGGEST));
        }
        lines.add(MenuRenderer.clickableLine(messages.text(player, "welcome.surfaces-btn"), "/nmod surface",
                messages.text(player, "welcome.surfaces-hover"), MenuRenderer.ClickAction.RUN));
        lines.add(MenuRenderer.clickableLine(messages.text(player, "trial.cta-button"), "/nmod trial",
                messages.text(player, "trial.cta-hover"), MenuRenderer.ClickAction.RUN));
        messages.sendMenu(player, lines);
    }

    private void tellAboutUpdate(Player player, String newer) {
        MessageService messages = plugin.messages();
        String current = plugin.getDescription().getVersion();
        messages.sendMenu(player, List.of(MenuRenderer.buttons(List.of(
                new MenuRenderer.Button(messages.format(player, "update.available",
                        Map.of("latest", newer, "current", current)) + " ", "", "", MenuRenderer.ClickAction.NONE),
                new MenuRenderer.Button(messages.text(player, "update.button"), UpdateChecker.RELEASES_PAGE,
                        messages.format(player, "update.hover", Map.of("url", UpdateChecker.RELEASES_PAGE)),
                        MenuRenderer.ClickAction.OPEN_URL)))));
    }
}
