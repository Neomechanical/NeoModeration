package com.neomechanical.neomoderation.commands.subcommands;

import com.neomechanical.neomoderation.NeoModerationPlugin;
import com.neomechanical.neomoderation.commands.SubCommand;
import com.neomechanical.neomoderation.commands.TrialPromptSupport;
import com.neomechanical.neomoderation.config.ConfigPaths;
import com.neomechanical.neomoderation.config.ModerationApiSettings;
import com.neomechanical.neomoderation.messages.DateText;
import com.neomechanical.neomoderation.messages.MenuRenderer;
import com.neomechanical.neomoderation.messages.MessageService;
import com.neomechanical.neomoderation.moderation.CloudPlanNotices;
import com.neomechanical.neomoderation.moderation.CloudPlanWatch;
import com.neomechanical.neomoderation.moderation.CloudRecovery;
import com.neomechanical.neomoderation.moderation.FreePlan;
import com.neomechanical.neomoderation.moderation.TrialClient;
import com.neomechanical.neomoderation.platform.InstallIdentity;
import com.neomechanical.neomoderation.platform.InstallTelemetry;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The server's free cloud plan, or its 14-day trial where the free plan is not open. {@code /nmod trial} says what
 * starting sends, how its decisions work and that starting accepts the terms, then offers {@code /nmod trial start};
 * with a key set it shows how the plan is going (today's checks, or a trial's days left and what the cloud caught) and
 * the button that keeps it in an account, in game only (its link is a key to the plan). {@code /nmod trial free} moves
 * a running trial onto the free plan with the admin's yes, and {@code /nmod trial start} brings a trial that ran out
 * back on the free plan, once.
 */
public class TrialCmd implements SubCommand {
    private final NeoModerationPlugin plugin;
    private final TrialClient trialClient;

    public TrialCmd(NeoModerationPlugin plugin) {
        this(plugin, plugin.trialClient() != null ? plugin.trialClient() : new TrialClient());
    }

    public TrialCmd(NeoModerationPlugin plugin, TrialClient trialClient) {
        this.plugin = plugin;
        this.trialClient = trialClient;
    }

    @Override
    public String getName() {
        return "trial";
    }

    /** How often a busy activation is asked again, and the longest wait between two asks. */
    static final int BUSY_ATTEMPTS = 3;
    static final long MAX_BUSY_WAIT_SECONDS = 60;

    @Override
    public List<String> onTabComplete(CommandSender sender, String[] args) {
        return args.length == 2 ? SubCommand.filterPrefix(args[1], "start", "status", "free") : List.of();
    }

    @Override
    public void execute(CommandSender sender, String label, String[] args) {
        String existingKey = plugin.settings().api().apiKey();
        String action = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "";
        CloudPlanWatch.Snapshot plan = plugin.cloudPlan().current();
        if (action.equals("start") && existingKey.isBlank()) {
            start(sender, false);
        } else if (action.equals("start") && plan.state() == CloudPlanWatch.State.TRIAL_ENDED
                && !plan.status().isRevoked()) {
            // A trial that ran out comes back on the free plan, once, with a new key.
            start(sender, true);
        } else if (action.equals("free") && !existingKey.isBlank()) {
            moveToFreePlan(sender);
        } else if (action.equals("status") || !existingKey.isBlank()) {
            showStatus(sender, existingKey);
        } else {
            explain(sender, label);
        }
    }

