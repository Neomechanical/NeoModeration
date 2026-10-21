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
import com.neomechanical.neomoderation.moderation.CloudRecovery;
import com.neomechanical.neomoderation.moderation.TrialClient;
import com.neomechanical.neomoderation.platform.InstallIdentity;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The free 14-day cloud trial. {@code /nmod trial} says what a trial sends and
 * how its decisions work, then offers {@code /nmod trial start}; with a key set
 * it shows how the trial is going: days left, what the cloud caught, and the
 * button that keeps it, in game only (its link is a key to the trial).
 */
public class TrialCmd implements SubCommand {
    private final NeoModerationPlugin plugin;
    private final TrialClient trialClient;

    public TrialCmd(NeoModerationPlugin plugin) {
        this(plugin, new TrialClient());
    }

    public TrialCmd(NeoModerationPlugin plugin, TrialClient trialClient) {
        this.plugin = plugin;
        this.trialClient = trialClient;
    }

    @Override
    public String getName() {
        return "trial";
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, String[] args) {
        return args.length == 2 ? SubCommand.filterPrefix(args[1], "start", "status") : List.of();
    }

    @Override
    public void execute(CommandSender sender, String label, String[] args) {
        String existingKey = plugin.settings().api().apiKey();
        String action = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "";
        if (action.equals("status") || !existingKey.isBlank()) {
            showStatus(sender, existingKey);
        } else if (action.equals("start")) {
            start(sender);
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
        String command = "/" + label + " trial start";
        if (sender instanceof Player) {
            messages.sendMenu(sender, List.of(MenuRenderer.clickableLine(messages.text(sender, "trial.start-button"),
                    command, messages.format(sender, "trial.start-hover", Map.of("command", command)),
                    MenuRenderer.ClickAction.RUN)));
        } else {
            messages.send(sender, "trial.start-console", Map.of("command", command));
        }
    }

    private void start(CommandSender sender) {
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
                TrialClient.TrialResult result = trialClient.activateTrial(api, installId, platform);
                plugin.runSync(() -> {
                    try {
                        saveTrialKey(sender, api, result);
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

    private void saveTrialKey(CommandSender sender, ModerationApiSettings asked, TrialClient.TrialResult result) {
        // Checked under the config lock, so a key an admin set meanwhile is never overwritten.
        boolean saved = plugin.editConfig(config -> {
            if (!plugin.settings().api().apiKey().isBlank()
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
            messages.send(sender, "trial.activated",
                    Map.of("expires", DateText.of(messages, sender, result.expiresAt())));
            messages.send(sender, "trial.activated-monitor");
            if (!moderationOn) {
                // Starting a trial never switches moderation back on behind the admin's back.
                messages.send(sender, "trial.moderation-off");
            }
            sendKeepButton(sender, result.claimUrl());
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
            // An ended trial cannot be kept: its way on is a new account.
            sendButton(sender, "trial.create-account-button", CloudRecovery.SIGNUP_URL);
        } else {
            sendKeepButton(sender, status.upgradeUrl());
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

    private void sendFailure(CommandSender sender, TrialClient.TrialException e) {
        MessageService messages = plugin.messages();
        switch (e.error()) {
            // An install that used its trial has no account yet: the way on is one, never billing's sign-in.
            case ALREADY_CLAIMED -> messages.send(sender, "trial.already-claimed", Map.of("url", CloudRecovery.SIGNUP_URL));
            case RATE_LIMITED -> messages.send(sender, "trial.rate-limited", Map.of("url", CloudRecovery.SIGNUP_URL));
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
