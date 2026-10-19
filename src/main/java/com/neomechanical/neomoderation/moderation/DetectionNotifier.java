package com.neomechanical.neomoderation.moderation;

import com.neomechanical.neomoderation.NeoModerationPlugin;
import com.neomechanical.neomoderation.config.ModerationSettings;
import com.neomechanical.neomoderation.messages.MenuRenderer;
import org.bukkit.entity.Player;

import java.util.Map;

/**
 * Sends in-game detection alerts to staff holding {@code neomoderation.notify}
 * and delegates optional Discord webhook notifications to {@link DiscordWebhookNotifier}.
 * Console logging stays with the caller so alerts and log lines are never doubled.
 */
public final class DetectionNotifier {
    public static final String NOTIFY_PERMISSION = "neomoderation.notify";
    private static final int MAX_MESSAGE_PREVIEW = 60;

    private final NeoModerationPlugin plugin;
    private final DiscordWebhookNotifier discordNotifier;

    public DetectionNotifier(NeoModerationPlugin plugin) {
        this(plugin, new DiscordWebhookNotifier());
    }

    public DetectionNotifier(NeoModerationPlugin plugin, DiscordWebhookNotifier discordNotifier) {
        this.plugin = plugin;
        this.discordNotifier = discordNotifier;
    }

    /** Must run on the main server thread. */
    public void notifyDetection(Player offender, String surface, String reason, String message,
                                ModerationSettings settings, String actionsText, boolean monitorStyle) {
        if (!settings.alerts().enabled()) {
            return;
        }
        String previewText = settings.alerts().includeMessage() ? preview(message) : "(hidden)";
        String key = monitorStyle ? "alert.monitor" : "alert.enforce";
        String line = plugin.messages().format(key, Map.of(
                "player", offender.getName(),
                "surface", surface,
                "reason", reason,
                "message", previewText,
                "actions", actionsText
        ));
        String caseCmd = "/nmod cases " + offender.getName();
        MenuRenderer.Line clickable = MenuRenderer.clickableLine(
                line, caseCmd, "&b" + caseCmd, MenuRenderer.ClickAction.RUN);
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            if (online.hasPermission(NOTIFY_PERMISSION)) {
                Player.Spigot spigot = online.spigot();
                if (spigot != null) {
                    spigot.sendMessage(clickable.player());
                } else {
                    online.sendMessage(line);
                }
            }
        }

        String webhookUrl = settings.alerts().discordWebhookUrl();
        if (DiscordWebhookNotifier.isValidWebhookUrl(webhookUrl)) {
            String payload = DiscordWebhookNotifier.buildPayload(
                    offender.getName(), surface, reason, previewText, actionsText, monitorStyle);
            plugin.runAsync(() -> discordNotifier.post(webhookUrl, payload));
        }
    }

    static String preview(String message) {
        if (message == null || message.isBlank()) {
            return "(no text)";
        }
        String flattened = message.replace('\n', ' ').replace('\r', ' ').trim();
        return flattened.length() <= MAX_MESSAGE_PREVIEW
                ? flattened
                : flattened.substring(0, MAX_MESSAGE_PREVIEW) + "...";
    }
}
