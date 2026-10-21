package com.neomechanical.neomoderation.commands;

import com.neomechanical.neomoderation.NeoModerationPlugin;
import com.neomechanical.neomoderation.Permissions;
import com.neomechanical.neomoderation.messages.MenuRenderer;
import com.neomechanical.neomoderation.messages.MessageService;
import com.neomechanical.neomoderation.moderation.TrialClient;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.net.URI;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * The clickable "start a free cloud trial" line, shown in command output to an
 * in-game admin while the server has no cloud key. Never sent unasked, and the
 * click only explains the trial: starting it takes a second click.
 */
public final class TrialPromptSupport {

    /** How long a trial runs, to count back from its end when the platform does not say when it began. */
    private static final int TRIAL_DAYS = 14;

    private TrialPromptSupport() {
    }

    /**
     * What the cloud caught since the trial began, from the case history; -1 when there is none to read. Off the
     * main thread: it reads the case history.
     */
    public static long caughtDuringTrial(NeoModerationPlugin plugin, TrialClient.TrialStatusResult status) {
        if (status == null || !status.isTrial() || !plugin.settings().cases().enabled()) {
            return -1L;
        }
        Instant started = trialStart(status);
        return started == null ? -1L : plugin.caseLog().countCloudSince(started.toEpochMilli());
    }

    /** When the trial began: the platform's {@code activatedAt}, else 14 days before its end. */
    static Instant trialStart(TrialClient.TrialStatusResult status) {
        try {
            if (status.activatedAt() != null && !status.activatedAt().isBlank()) {
                return Instant.parse(status.activatedAt());
            }
            return Instant.parse(status.expiresAt()).minus(TRIAL_DAYS, ChronoUnit.DAYS);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** What the cloud caught: from the case history over the whole trial, else since startup. */
    public record Caught(long count, boolean wholeTrial) {
    }

    /**
     * What the cloud caught, given the count over the trial ({@code -1} without a case history) and the count since
     * startup; null at zero, which says nothing.
     */
    public static Caught caught(long caughtDuringTrial, long sinceStartup) {
        boolean wholeTrial = caughtDuringTrial >= 0;
        long count = wholeTrial ? caughtDuringTrial : sinceStartup;
        return count > 0 ? new Caught(count, wholeTrial) : null;
    }

    /**
     * A button in game that opens {@code url}. Its hover names only the site: a trial's claim link keeps the trial for
     * whoever opens it first, and a tooltip ends up in screenshots.
     */
    public static void sendLinkButton(MessageService messages, Player player, String labelKey, String url) {
        messages.sendMenu(player, List.of(MenuRenderer.clickableLine(messages.text(player, labelKey), url,
                messages.format(player, "trial.keep-hover", Map.of("url", siteOf(url))),
                MenuRenderer.ClickAction.OPEN_URL)));
    }

    static String siteOf(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null ? url : host;
        } catch (IllegalArgumentException e) {
            return url;
        }
    }

    public static void sendTrialCtaIfPlayer(NeoModerationPlugin plugin, CommandSender sender) {
        if (!(sender instanceof Player) || !sender.hasPermission(Permissions.ADMIN)
                || !plugin.settings().api().apiKey().isBlank()) {
            return;
        }
        plugin.messages().sendMenu(sender, List.of(MenuRenderer.clickableLine(
                plugin.messages().text(sender, "trial.cta-button"), "/nmod trial",
                plugin.messages().text(sender, "trial.cta-hover"), MenuRenderer.ClickAction.RUN)));
    }
}
