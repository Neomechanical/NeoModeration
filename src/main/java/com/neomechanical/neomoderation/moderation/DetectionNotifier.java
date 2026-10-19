package com.neomechanical.neomoderation.moderation;

import com.neomechanical.neomoderation.NeoModerationPlugin;
import com.neomechanical.neomoderation.Permissions;
import com.neomechanical.neomoderation.config.ModerationSettings;
import com.neomechanical.neomoderation.messages.ActionText;
import com.neomechanical.neomoderation.messages.MenuRenderer;
import com.neomechanical.neomoderation.messages.MessageService;
import com.neomechanical.neomoderation.messages.Reasons;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Tells people about a detection: one line in the console, an alert to every
 * online player holding {@code neomoderation.notify} in their own language
 * (click it for the player's cases; paused while a raid lasts, see
 * {@link AlertThrottle}), and a post to the Discord webhook if one is set. Runs
 * on the offender's thread.
 */
public final class DetectionNotifier {
    private static final int MAX_MESSAGE_PREVIEW = 80;
    private static final int RISK = 0xFF7A70;
    private static final int REVIEW = 0xF5B54A;

    private final NeoModerationPlugin plugin;
    private final DiscordAlerts discord;
    private final AlertThrottle throttle = new AlertThrottle();
    private final LongSupplier clock;

    public DetectionNotifier(NeoModerationPlugin plugin, DiscordAlerts discord) {
        this(plugin, discord, System::currentTimeMillis);
    }

    DetectionNotifier(NeoModerationPlugin plugin, DiscordAlerts discord, LongSupplier clock) {
        this.plugin = plugin;
        this.discord = discord;
        this.clock = clock;
    }

    /** A detection as alerts describe it. */
    public record Detection(String player, String surface, String reason, String message, Outcome outcome) {
    }

    public void notifyDetection(Detection detection, ModerationSettings settings) {
        MessageService messages = plugin.messages();
        // A found map's "message" is where it hangs, which alerts always show.
        boolean showMessage = settings.alerts().includeMessage()
                || DetectionHandler.FOUND_SURFACES.contains(detection.surface());
        String preview = showMessage ? preview(detection.message()) : null;
        CommandSender console = plugin.getServer().getConsoleSender();
        // The logger already names the plugin, so the console line goes without the chat prefix.
        plugin.getLogger().info(MessageService.plain(line(messages, console, detection, preview, "")).trim());

        AlertThrottle.Decision shown = settings.alerts().enabled()
                ? throttle.admit(detection.player(), clock.getAsLong())
                : AlertThrottle.Decision.QUIET;
        if (shown.kind() != AlertThrottle.Kind.QUIET || shown.missed() != null) {
            for (Player staff : plugin.getServer().getOnlinePlayers()) {
                if (staff.hasPermission(Permissions.NOTIFY)) {
                    messages.sendMenu(staff, staffLines(messages, staff, detection, preview, shown));
                }
            }
        }

        String webhook = settings.alerts().discordWebhookUrl();
        if (DiscordAlerts.isWebhookUrl(webhook)) {
            discord.submit(webhook, discordAlert(messages, console, detection, preview),
                    MessageService.plain(messages.format(console, "discord.more", Map.of("count", "{count}"))));
        }
    }

    /** What one staff member sees for a detection: the line about a pause it ends, then the alert or a pause line. */
    private static List<MenuRenderer.Line> staffLines(MessageService messages, Player staff, Detection detection,
                                                      String preview, AlertThrottle.Decision shown) {
        // The click opens cases, so it is only offered to staff who may open them.
        boolean mayOpen = staff.hasPermission(Permissions.MODERATE);
        List<MenuRenderer.Line> lines = new ArrayList<>(2);
        AlertThrottle.Missed missed = shown.missed();
        if (missed != null && missed.player() != null) {
            String line = messages.format(staff, "alert.missed-player",
                    Map.of("player", missed.player(), "count", String.valueOf(missed.alerts())));
            lines.add(casesLine(messages, staff, line, missed.player(), mayOpen));
        } else if (missed != null) {
            lines.add(MenuRenderer.text(messages.format(staff, "alert.missed-all", Map.of(
                    "count", String.valueOf(missed.alerts()), "players", String.valueOf(missed.players())))));
        }
        switch (shown.kind()) {
            case SHOW -> lines.add(casesLine(messages, staff, line(messages, staff, detection, preview, null),
                    detection.player(), mayOpen));
            case PAUSE_PLAYER -> lines.add(casesLine(messages, staff,
                    messages.format(staff, "alert.paused-player", Map.of("player", detection.player())),
                    detection.player(), mayOpen));
            case PAUSE_ALL -> lines.add(MenuRenderer.text(messages.format(staff, "alert.paused-all", Map.of())));
            case QUIET -> {
            }
        }
        return lines;
    }

