package com.neomechanical.neomoderation.commands.subcommands;

import com.neomechanical.neomoderation.NeoModerationPlugin;
import com.neomechanical.neomoderation.commands.SubCommand;
import com.neomechanical.neomoderation.commands.TrialPromptSupport;
import com.neomechanical.neomoderation.config.ConfigPaths;
import com.neomechanical.neomoderation.config.ModerationApiSettings;
import com.neomechanical.neomoderation.messages.MessageService;
import com.neomechanical.neomoderation.moderation.CloudPlanWatch;
import com.neomechanical.neomoderation.moderation.CloudRecovery;
import com.neomechanical.neomoderation.moderation.CloudWiring;
import com.neomechanical.neomoderation.moderation.TrialClient;
import com.neomechanical.neomoderation.platform.InstallIdentity;
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
    private final TrialStatusView view;

    /** How often a busy activation is asked again, and the longest wait between two asks. */
    static final int BUSY_ATTEMPTS = 3;
    static final long MAX_BUSY_WAIT_SECONDS = 60;

    /** The refusals a move or a come-back can meet, each with its own words ({@code trial.refused-<code>}). */
    static final List<String> REFUSAL_CODES = List.of("free_plan_not_open", "trial_ended", "trial_claimed",
            "install_mismatch", "already_free_plan", "already_back", "trial_revoked", "trial_running");

    public TrialCmd(NeoModerationPlugin plugin) {
        this(plugin, plugin.trialClient() != null ? plugin.trialClient() : new TrialClient());
    }

    public TrialCmd(NeoModerationPlugin plugin, TrialClient trialClient) {
        this.plugin = plugin;
        this.trialClient = trialClient;
        this.view = new TrialStatusView(plugin);
    }

    @Override
    public String getName() {
        return "trial";
    }

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
            view.explain(sender, label);
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
                reply(sender, () -> view.sendFailure(sender, e));
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
                // A wait longer than the backoff allows (the hourly cap's ten minutes) is said at once, not waited out.
                if (e.error() != TrialClient.TrialError.BUSY || attempt >= BUSY_ATTEMPTS
                        || e.retryAfterSeconds() > MAX_BUSY_WAIT_SECONDS) {
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
        reply(sender, () -> view.sendActivated(sender, result, moderationOn));
    }

    /**
     * Moves the running trial onto the free plan, keeping its key: the admin asked for it in game. The plan's status
     * then shows the free plan's day.
     */
    private void moveToFreePlan(CommandSender sender) {
        plugin.messages().send(sender, "trial.free-moving");
        ModerationApiSettings api = plugin.settings().api();
        plugin.runAsync(() -> {
            try {
                TrialClient.TrialStatusResult status = trialClient.convertToFreePlan(api);
                plugin.cloudPlan().accept(api.apiKey(), status);
                reply(sender, () -> view.sendMoved(sender, status));
            } catch (TrialClient.TrialException e) {
                // A 401 from the move says only that the key is no trial's (an account's key gets one too), so the
                // plan watch is not told the key was rejected.
                reply(sender, () -> view.sendFailure(sender, e));
            }
        });
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
                CloudWiring.freePlanAnswered(plugin.coordinator(), plugin.cloudPlan().current());
                long caught = TrialPromptSupport.caughtDuringTrial(plugin, status);
                reply(sender, () -> view.sendStatus(sender, status, caught));
            } catch (TrialClient.TrialException e) {
                if (e.error() == TrialClient.TrialError.FORBIDDEN) {
                    plugin.cloudPlan().rejected(api.apiKey());
                }
                reply(sender, () -> view.sendStatusFailure(sender, e));
            }
        });
    }

    private void reply(CommandSender sender, Runnable task) {
        if (sender instanceof Player player) {
            plugin.runForEntity(player, task);
        } else {
            plugin.runSync(task);
        }
    }
}
