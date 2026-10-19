package com.neomechanical.neomoderation.moderation;

import com.neomechanical.neomoderation.NeoModerationPlugin;
import com.neomechanical.neomoderation.commands.DurationParser;
import com.neomechanical.neomoderation.config.ModerationSettings;
import com.neomechanical.neomoderation.config.ModerationMode;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Optional;

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

    public ChatDecision handleAsyncChat(Player player, String message) {
        if (player.hasPermission("neomoderation.bypass")) {
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
        if (spamReason.isPresent()) {
            return toDecision(
                    handler.handle(player, "chat", spamReason.get(), message, DetectionHandler.Disposition.BLOCK),
                    null);
        }

        OfflineModerationResult offlineResult = OfflineModerationEngine.evaluate(message, settings.offline());
        if (offlineResult.flagged()) {
            splitMessageWindow.clear(player.getUniqueId());
            String censored = null;
            DetectionHandler.Disposition requested = DetectionHandler.Disposition.BLOCK;
            if (settings.chatCensorLocal()) {
                String masked = OfflineModerationEngine.censor(message, settings.offline());
                if (!masked.equals(message)) {
                    censored = masked;
                    requested = DetectionHandler.Disposition.CENSOR;
                }
            }
            return toDecision(
                    handler.handle(player, "chat", offlineResult.reason(), message, requested),
                    censored);
        }

        Optional<String> splitReason = splitMessageWindow.recordAndCheck(
                player.getUniqueId(), message, settings.offline());
        if (splitReason.isPresent()) {
            return toDecision(
                    handler.handle(player, "chat", splitReason.get(), message, DetectionHandler.Disposition.BLOCK),
                    null);
        }

        if (settings.api().apiKey().isBlank() || settings.categories().enabledCount() == 0) {
            return ChatDecision.allow();
        }
        ModerationApiResult cloudResult = coordinator.checkMessage(player, message, settings);
        if (!cloudResult.isFlagged()) {
            if (cloudResult.kind() != ModerationApiResult.Kind.CLEAR && !settings.failOpen()
                    && settings.cloudMode() == ModerationMode.ENFORCE) {
                plugin.messages().send(player, "chat.cloud-unavailable");
                return ChatDecision.block();
            }
            return ChatDecision.allow();
        }
        return toDecision(
                handler.handle(player, "chat", cloudResult.reason(), message, DetectionHandler.Disposition.BLOCK,
                        DetectionHandler.Source.CLOUD),
                null);
    }

    private static ChatDecision toDecision(DetectionHandler.Disposition effective, String censored) {
        return switch (effective) {
            case ALLOW -> ChatDecision.allow();
            case BLOCK -> ChatDecision.block();
            case CENSOR -> censored != null ? ChatDecision.censor(censored) : ChatDecision.block();
        };
    }
}
