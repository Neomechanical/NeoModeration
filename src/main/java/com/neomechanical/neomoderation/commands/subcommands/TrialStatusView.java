package com.neomechanical.neomoderation.commands.subcommands;

import com.neomechanical.neomoderation.NeoModerationPlugin;
import com.neomechanical.neomoderation.commands.TrialPromptSupport;
import com.neomechanical.neomoderation.messages.DateText;
import com.neomechanical.neomoderation.messages.MenuRenderer;
import com.neomechanical.neomoderation.messages.MessageService;
import com.neomechanical.neomoderation.moderation.CloudPlanNotices;
import com.neomechanical.neomoderation.moderation.CloudRecovery;
import com.neomechanical.neomoderation.moderation.FreePlan;
import com.neomechanical.neomoderation.moderation.TrialClient;
import com.neomechanical.neomoderation.platform.InstallTelemetry;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Renders {@code /nmod trial} explanations, status summaries, action buttons and failure guidance.
 * Separated from {@link TrialCmd} command orchestration (matching {@link CasesCmd} + {@link CaseView}).
 */
final class TrialStatusView {
    private final NeoModerationPlugin plugin;

    TrialStatusView(NeoModerationPlugin plugin) {
        this.plugin = plugin;
    }

    /** What a trial sends and how its decisions work, before anything is sent. */
    void explain(CommandSender sender, String label) {
        MessageService messages = plugin.messages();
        messages.send(sender, "trial.explain-title");
        messages.send(sender, "trial.explain-sends");
        messages.send(sender, "trial.explain-monitor");
        // Starting accepts the terms; the version shown here is the one activation sends (TrialClient.TERMS_VERSION).
        messages.send(sender, "trial.explain-terms", Map.of("terms", CloudRecovery.TERMS_URL,
                "privacy", CloudRecovery.PRIVACY_URL));
        String command = "/" + label + " trial start";
        if (sender instanceof Player) {
            messages.sendMenu(sender, List.of(MenuRenderer.clickableLine(messages.text(sender, "trial.start-button"),
                    command, messages.format(sender, "trial.start-hover", Map.of("command", command)),
                    MenuRenderer.ClickAction.RUN)));
        } else {
            messages.send(sender, "trial.start-console", Map.of("command", command));
        }
    }

    /** A free plan's day as message values: its checks and pictures, and when they start again. */
    Map<String, String> freePlanValues(MessageService messages, CommandSender sender, FreePlan plan) {
        FreePlan known = plan != null ? plan : new FreePlan(0, 0, 0, 0, "", false, false);
        String resets = known.resetsAtOr(plugin.cloudPlan().now()).toString();
        return Map.of(
                "checks", String.valueOf(known.checksToday()),
                "dailyChecks", String.valueOf(known.dailyChecks()),
                "images", String.valueOf(known.imagesToday()),
                "dailyImages", String.valueOf(known.dailyImages()),
                "date", DateText.of(messages, sender, resets),
                "time", DateText.utcTime(resets));
    }

    void sendActivated(CommandSender sender, TrialClient.TrialResult result, boolean moderationOn) {
        MessageService messages = plugin.messages();
        if (result.onFreePlan()) {
            messages.send(sender, "trial.free-activated", freePlanValues(messages, sender, result.freePlan()));
        } else {
            messages.send(sender, "trial.activated",
                    Map.of("expires", DateText.of(messages, sender, result.expiresAt())));
        }
        messages.send(sender, "trial.activated-monitor");
        if (!moderationOn) {
            // Starting a plan never switches moderation back on behind the admin's back.
            messages.send(sender, "trial.moderation-off");
        }
        if (!result.onFreePlan() || !result.claimUrl().isBlank()) {
            sendKeepButton(sender, result.claimUrl());
        }
    }