    /** What a trial sends and how its decisions work, before anything is sent. */
    private void explain(CommandSender sender, String label) {
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

    /**
     * Starts the server's cloud plan: the free plan where it is open, else a trial. {@code comeBack}: a trial that ran
     * out comes back on the free plan. A busy service is asked again, at most {@link #BUSY_ATTEMPTS} times, after the
     * wait it gives (at least 5, 10, then 20 seconds, never above {@link #MAX_BUSY_WAIT_SECONDS}), and the admin is
     * told each wait.
     */
    private void start(CommandSender sender, boolean comeBack) {
        if (!plugin.tryStartTrialActivation()) {
            plugin.messages().send(sender, "trial.pending");
            return;
        }
        plugin.messages().send(sender, "trial.requesting");
        ModerationApiSettings api = plugin.settings().api();
        String platform = plugin.getServer().getName() + " " + plugin.getServer().getVersion();
        plugin.runAsync(() -> {
            try {
                String installId = InstallIdentity.getOrCreate(plugin);
                TrialClient.TrialResult result = activateWhenNotBusy(sender, api, installId, platform, comeBack);
                plugin.runSync(() -> {
                    try {
                        saveTrialKey(sender, api, result, comeBack);
                    } finally {
                        plugin.finishTrialActivation();
                    }
                });
            } catch (TrialClient.TrialException e) {
                plugin.finishTrialActivation();
                reply(sender, () -> sendFailure(sender, e));
            } catch (RuntimeException e) {
                plugin.finishTrialActivation();
                throw e;
            }
        });
    }

    /** Off the main thread: the activation, asked again while the service is busy. */
    private TrialClient.TrialResult activateWhenNotBusy(CommandSender sender, ModerationApiSettings api,
                                                        String installId, String platform, boolean comeBack)
            throws TrialClient.TrialException {
        for (int attempt = 1; ; attempt++) {
            try {
                return trialClient.activate(api, installId, platform, comeBack);
            } catch (TrialClient.TrialException e) {
                if (e.error() != TrialClient.TrialError.BUSY || attempt >= BUSY_ATTEMPTS) {
                    throw e;
                }
                long wait = busyWait(e.retryAfterSeconds(), attempt);
                reply(sender, () -> plugin.messages().send(sender, "trial.busy-retrying",
                        Map.of("seconds", String.valueOf(wait))));
                try {
                    Thread.sleep(wait * 1000L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    /** The wait before ask number {@code attempt + 1}: what the service asked, at least 5 s doubling, at most 60 s. */
    static long busyWait(long askedSeconds, int attempt) {
        long backoff = 5L << Math.max(0, attempt - 1);
        return Math.min(MAX_BUSY_WAIT_SECONDS, Math.max(askedSeconds, backoff));
    }

    private void saveTrialKey(CommandSender sender, ModerationApiSettings asked, TrialClient.TrialResult result,
                              boolean comeBack) {
        // Checked under the config lock, so a key an admin set meanwhile is never overwritten: a new plan only where
        // there was no key, a trial come back only over the ended trial's own key.
        boolean saved = plugin.editConfig(config -> {
            String current = plugin.settings().api().apiKey();
            if (!(comeBack ? current.equals(asked.apiKey()) : current.isBlank())
                    || !plugin.settings().api().endpoint().equals(asked.endpoint())) {
                return false;
            }
            config.set(ConfigPaths.CLOUD_MODE, "monitor");
            config.set(ConfigPaths.API_KEY, result.apiKey());
            return true;
        });
        if (!saved) {
            reply(sender, () -> plugin.messages().send(sender, "trial.configuration-changed"));
            return;
        }
        boolean moderationOn = plugin.settings().enabled();
        reply(sender, () -> {
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
        });
    }

    /**
     * Moves the running trial onto the free plan, keeping its key: the admin asked for it in game. The plan's status
     * then shows the free plan's day.
     */
    private void moveToFreePlan(CommandSender sender) {
        MessageService messages = plugin.messages();
        messages.send(sender, "trial.free-moving");
        ModerationApiSettings api = plugin.settings().api();
        plugin.runAsync(() -> {
            try {
                TrialClient.TrialStatusResult status = trialClient.convertToFreePlan(api);
                plugin.cloudPlan().accept(api.apiKey(), status);
                reply(sender, () -> {
                    messages.send(sender, "trial.free-moved", freePlanValues(messages, sender, status.freePlan()));
                    if (status.freePlan() != null && !status.freePlan().claimed() && !status.upgradeUrl().isBlank()) {
                        sendKeepButton(sender, status.upgradeUrl());
                    }
                });
            } catch (TrialClient.TrialException e) {
                if (e.error() == TrialClient.TrialError.FORBIDDEN) {
                    plugin.cloudPlan().rejected(api.apiKey());
                }
                reply(sender, () -> sendFailure(sender, e));
            }
        });
    }

    /** A free plan's day as message values: its checks and pictures, and when they start again. */
    private Map<String, String> freePlanValues(MessageService messages, CommandSender sender, FreePlan plan) {
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

    private void showStatus(CommandSender sender, String key) {
        MessageService messages = plugin.messages();
        if (key == null || key.isBlank()) {
            messages.send(sender, "trial.status-no-key", Map.of("url", CloudRecovery.SIGNUP_URL));
            return;
        }
        messages.send(sender, "trial.status-checking");
        ModerationApiSettings api = plugin.settings().api();
        plugin.runAsync(() -> {
            try {
                TrialClient.TrialStatusResult status = trialClient.fetchTrialStatus(api);
                plugin.cloudPlan().accept(api.apiKey(), status);
                long caught = TrialPromptSupport.caughtDuringTrial(plugin, status);
                reply(sender, () -> sendStatus(sender, status, caught));
            } catch (TrialClient.TrialException e) {
                if (e.error() == TrialClient.TrialError.FORBIDDEN) {
                    plugin.cloudPlan().rejected(api.apiKey());
                }
                reply(sender, () -> sendStatusFailure(sender, e));
            }
        });
    }

    private void sendStatus(CommandSender sender, TrialClient.TrialStatusResult status, long caught) {
        MessageService messages = plugin.messages();
        if (status.onFreePlan()) {
            sendFreePlanStatus(sender, status);
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
    private void sendKeepButton(CommandSender sender, String claimUrl) {
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
    private void sendStatusFailure(CommandSender sender, TrialClient.TrialException e) {
        MessageService messages = plugin.messages();
        // From the status call a 401 means only that the key was rejected (TrialClient.fetchTrialStatus).
        String error = e.error() == TrialClient.TrialError.FORBIDDEN ? "key-rejected" : errorKey(e.error());
        messages.send(sender, "trial.status-failed", Map.of("error", messages.text(sender, "trial.error." + error)));
    }

    /** The refusals a move or a come-back can meet, each with its own words ({@code trial.refused-<code>}). */
    static final List<String> REFUSAL_CODES = List.of("free_plan_not_open", "trial_ended", "trial_claimed",
            "install_mismatch", "already_free_plan", "already_back", "trial_revoked", "trial_running");

    private void sendFailure(CommandSender sender, TrialClient.TrialException e) {
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
                if (REFUSAL_CODES.contains(e.code())) {
                    messages.send(sender, "trial.refused-" + e.code(), signup);
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

    private void reply(CommandSender sender, Runnable task) {
        if (sender instanceof Player player) {
            plugin.runForEntity(player, task);
        } else {
            plugin.runSync(task);
        }
    }
}
