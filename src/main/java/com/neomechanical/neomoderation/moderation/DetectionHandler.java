package com.neomechanical.neomoderation.moderation;

import com.neomechanical.neomoderation.NeoModerationPlugin;
import com.neomechanical.neomoderation.config.ModerationAction;
import com.neomechanical.neomoderation.config.ModerationMode;
import com.neomechanical.neomoderation.config.ModerationSettings;
import com.neomechanical.neomoderation.messages.Reasons;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The single end-to-end path for every detection, on every surface: counts it,
 * records a strike, runs actions and escalation (enforce only), tells the player
 * why, alerts staff, and appends the case row. Callers pass the disposition they
 * want (ALLOW = alert only, CENSOR, BLOCK); monitor mode always downgrades to
 * alert only.
 */
public final class DetectionHandler {
    public enum Disposition {
        ALLOW,
        CENSOR,
        BLOCK
    }

    /**
     * Which engine decided. Local rules are deterministic and admin-authored;
     * cloud decisions are model judgements. They are allowed to run at different
     * enforcement levels, so a fresh install can block what the admin configured
     * while still only alerting on what the model believes.
     */
    public enum Source {
        LOCAL,
        CLOUD;

        public ModerationMode modeIn(ModerationSettings settings) {
            return this == CLOUD ? settings.cloudMode() : settings.mode();
        }
    }

    /** Surfaces that tell the player themselves, such as map art's own removal message. */
    private static final List<String> SELF_EXPLAINING_SURFACES = List.of("map_art");
    /** Surfaces whose "message" is where something was found, which every case keeps. */
    static final List<String> FOUND_SURFACES = List.of("map_frame");

    private final NeoModerationPlugin plugin;
    private final ChatModerationActionExecutor actionExecutor;
    private final MonitorStats monitorStats;
    private final DetectionNotifier notifier;
    private final StrikeService strikes;

    public DetectionHandler(
            NeoModerationPlugin plugin,
            ChatModerationActionExecutor actionExecutor,
            MonitorStats monitorStats,
            DetectionNotifier notifier,
            StrikeService strikes
    ) {
        this.plugin = plugin;
        this.actionExecutor = actionExecutor;
        this.monitorStats = monitorStats;
        this.notifier = notifier;
        this.strikes = strikes;
    }

    /** Safe from any thread. Returns the disposition the caller must apply. */
    public Disposition handle(Player player, String surface, String reason, String message, Disposition requested) {
        return handle(player, surface, reason, message, requested, Source.LOCAL);
    }

    /** Safe from any thread. Returns the disposition the caller must apply. */
    public Disposition handle(Player player, String surface, String reason, String message,
                              Disposition requested, Source source) {
        ModerationSettings settings = plugin.settings();
        monitorStats.record(reason);

        boolean alertOnly = source.modeIn(settings) == ModerationMode.MONITOR || requested == Disposition.ALLOW;
        Disposition effective = alertOnly ? Disposition.ALLOW : requested;
        int strike = 0;
        List<ModerationAction> actions = new ArrayList<>();
        if (!alertOnly) {
            StrikeService.Result result =
                    strikes.recordStrike(player.getUniqueId(), settings.strikes(), System.currentTimeMillis());
            strike = result.strikes();
            if (effective == Disposition.BLOCK) {
                actions.addAll(settings.actions());
            }
            result.escalation().ifPresent(actions::add);
        }
        Outcome outcome = new Outcome(verdictOf(effective), actions, strike);
        DetectionNotifier.Detection detection =
                new DetectionNotifier.Detection(player.getName(), surface, reason, message, outcome);

        plugin.runForEntity(player, () -> {
            if (!alertOnly && settings.tellPlayers() && !SELF_EXPLAINING_SURFACES.contains(surface)) {
                tellPlayer(player, surface, reason, effective);
            }
            actionExecutor.execute(player, actions);
            notifier.notifyDetection(detection, settings);
        });
        recordCase(player, detection, settings);
        return effective;
    }

    /**
     * A detection nobody can be blamed for, such as flagged map art already
     * hanging in an item frame: counted, alerted and logged with where it was,
     * with no strike, action or notice for {@code finder}, who only came across it.
     */
    public Disposition handleFound(Player finder, String surface, String reason, String where,
                                   Disposition requested, Source source) {
        ModerationSettings settings = plugin.settings();
        monitorStats.record(reason);
        boolean alertOnly = source.modeIn(settings) == ModerationMode.MONITOR || requested == Disposition.ALLOW;
        Disposition effective = alertOnly ? Disposition.ALLOW : requested;
        DetectionNotifier.Detection detection = new DetectionNotifier.Detection(
                finder.getName(), surface, reason, where, new Outcome(verdictOf(effective), List.of(), 0));
        plugin.runForEntity(finder, () -> notifier.notifyDetection(detection, settings));
        recordCase(finder, detection, settings);
        return effective;
    }

    private void tellPlayer(Player player, String surface, String reason, Disposition effective) {
        String key = effective == Disposition.CENSOR ? "notice.censored" : "notice." + surface;
        String why = plugin.messages().text(player, Reasons.whyKey(reason));
        plugin.messages().send(player, key, Map.of("why", why));
    }

    private static Outcome.Verdict verdictOf(Disposition effective) {
        return switch (effective) {
            case ALLOW -> Outcome.Verdict.REVIEW;
            case CENSOR -> Outcome.Verdict.CENSORED;
            case BLOCK -> Outcome.Verdict.BLOCKED;
        };
    }

    private void recordCase(Player player, DetectionNotifier.Detection detection, ModerationSettings settings) {
        if (!settings.cases().enabled() || plugin.caseLog() == null) {
            return;
        }
        long now = System.currentTimeMillis();
        String uuid = player.getUniqueId().toString();
        boolean keepText = settings.cases().storeContent() || FOUND_SURFACES.contains(detection.surface());
        String preview = keepText ? DetectionNotifier.preview(detection.message()) : "";
        String mode = detection.outcome().verdict() == Outcome.Verdict.REVIEW ? "monitor" : "enforce";
        String encoded = detection.outcome().encode();
        plugin.runAsync(() -> plugin.caseLog().record(now, uuid, detection.player(), detection.surface(),
                detection.reason(), encoded, mode, preview));
    }
}
