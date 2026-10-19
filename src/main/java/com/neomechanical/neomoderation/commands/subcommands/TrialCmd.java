package com.neomechanical.neomoderation.commands.subcommands;

import com.neomechanical.neomoderation.NeoModerationPlugin;
import com.neomechanical.neomoderation.commands.SubCommand;
import com.neomechanical.neomoderation.config.ModerationApiSettings;
import com.neomechanical.neomoderation.messages.DateText;
import com.neomechanical.neomoderation.messages.MenuRenderer;
import com.neomechanical.neomoderation.messages.MessageService;
import com.neomechanical.neomoderation.moderation.CloudRecovery;
import com.neomechanical.neomoderation.moderation.TrialClient;
import com.neomechanical.neomoderation.platform.InstallIdentity;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Starts the free 14-day cloud trial for this server install, or, once a key is
 * set, shows how the trial is going: days left, what the cloud caught, and the
 * link to keep it.
 */
public class TrialCmd implements SubCommand {
    private static final String KEY_PATH = "moderation.api.apiKey";
    private static final String ENABLED_PATH = "moderation.enabled";

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
        return args.length == 2 ? SubCommand.filterPrefix(args[1], "status") : List.of();
    }

    @Override
    public void execute(CommandSender sender, String label, String[] args) {
        String existingKey = plugin.settings().api().apiKey();
        boolean statusAsked = args.length >= 2 && "status".equalsIgnoreCase(args[1]);
        if (statusAsked || !existingKey.isBlank()) {
            showStatus(sender, existingKey);
            return;
        }
        if (!plugin.tryStartTrialActivation()) {
            plugin.messages().send(sender, "trial.pending");
            return;
        }
        plugin.messages().send(sender, "trial.requesting");
        ModerationApiSettings api = plugin.settings().api();
        String installId = InstallIdentity.getOrCreate(plugin);
        String platform = plugin.getServer().getName() + " " + plugin.getServer().getVersion();
        plugin.runAsync(() -> {
            try {
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
        if (!plugin.settings().api().apiKey().isBlank() || !plugin.settings().api().endpoint().equals(asked.endpoint())) {
            reply(sender, () -> plugin.messages().send(sender, "trial.configuration-changed"));
            return;
        }
        plugin.getConfig().set(ENABLED_PATH, true);
        plugin.getConfig().set("moderation.cloudMode", "monitor");
        plugin.getConfig().set(KEY_PATH, result.apiKey());
        plugin.saveAndReload();
        reply(sender, () -> {
            MessageService messages = plugin.messages();
            messages.send(sender, "trial.activated", Map.of("expires", DateText.of(messages, sender, result.expiresAt())));
            messages.send(sender, "trial.activated-monitor");
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
                reply(sender, () -> sendStatus(sender, status));
            } catch (TrialClient.TrialException e) {
                reply(sender, () -> sendFailure(sender, e));
            }
        });
    }

    private void sendStatus(CommandSender sender, TrialClient.TrialStatusResult status) {
        MessageService messages = plugin.messages();
        if (!status.isTrial()) {
            messages.send(sender, "trial.status-standard");
            return;
        }
        String expires = DateText.of(messages, sender, status.expiresAt());
        if (status.isExpired()) {
            messages.send(sender, "trial.status-expired", Map.of("expires", expires));
        } else {
            messages.send(sender, "trial.status-active",
                    Map.of("days", String.valueOf(status.daysRemaining()), "expires", expires));
            messages.send(sender, "trial.status-caught",
                    Map.of("count", String.valueOf(plugin.monitorStats().cloudTotal())));
        }
        sendKeepButton(sender, status.upgradeUrl());
    }

    private void sendKeepButton(CommandSender sender, String url) {
        MessageService messages = plugin.messages();
        if (sender instanceof Player) {
            messages.sendMenu(sender, List.of(MenuRenderer.clickableLine(messages.text(sender, "trial.keep-button"), url,
                    messages.format(sender, "trial.keep-hover", Map.of("url", url)), MenuRenderer.ClickAction.OPEN_URL)));
        } else {
            sender.sendMessage(messages.text(sender, "trial.keep-button") + " " + url);
        }
    }

    private void sendFailure(CommandSender sender, TrialClient.TrialException e) {
        MessageService messages = plugin.messages();
        switch (e.error()) {
            case ALREADY_CLAIMED -> messages.send(sender, "trial.already-claimed", Map.of("url", CloudRecovery.BILLING_URL));
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