    void sendMoved(CommandSender sender, TrialClient.TrialStatusResult status) {
        MessageService messages = plugin.messages();
        messages.send(sender, "trial.free-moved", freePlanValues(messages, sender, status.freePlan()));
        if (status.freePlan() != null && !status.freePlan().claimed() && !status.upgradeUrl().isBlank()) {
            sendKeepButton(sender, status.upgradeUrl());
        }
    }

    void sendStatus(CommandSender sender, TrialClient.TrialStatusResult status, long caught) {
        MessageService messages = plugin.messages();
        if (status.onFreePlan()) {
            sendFreePlanStatus(sender, status);
            return;
        }
        if (status.freePlanEnded()) {
            // A stopped free plan cannot come back: its way on is a new account.
            messages.send(sender, "trial.status-free-ended");
            sendButton(sender, "trial.create-account-button", CloudRecovery.SIGNUP_URL);
            return;
        }
        if (!status.isTrial()) {
            messages.send(sender, "trial.status-standard");
            return;
        }
        String expires = DateText.of(messages, sender, status.expiresAt());
        if (status.hasEnded()) {
            // A revoked trial names no date: the service sends the planned end, not the day it stopped.
            messages.send(sender, status.isRevoked() ? "trial.status-revoked" : "trial.status-expired",
                    Map.of("expires", expires));
        } else {
            messages.send(sender, "trial.status-active",
                    Map.of("days", String.valueOf(status.daysRemaining()), "expires", expires));
            if (CloudPlanNotices.creditsUsedUp(plugin.cloudPlan().current(), plugin.coordinator().lastCloudResultKind())) {
                // Credits bought in another account fund another workspace; the trial is kept first.
                messages.send(sender, "trial.status-credits");
            }
            TrialPromptSupport.Caught counted = TrialPromptSupport.caught(caught, plugin.monitorStats().cloudTotal());
            if (counted != null) {
                messages.send(sender, counted.wholeTrial() ? "trial.status-caught" : "trial.status-caught-startup",
                        Map.of("count", String.valueOf(counted.count())));
            }
        }
        if (status.hasEnded()) {
            if (status.freePlanOpen() && !status.isRevoked()) {
                // A trial that ran out can come back on the free plan, once.
                messages.send(sender, "trial.come-back");
                sendCommandButton(sender, "trial.come-back-button", "/nmod trial start");
            }
            // An ended trial cannot be kept: its way on is a new account.
            sendButton(sender, "trial.create-account-button", CloudRecovery.SIGNUP_URL);
        } else {
            if (status.freePlanOpen()) {
                messages.send(sender, "trial.free-offer");
                sendCommandButton(sender, "trial.free-offer-button", "/nmod trial free");
            }
            sendKeepButton(sender, status.upgradeUrl());
        }
    }

    /**
     * The free plan's day, and what to do when anything needs doing: an unclaimed plan's claim button (its link is a
     * key to the plan, so in game only), a pending claim's email, and nothing for a plan an account holds.
     */
    private void sendFreePlanStatus(CommandSender sender, TrialClient.TrialStatusResult status) {
        MessageService messages = plugin.messages();
        FreePlan plan = status.freePlan();
        Map<String, String> values = freePlanValues(messages, sender, plan);
        boolean usedUp = plan.checksUsedUp() || plugin.coordinator().freeChecksBackAt().isPresent();
        if (plan.claimed()) {
            messages.send(sender, usedUp ? "trial.status-free-claimed-over" : "trial.status-free-claimed", values);
            return;
        }
        messages.send(sender, usedUp ? "trial.status-free-capped" : "trial.status-free", values);
        if (plan.claimPending()) {
            messages.send(sender, "trial.status-free-pending");
        } else if (!status.upgradeUrl().isBlank()) {
            messages.send(sender, "trial.status-free-claim");
            sendKeepButton(sender, status.upgradeUrl());
        }
    }

    /** A button in game that runs {@code command}; the console is told the command. */
    private void sendCommandButton(CommandSender sender, String label, String command) {
        MessageService messages = plugin.messages();
        if (sender instanceof Player) {
            messages.sendMenu(sender, List.of(MenuRenderer.clickableLine(messages.text(sender, label), command,
                    messages.format(sender, "trial.start-hover", Map.of("command", command)),
                    MenuRenderer.ClickAction.RUN)));
        } else {
            messages.send(sender, "trial.start-console", Map.of("command", command));
        }
    }

