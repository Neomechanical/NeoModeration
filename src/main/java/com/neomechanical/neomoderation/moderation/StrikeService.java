package com.neomechanical.neomoderation.moderation;

import com.neomechanical.neomoderation.config.ModerationAction;
import com.neomechanical.neomoderation.config.StrikeSettings;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player strike counter with full-reset decay: a player who stays clean for
 * the configured decay window starts from zero. Escalation actions fire exactly
 * once, at the moment the count reaches their rung.
 */
public final class StrikeService {
    public record Result(int strikes, Optional<ModerationAction> escalation) {
    }

    /** Past this many players, counts that have already decayed are dropped. */
    private static final int CLEANUP_THRESHOLD = 1_000;

    private final Map<UUID, State> states = new ConcurrentHashMap<>();

    public Result recordStrike(UUID player, StrikeSettings settings, long now) {
        if (!settings.enabled()) {
            return new Result(0, Optional.empty());
        }
        long decayMs = settings.decayMinutes() * 60_000L;
        if (states.size() > CLEANUP_THRESHOLD) {
            states.values().removeIf(stale -> now - stale.lastStrike > decayMs);
        }
        State state = states.computeIfAbsent(player, ignored -> new State());
        synchronized (state) {
            if (state.count > 0 && now - state.lastStrike > decayMs) {
                state.count = 0;
            }
            state.count++;
            state.lastStrike = now;
            for (StrikeSettings.Escalation rung : settings.escalation()) {
                if (rung.atStrikes() == state.count) {
                    return new Result(state.count, Optional.of(rung.action()));
                }
            }
            return new Result(state.count, Optional.empty());
        }
    }

    /** How many players a count is held for. */
    int tracked() {
        return states.size();
    }

    private static final class State {
        int count;
        long lastStrike;
    }
}
