package com.neomechanical.neomoderation.moderation;

import com.neomechanical.neomoderation.config.SpamSettings;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stateful local anti-spam: message rate, duplicate/similar messages, caps,
 * character floods, and command rate. Runs before any cloud call, entirely
 * in-process. Reasons use the {@code spam:} prefix so MonitorStats buckets them
 * together. Thread-safe (chat events arrive async).
 */
public final class SpamDetector {
    /** The reason for a message mostly in capitals, which is lower-cased rather than blocked. */
    public static final String CAPS = "spam:caps";

    private static final long WINDOW_MS = 10_000L;
    /**
     * A repeat counts only when it follows the last message within this gap:
     * "gg" after each of three games an hour apart is not spam.
     */
    private static final long REPEAT_GAP_MS = 30_000L;
    private static final long STALE_MS = 600_000L;
    private static final int CLEANUP_THRESHOLD = 1_000;

    private final Map<UUID, PlayerState> states = new ConcurrentHashMap<>();

    /** Returns a {@code spam:*} reason when the message trips a check. */
    public Optional<String> checkMessage(UUID player, String playerName, String message, SpamSettings s, long now) {
        if (!s.enabled()) {
            return Optional.empty();
        }
        cleanupIfNeeded(now);
        PlayerState state = states.computeIfAbsent(player, ignored -> new PlayerState());
        synchronized (state) {
            state.lastSeen = now;

            if (s.messagesPer10s() > 0) {
                evict(state.messageTimes, now);
                state.messageTimes.addLast(now);
                if (state.messageTimes.size() > s.messagesPer10s()) {
                    return Optional.of("spam:rate");
                }
            }

            if (s.duplicateLimit() > 0) {
                String normalized = normalize(message);
                if (state.lastMessage != null
                        && now - state.lastMessageAt <= REPEAT_GAP_MS
                        && similarity(state.lastMessage, normalized) >= s.similarityThreshold()) {
                    state.repeats++;
                } else {
                    state.repeats = 1;
                }
                state.lastMessage = normalized;
                state.lastMessageAt = now;
                if (state.repeats >= s.duplicateLimit()) {
                    return Optional.of("spam:duplicate");
                }
            }
        }

        if (s.maxCharRun() > 0 && hasCharRun(message, s.maxCharRun())) {
            return Optional.of("spam:flood");
        }
        // Last, because shouting is only turned down: the caller sends the message in lower case.
        if (s.capsPercent() > 0 && isCapsAbuse(message, playerName, s)) {
            return Optional.of(CAPS);
        }
        return Optional.empty();
    }

    /** Rate-limits scanned commands; content checks are handled by the caller. */
    public Optional<String> checkCommand(UUID player, SpamSettings s, long now) {
        if (!s.enabled() || s.commandsPer10s() <= 0) {
            return Optional.empty();
        }
        cleanupIfNeeded(now);
        PlayerState state = states.computeIfAbsent(player, ignored -> new PlayerState());
        synchronized (state) {
            state.lastSeen = now;
            evict(state.commandTimes, now);
            state.commandTimes.addLast(now);
            if (state.commandTimes.size() > s.commandsPer10s()) {
                return Optional.of("spam:command_rate");
            }
        }
        return Optional.empty();
    }

    static boolean isCapsAbuse(String message, String playerName, SpamSettings s) {
        String sample = playerName == null || playerName.isBlank()
                ? message
                : removeIgnoringCase(message, playerName);
        int letters = 0;
        int uppers = 0;
        for (int i = 0; i < sample.length(); i++) {
            char c = sample.charAt(i);
            if (Character.isLetter(c)) {
                letters++;
                if (Character.isUpperCase(c)) {
                    uppers++;
                }
            }
        }
        return letters >= s.capsMinLength() && uppers * 100 > letters * s.capsPercent();
    }

    private static String removeIgnoringCase(String text, String needle) {
        StringBuilder out = new StringBuilder(text.length());
        int index = 0;
        while (index < text.length()) {
            if (text.regionMatches(true, index, needle, 0, needle.length())) {
                index += needle.length();
            } else {
                out.append(text.charAt(index++));
            }
        }
        return out.toString();
    }

    static boolean hasCharRun(String message, int maxRun) {
        int run = 1;
        for (int i = 1; i < message.length(); i++) {
            run = message.charAt(i) == message.charAt(i - 1) ? run + 1 : 1;
            if (run > maxRun) {
                return true;
            }
        }
        return false;
    }

    /** Dice coefficient over character bigrams of the normalized messages. */
    static double similarity(String a, String b) {
        if (a.equals(b)) {
            return 1.0D;
        }
        if (a.length() < 2 || b.length() < 2) {
            return 0.0D;
        }
        Set<String> bigramsA = bigrams(a);
        Set<String> bigramsB = bigrams(b);
        int common = 0;
        for (String bigram : bigramsA) {
            if (bigramsB.contains(bigram)) {
                common++;
            }
        }
        return 2.0D * common / (bigramsA.size() + bigramsB.size());
    }

    private static Set<String> bigrams(String value) {
        Set<String> result = new HashSet<>();
        for (int i = 0; i < value.length() - 1; i++) {
            result.add(value.substring(i, i + 2));
        }
        return result;
    }

    private static String normalize(String message) {
        return message.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private static void evict(Deque<Long> times, long now) {
        while (!times.isEmpty() && now - times.peekFirst() > WINDOW_MS) {
            times.pollFirst();
        }
    }

    private void cleanupIfNeeded(long now) {
        if (states.size() > CLEANUP_THRESHOLD) {
            states.values().removeIf(state -> now - state.lastSeen > STALE_MS);
        }
    }

    private static final class PlayerState {
        final Deque<Long> messageTimes = new ArrayDeque<>();
        final Deque<Long> commandTimes = new ArrayDeque<>();
        String lastMessage;
        long lastMessageAt;
        int repeats;
        long lastSeen;
    }
}