    /**
     * The button that keeps a running trial. Its link is the trial's claim link, which whoever opens it first can use,
     * so only a player gets it, as a button: the console's lines are the server log, which hosts keep and admins paste
     * when they ask for help. The console is told to run the command in game.
     */
    void sendKeepButton(CommandSender sender, String claimUrl) {
        if (!(sender instanceof Player)) {
            plugin.messages().send(sender, "trial.keep-in-game");
            return;
        }
        sendButton(sender, "trial.keep-button", claimUrl == null || claimUrl.isBlank() ? CloudRecovery.SIGNUP_URL : claimUrl);
    }

    /** A button in game; the console, which cannot click, gets its link written out. Never a claim link. */
    private void sendButton(CommandSender sender, String label, String url) {
        if (sender instanceof Player player) {
            TrialPromptSupport.sendLinkButton(plugin.messages(), player, label, url);
        } else {
            sender.sendMessage(plugin.messages().text(sender, label) + " " + url);
        }
    }

    /** Reading the trial failed; nothing was started, so the words are not those of {@link #sendFailure}. */
    void sendStatusFailure(CommandSender sender, TrialClient.TrialException e) {
        MessageService messages = plugin.messages();
        // From the status call a 401 means only that the key was rejected (TrialClient.fetchTrialStatus).
        String error = e.error() == TrialClient.TrialError.FORBIDDEN ? "key-rejected" : errorKey(e.error());
        messages.send(sender, "trial.status-failed", Map.of("error", messages.text(sender, "trial.error." + error)));
    }

    void sendFailure(CommandSender sender, TrialClient.TrialException e) {
        MessageService messages = plugin.messages();
        plugin.noteStartRefusal(InstallTelemetry.startRefusal(e.error(), e.code()));
        Map<String, String> signup = Map.of("url", CloudRecovery.SIGNUP_URL);
        switch (e.error()) {
            // An install that used its trial has no account yet: the way on is one, never billing's sign-in.
            case ALREADY_CLAIMED -> messages.send(sender, "trial.already-claimed", signup);
            case LIFETIME_LIMIT -> messages.send(sender, "trial.lifetime-limit", signup);
            case COOLDOWN -> messages.send(sender, e.retryAt().isBlank() ? "trial.rate-limited" : "trial.cooldown",
                    Map.of("url", CloudRecovery.SIGNUP_URL, "date", DateText.of(messages, sender, e.retryAt())));
            case BUSY -> messages.send(sender, "trial.busy");
            case FREE_PLAN_CLOSED -> messages.send(sender, "trial.free-closed", signup);
            case RATE_LIMITED -> messages.send(sender, "trial.rate-limited", signup);
            case REFUSED -> {
                if (TrialCmd.REFUSAL_CODES.contains(e.code())) {
                    // A trial in an account is managed by signing in to it, never by a new sign-up.
                    messages.send(sender, "trial.refused-" + e.code(), e.code().equals("trial_claimed")
                            ? Map.of("url", CloudRecovery.SIGN_IN_URL) : signup);
                } else {
                    messages.send(sender, "trial.failed", Map.of("error", e.code()));
                }
            }
            default -> messages.send(sender, "trial.failed", Map.of("error",
                    messages.text(sender, "trial.error." + errorKey(e.error()))));
        }
    }

    private static String errorKey(TrialClient.TrialError error) {
        return switch (error) {
            case TRANSPORT_ERROR -> "transport";
            case INVALID_RESPONSE -> "invalid-response";
            case FORBIDDEN -> "forbidden";
            case INVALID_ENDPOINT -> "invalid-endpoint";
            default -> error.name().toLowerCase(Locale.ROOT).replace("_error", "");
        };
    }
}
