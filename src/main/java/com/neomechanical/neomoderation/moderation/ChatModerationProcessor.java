package com.neomechanical.neomoderation.moderation;

import com.neomechanical.neomoderation.Permissions;
import com.neomechanical.neomoderation.NeoModerationPlugin;
import com.neomechanical.neomoderation.commands.DurationParser;
import com.neomechanical.neomoderation.config.ModerationSettings;
import com.neomechanical.neomoderation.config.ModerationMode;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class ChatModerationProcessor {
    private final NeoModerationPlugin plugin;
    private final ChatModerationCoordinator coordinator;
    private final PlayerMuteService muteService;
    private final SpamDetector spamDetector;
    private final DetectionHandler handler;
    private final SplitMessageWindow splitMessageWindow;

    public ChatModerationProcessor(
            NeoModerationPlugin plugin,
            ChatModerationCoordinator coordinator,
            PlayerMuteService muteService,
            SpamDetector spamDetector,
            DetectionHandler handler
    ) {
        this(plugin, coordinator, muteService, spamDetector, handler, new SplitMessageWindow());
    }

    public ChatModerationProcessor(
            NeoModerationPlugin plugin,
            ChatModerationCoordinator coordinator,
            PlayerMuteService muteService,
            SpamDetector spamDetector,
            DetectionHandler handler,
            SplitMessageWindow splitMessageWindow
    ) {
        this.plugin = plugin;
        this.coordinator = coordinator;
        this.muteService = muteService;
        this.spamDetector = spamDetector;
        this.handler = handler;
        this.splitMessageWindow = splitMessageWindow;
    }

    /**
     * Checks one chat message. {@code mayWait} is false when another plugin made
     * the player chat from the server thread: local rules still apply, and the
     * cloud, whose answer would hold the server, can then only alert staff.
     */
    public ChatDecision handleChat(Player player, String message, boolean mayWait) {
        if (player.hasPermission(Permissions.BYPASS)) {
            return ChatDecision.allow();
        }

        if (muteService.isMuted(player.getUniqueId())) {
            int remaining = muteService.remainingSeconds(player.getUniqueId());
            plugin.messages().send(player, "mute.blocked", Map.of(
                    "duration", DurationParser.format(remaining)
            ));
            return ChatDecision.block();
        }

        ModerationSettings settings = plugin.settings();
        if (!settings.enabled() || !settings.scanAsyncChat()) {
            return ChatDecision.allow();
        }

        Optional<String> spamReason = spamDetector.checkMessage(
                player.getUniqueId(), player.getName(), message, settings.spam(), System.currentTimeMillis());
        String text = message;
        if (spamReason.isPresent() && SpamDetector.CAPS.equals(spamReason.get())) {
            // Shouting is turned down, not punished: the message goes on in lower case.
            text = message.toLowerCase(Locale.ROOT);
            spamReason = Optional.empty();
        }
        if (spamReason.isPresent()) {
            return toDecision(
                    handler.handle(player, "chat", spamReason.get(), message, DetectionHandler.Disposition.BLOCK),
                    null);
        }

        OfflineModerationResult offlineResult = OfflineModerationEngine.evaluate(text, settings.offline());
        if (offlineResult.flagged()) {
            splitMessageWindow.clear(player.getUniqueId());
            String censored = settings.chatCensorLocal()
                    ? OfflineModerationEngine.censorCompletely(text, settings.offline()).orElse(null)
                    : null;
            DetectionHandler.Disposition requested = censored != null
                    ? DetectionHandler.Disposition.CENSOR
                    : DetectionHandler.Disposition.BLOCK;
            return toDecision(
                    handler.handle(player, "chat", offlineResult.reason(), message, requested),
                    censored);
        }

        Optional<String> splitReason = splitMessageWindow.recordAndCheck(
                player.getUniqueId(), text, settings.offline());
        if (splitReason.isPresent()) {
            return toDecision(
                    handler.handle(player, "chat", splitReason.get(), message, DetectionHandler.Disposition.BLOCK),
                    null);
        }

        ChatDecision passed = text.equals(message) ? ChatDecision.allow() : ChatDecision.censor(text);
        if (settings.api().apiKey().isBlank() || settings.categories().enabledCount() == 0) {
            return passed;
        }
        String checked = text;
        if (settings.cloudMode() == ModerationMode.MONITOR || !mayWait) {
            // The cloud can only alert staff here, so the message goes now and the answer follows it.
            coordinator.checkInBackground(player, checked, settings, result -> {
                if (result.isFlagged()) {
                    handler.handle(player, "chat", result.reason(), checked, DetectionHandler.Disposition.ALLOW,
                            DetectionHandler.Source.CLOUD);
                }
            });
            return passed;
        }
        ModerationApiResult cloudResult = coordinator.checkMessage(player, checked, settings);
        if (!cloudResult.isFlagged()) {
            if (cloudResult.kind() != ModerationApiResult.Kind.CLEAR && !settings.failOpen()
                    && settings.cloudMode() == ModerationMode.ENFORCE) {
                plugin.messages().send(player, "chat.cloud-unavailable");
                return ChatDecision.block();
            }
            return passed;
        }
        return toDecision(
                handler.handle(player, "chat", cloudResult.reason(), checked, DetectionHandler.Disposition.BLOCK,
                        DetectionHandler.Source.CLOUD),
                null);
    }

    /** Drops what is held for a player who left, such as the fragments of a split message. */
    public void forget(UUID player) {
        splitMessageWindow.clear(player);
    }

    private static ChatDecision toDecision(DetectionHandler.Disposition effective, String censored) {
        return switch (effective) {
            case ALLOW -> ChatDecision.allow();
            case BLOCK -> ChatDecision.block();
            case CENSOR -> censored != null ? ChatDecision.censor(censored) : ChatDecision.block();
        };
    }
}
