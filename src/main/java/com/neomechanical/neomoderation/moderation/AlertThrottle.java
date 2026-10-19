package com.neomechanical.neomoderation.moderation;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Keeps staff chat readable during a raid. Each player gets a few alerts per
 * half minute and everyone together a dozen per ten seconds; past either, one
 * line says alerts are paused, and the rest still reach the console, the case
 * history and Discord.
 */
final class AlertThrottle {
    enum Decision {
        /** Show the alert. */
        SHOW,
        /** Say that alerts about this player are paused. */
        PAUSE_PLAYER,
        /** Say that all alerts are paused. */
        PAUSE_ALL,
        /** Show nothing in game. */
        QUIET
    }

    static final int PER_PLAYER = 3;
    static final long PLAYER_WINDOW_MS = 30_000L;
    static final int OVERALL = 12;
    static final long OVERALL_WINDOW_MS = 10_000L;
    /** Past this many players, windows that have ended are dropped. */
    private static final int CLEANUP_THRESHOLD = 256;

    private final Map<String, FixedWindow> players = new HashMap<>();
    private final FixedWindow overall = new FixedWindow(OVERALL_WINDOW_MS);

    synchronized Decision admit(String player, long now) {
        if (overall.count(now) > OVERALL) {
            return Decision.QUIET;
        }
        if (players.size() > CLEANUP_THRESHOLD) {
            players.values().removeIf(window -> window.ended(now));
        }
        FixedWindow own = players.computeIfAbsent(player.toLowerCase(Locale.ROOT),
                ignored -> new FixedWindow(PLAYER_WINDOW_MS));
        if (own.count(now) > PER_PLAYER) {
            return Decision.QUIET;
        }
        int theirs = own.add(now);
        if (overall.add(now) > OVERALL) {
            return Decision.PAUSE_ALL;
        }
        return theirs > PER_PLAYER ? Decision.PAUSE_PLAYER : Decision.SHOW;
    }
}
