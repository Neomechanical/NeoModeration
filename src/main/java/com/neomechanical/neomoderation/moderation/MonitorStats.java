package com.neomechanical.neomoderation.moderation;

import com.neomechanical.neomoderation.messages.Reasons;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Since-startup counters of moderation detections, bucketed by the kind of rule
 * ({@link Reasons#bucket}: word, link, server-ad, spam, cloud, map ...). Backs the
 * "what would have happened" summary shown by /nmod mode and status.
 */
public final class MonitorStats {
    private final Object lock = new Object();
    private final Map<String, Long> byReason = new LinkedHashMap<>();
    private final Instant since = Instant.now();
    private long total;
    private long chatMessages;
    private long chatDetections;

    public void record(String reason) {
        String bucket = bucket(reason);
        synchronized (lock) {
            total++;
            byReason.merge(bucket, 1L, Long::sum);
        }
    }

    /** A chat message the rules looked at (moderation on, sender without bypass), for the detection rate. */
    public void recordChatMessage() {
        synchronized (lock) {
            chatMessages++;
        }
    }

    /** A detection in chat, beside {@link #record}, for the detection rate. */
    public void recordChatDetection() {
        synchronized (lock) {
            chatDetections++;
        }
    }

    public long chatMessages() {
        synchronized (lock) {
            return chatMessages;
        }
    }

    public long chatDetections() {
        synchronized (lock) {
            return chatDetections;
        }
    }

    public long total() {
        synchronized (lock) {
            return total;
        }
    }

    public long cloudTotal() {
        synchronized (lock) {
            return byReason.getOrDefault("cloud", 0L) + byReason.getOrDefault("map", 0L);
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
        return Reasons.bucket(reason);
    }
}