    /** A line that opens {@code player}'s cases when clicked, for staff who may open them. */
    private static MenuRenderer.Line casesLine(MessageService messages, Player staff, String line, String player,
                                               boolean mayOpen) {
        return mayOpen
                ? MenuRenderer.clickableLine(line, CaseLog.historyCommand("nmod", player),
                        messages.format(staff, "alert.hover", Map.of("player", player)), MenuRenderer.ClickAction.RUN)
                : MenuRenderer.text(line);
    }

    /**
     * The alert line for one reader: "Blocked Steve in chat: hello (banned word "x") then mute for 10m".
     * {@code prefix} replaces the locale's chat prefix; {@code null} keeps it.
     */
    static String line(MessageService messages, CommandSender reader, Detection detection, String preview,
                       String prefix) {
        Map<String, String> values = new LinkedHashMap<>();
        if (prefix != null) {
            values.put("prefix", prefix);
        }
        values.put("verdict", messages.text(reader, "alert.verdict." + detection.outcome().verdict().key()));
        values.put("player", detection.player());
        values.put("surface", messages.text(reader, "surface-name." + detection.surface()));
        values.put("message", preview != null ? preview : messages.text(reader, "alert.hidden"));
        values.put("reason", Reasons.describe(messages, reader, detection.reason()));
        String template = DetectionHandler.FOUND_SURFACES.contains(detection.surface()) ? "alert.found" : "alert.line";
        String line = messages.format(reader, template, values);
        String afterwards = ActionText.afterwards(messages, reader, detection.outcome());
        return afterwards.isEmpty() ? line : line + messages.format(reader, "alert.then", Map.of("actions", afterwards));
    }

    private static DiscordAlerts.Alert discordAlert(MessageService messages, CommandSender console,
                                                    Detection detection, String preview) {
        Outcome.Verdict verdict = detection.outcome().verdict();
        String title = messages.plainText(console, "discord.title-" + verdict.key());
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(messages.plainText(console, "discord.field-player"), detection.player());
        String where = messages.plainText(console, "surface-name." + detection.surface());
        // A found map's "message" is where it hangs, which belongs with where, not with what was written.
        boolean found = DetectionHandler.FOUND_SURFACES.contains(detection.surface());
        fields.put(messages.plainText(console, "discord.field-where"),
                found && preview != null ? where + ": " + preview : where);
        fields.put(messages.plainText(console, "discord.field-reason"),
                MessageService.plain(Reasons.describe(messages, console, detection.reason())));
        if (preview != null && !found) {
            fields.put(messages.plainText(console, "discord.field-message"), "`" + preview.replace('`', '\'') + "`");
        }
        fields.put(messages.plainText(console, "discord.field-then"),
                MessageService.plain(ActionText.afterwards(messages, console, detection.outcome())));
        return new DiscordAlerts.Alert(title, verdict == Outcome.Verdict.BLOCKED ? RISK : REVIEW, fields);
    }

    static String preview(String message) {
        if (message == null || message.isBlank()) {
            return "";
        }
        String flattened = message.replace('\n', ' ').replace('\r', ' ').trim();
        return flattened.length() <= MAX_MESSAGE_PREVIEW
                ? flattened
                : flattened.substring(0, MAX_MESSAGE_PREVIEW) + "...";
    }
}
