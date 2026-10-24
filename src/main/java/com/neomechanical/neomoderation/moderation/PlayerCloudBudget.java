package com.neomechanical.neomoderation.moderation;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Each player's share of the free plan's cloud checks for the UTC day, so one player cannot spend the server's day: at
 * most {@link #SHARE} of the plan's daily checks, and never fewer than {@link #MINIMUM}. Past it, that player's chat is
 * checked by local rules only until 00:00 UTC; everyone else's goes on as before. A player who leaves and comes back
 * keeps their count for the day. Only a free plan has a day to share: any other key is never limited here.
 */
public final class PlayerCloudBudget {

    /** The part of the day's checks one player may use. */
    static final double SHARE = 0.25;
    /** The fewest checks a player gets, however small the plan's day. */
    static final int MINIMUM = 50;
    private static final long DAY_MILLIS = 86_400_000L;

    private record Count(long day, int used) {
    }

    private final Map<UUID, Count> counts = new ConcurrentHashMap<>();
    private final AtomicLong skipped = new AtomicLong();
    private volatile long prunedDay = Long.MIN_VALUE;

    /** A player's share of a plan with {@code dailyChecks} a day. */
    static int shareOf(int dailyChecks) {
        return Math.max(MINIMUM, (int) Math.floor(dailyChecks * SHARE));
    }

    /**
     * Whether {@code player} may have one more cloud check now, counting it when they may. {@code dailyChecks} is the
     * plan's day; 0 or less means the plan is not a free plan, or its day is not known, and nothing is limited.
     */
    public boolean tryTake(UUID player, int dailyChecks, long nowMillis) {
        if (dailyChecks <= 0 || player == null) {
            return true;
        }
        long day = Math.floorDiv(nowMillis, DAY_MILLIS);
        pruneBefore(day);
        int share = shareOf(dailyChecks);
        boolean[] took = new boolean[1];
        counts.compute(player, (id, count) -> {
            int used = count == null || count.day() != day ? 0 : count.used();
            took[0] = used < share;
            return new Count(day, took[0] ? used + 1 : used);
        });
        if (!took[0]) {
            skipped.incrementAndGet();
        }
        return took[0];
    }

    /** How many messages went without a cloud check since startup because their player had used their share. */
    public long skipped() {
        return skipped.get();
    }

    /** Forgets yesterday's counts once a day, so the map holds only today's players. */
    private void pruneBefore(long day) {
        if (prunedDay != day) {
            prunedDay = day;
            counts.values().removeIf(count -> count.day() < day);
        }
    }
}
