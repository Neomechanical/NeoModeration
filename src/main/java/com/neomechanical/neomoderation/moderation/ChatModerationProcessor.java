package com.neomechanical.neomoderation.moderation;

import com.neomechanical.neomoderation.NeoModerationPlugin;
import com.neomechanical.neomoderation.Permissions;
import com.neomechanical.neomoderation.commands.DurationParser;
import com.neomechanical.neomoderation.config.ModerationMode;
import com.neomechanical.neomoderation.config.ModerationSettings;
import com.neomechanical.neomoderation.platform.GameLanguage;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

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
                    "duration", DurationParser.remaining(remaining)
            ));
            return ChatDecision.block();
        }

        ModerationSettings settings = plugin.settings();
        if (!settings.enabled() || !settings.scanAsyncChat()) {
            return ChatDecision.allow();
        }
        MonitorStats stats = plugin.monitorStats();
        if (stats != null) {
            stats.recordChatMessage();
        }

        Optional<String> spamReason = spamDetector.checkMessage(
                player.getUniqueId(), player.getName(), message, settings.spam(), System.currentTimeMillis());
        String speaker = GameLanguage.of(player);
        String text = message;
        if (spamReason.isPresent() && SpamDetector.CAPS.equals(spamReason.get())) {
            // Shouting is turned down, not punished: the message goes on in lower case, the way the
            // player's language writes it ("IŞIK" is "ışık" in Turkish). Local monitor mode changes nothing.
            if (settings.mode() == ModerationMode.ENFORCE) {
                text = message.toLowerCase(speaker == null ? Locale.ROOT : Locale.forLanguageTag(speaker.replace('_', '-')));
            }
            spamReason = Optional.empty();
        }
        if (spamReason.isPresent()) {
            return toDecision(
                    handler.handle(player, "chat", spamReason.get(), message, DetectionHandler.Disposition.BLOCK),
                    null);
        }

        OfflineModerationResult offlineResult = OfflineModerationEngine.evaluate(text, settings.offline(), speaker);
        if (offlineResult.flagged()) {
            splitMessageWindow.clear(player.getUniqueId());
            String censored = settings.chatCensorLocal()
                    ? OfflineModerationEngine.censorCompletely(text, settings.offline(), speaker).orElse(null)
                    : null;
            DetectionHandler.Disposition requested = censored != null
                    ? DetectionHandler.Disposition.CENSOR
                    : DetectionHandler.Disposition.BLOCK;
            return toDecision(
                    handler.handle(player, "chat", offlineResult.reason(), message, requested),
                    censored);
        }

        Optional<String> splitReason = splitMessageWindow.recordAndCheck(
                player.getUniqueId(), text, settings.offline(), speaker);
        if (splitReason.isPresent()) {
            return toDecision(
                    handler.handle(player, "chat", splitReason.get(), message, DetectionHandler.Disposition.BLOCK),
                    null);
        }

        ChatDecision passed = text.equals(message) ? ChatDecision.allow() : ChatDecision.soften(text);
        if (settings.api().apiKey().isBlank() || settings.categories().enabledCount() == 0) {
            return passed;
        }
        String checked = text;
        if (!coordinator.playerBudget().tryTake(player.getUniqueId(), freePlanDailyChecks(), System.currentTimeMillis())) {
            // This player used their share of the free plan's day: local rules alone check them until 00:00 UTC. A
            // server that set failOpen to false holds their chat instead, as it would any message the cloud cannot check.
            if (!settings.failOpen() && settings.cloudMode() == ModerationMode.ENFORCE && mayWait) {
                plugin.messages().send(player, "chat.cloud-unavailable");
                return ChatDecision.block();
            }
            return passed;
        }
        if (settings.cloudMode() == ModerationMode.MONITOR || !mayWait) {
            // The cloud can only alert staff here, so the message goes now and the answer follows it.
            coordinator.checkInBackground(player, checked, settings, lateAnswer(player, checked, false));
            return passed;
        }
        // An answer too late to stop the message only tells staff; with no answer in time, fail-closed blocked it.
        ModerationApiResult cloudResult = coordinator.checkMessage(player, checked, settings,
                lateAnswer(player, checked, !settings.failOpen()));
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

    /** The free plan's checks a day, which each player gets a share of; 0 for any other key, which has no day. */
    private int freePlanDailyChecks() {
        CloudPlanWatch watch = plugin.cloudPlan();
        CloudPlanWatch.Snapshot plan = watch == null ? CloudPlanWatch.Snapshot.UNKNOWN : watch.current();
        return plan.state() == CloudPlanWatch.State.FREE_PLAN ? plan.status().freePlan().dailyChecks() : 0;
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

    /** A cloud answer that came after the message went its way: staff hear about it if it flagged. */
    private Consumer<ModerationApiResult> lateAnswer(Player player, String message, boolean blocked) {
        return result -> {
            if (result.isFlagged()) {
                handler.handleAfter(player, "chat", result.reason(), message, blocked);
            }
        };
    }
}
