package com.neomechanical.neomoderation.moderation;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Since-startup counters of moderation detections, bucketed by reason prefix
 * ({@code blocked_word}, {@code blocked_url}, {@code platform}, {@code map_art}).
 * Backs the "what would have happened" summary shown by /nmod mode and status.
 */
public final class MonitorStats {
    private final Object lock = new Object();
    private final Map<String, Long> byReason = new LinkedHashMap<>();
    private final Instant since = Instant.now();
    private long total;

    public void record(String reason) {
        String bucket = bucket(reason);
        synchronized (lock) {
            total++;
            byReason.merge(bucket, 1L, Long::sum);
        }
    }

    public long total() {
        synchronized (lock) {
            return total;
        }
    }

    public long cloudTotal() {
        synchronized (lock) {
            return byReason.getOrDefault("platform", 0L) + byReason.getOrDefault("map_art", 0L);
        }
    }

    public Map<String, Long> byReason() {
        synchronized (lock) {
            return new LinkedHashMap<>(byReason);
        }
    }

    public Instant since() {
        return since;
    }

    private static String bucket(String reason) {
        if (reason == null || reason.isBlank()) {
            return "other";
        }
        int colon = reason.indexOf(':');
        return colon > 0 ? reason.substring(0, colon) : reason;
    }
}
