package com.neomechanical.neomoderation.moderation;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Keeps staff chat readable during a raid. Past three alerts about one player in
 * 30 seconds, or a dozen about anyone in 10 seconds, one line says alerts are
 * paused, and they stay paused until a quiet minute: one in which that player
 * had at most one alert, or everyone together at most three. A raid that comes
 * in waves stays one pause. The first alert after a pause comes with how many
 * staff missed. Every detection still reaches the console and the case history,
 * and Discord in batches.
 */
final class AlertThrottle {
    enum Kind {
        /** Show the alert. */
        SHOW,
        /** Say that alerts about this player are paused. */
        PAUSE_PLAYER,
        /** Say that all alerts are paused. */
        PAUSE_ALL,
        /** Show nothing in game. */
        QUIET
    }

    /** Alerts staff did not see while paused: about {@code player}, or about {@code players} players when it is null. */
    record Missed(String player, int alerts, int players) {
    }

    /** What staff chat shows for one alert, after a line about the pause it ends when {@code missed} is set. */
    record Decision(Kind kind, Missed missed) {
        static final Decision QUIET = new Decision(Kind.QUIET, null);
    }

    static final int PER_PLAYER = 3;
    static final long PLAYER_WINDOW_MS = 30_000L;
    static final int OVERALL = 12;
    static final long OVERALL_WINDOW_MS = 10_000L;
    /** A pause ends after this long with at most the calm number of alerts. */
    static final long CALM_WINDOW_MS = 60_000L;
    static final int PLAYER_CALM = 1;
    static final int OVERALL_CALM = 3;
    /** Past this many players, players with nothing recent are dropped. */
    private static final int CLEANUP_THRESHOLD = 256;

    /** The most recent alert times, and what a pause has held back. */
    private static final class Recent {
        private final int capacity;
        private final ArrayDeque<Long> times = new ArrayDeque<>();
        private final Set<String> heldPlayers = new HashSet<>();
        private boolean paused;
        private int held;

        Recent(int capacity) {
            this.capacity = capacity;
        }

        /** How many alerts came in the {@code window} before {@code now}, counting up to the capacity. */
        int count(long now, long window) {
            int count = 0;
            for (Iterator<Long> newest = times.descendingIterator(); newest.hasNext(); ) {
                if (now - newest.next() >= window) {
                    break;
                }
                count++;
            }
            return count;
        }

        /** Counts an alert; returns how many the last {@code window} holds with it. */
        int add(long now, long window) {
            times.addLast(now);
            if (times.size() > capacity) {
                times.pollFirst();
            }
            return count(now, window);
        }

        void hold(String player, int alerts) {
            held += alerts;
            heldPlayers.add(player);
        }

        /** Ends the pause and says what it held back, or null when it held nothing. */
        Missed resume(String player) {
            Missed missed = held == 0 ? null : new Missed(player, held, heldPlayers.size());
            paused = false;
            held = 0;
            heldPlayers.clear();
            return missed;
        }
    }

    private final Map<String, Recent> players = new HashMap<>();
    private final Recent overall = new Recent(OVERALL + 1);

    synchronized Decision admit(String player, long now) {
        String key = player.toLowerCase(Locale.ROOT);
        Missed missed = null;
        if (overall.paused) {
            if (overall.count(now, CALM_WINDOW_MS) > OVERALL_CALM) {
                overall.add(now, OVERALL_WINDOW_MS);
                overall.hold(key, 1);
                return Decision.QUIET;
            }
            // Alerts a player's own pause held back belong in the same summary.
            players.forEach((name, recent) -> {
                if (recent.held > 0) {
                    overall.hold(name, recent.held);
                }
            });
            missed = overall.resume(null);
            players.clear();
        }
        if (overall.add(now, OVERALL_WINDOW_MS) > OVERALL) {
            overall.paused = true;
            return new Decision(Kind.PAUSE_ALL, missed);
        }

        if (players.size() > CLEANUP_THRESHOLD) {
            players.values().removeIf(recent -> !recent.paused && recent.count(now, CALM_WINDOW_MS) == 0);
        }
        Recent own = players.computeIfAbsent(key, ignored -> new Recent(PER_PLAYER + 1));
        if (own.paused) {
            if (own.count(now, CALM_WINDOW_MS) > PLAYER_CALM) {
                own.add(now, PLAYER_WINDOW_MS);
                own.hold(key, 1);
                return new Decision(Kind.QUIET, missed);
            }
            Missed theirs = own.resume(player);
            missed = missed != null ? missed : theirs;
        }
        if (own.add(now, PLAYER_WINDOW_MS) > PER_PLAYER) {
            own.paused = true;
            return new Decision(Kind.PAUSE_PLAYER, missed);
        }
        return new Decision(Kind.SHOW, missed);
    }
}
