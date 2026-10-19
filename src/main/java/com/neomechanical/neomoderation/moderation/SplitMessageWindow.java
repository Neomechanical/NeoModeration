package com.neomechanical.neomoderation.moderation;

import com.neomechanical.neomoderation.config.OfflineModerationSettings;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects multi-message split-word evasion in Minecraft chat.
 *
 * <p>When a player sends rapid short messages within a few seconds (e.g.
 * {@code "f"}, {@code "u"}, {@code "c"}, {@code "k"} or {@code "f u"},
 * {@code "c k"}) to bypass per-message word filters, this window aggregates
 * recent short fragments and evaluates the combined text.</p>
 */
public final class SplitMessageWindow {

    public static final long DEFAULT_WINDOW_MS = 6_000L;
    public static final int MAX_FRAGMENT_LENGTH = 12;
    public static final int MAX_WINDOW_MESSAGES = 4;
    private static final int MAX_ENTRIES_BEFORE_CLEANUP = 1_000;

    private record Entry(long timestamp, String fragment) {
    }

    private final ConcurrentHashMap<UUID, Deque<Entry>> windows = new ConcurrentHashMap<>();

    public SplitMessageWindow() {
    }

    /**
     * Records a chat fragment for the player and evaluates the joined window against
     * offline moderation rules.
     *
     * @param player   the player sending the message
     * @param message  the raw chat message, which on its own broke no rule
     * @param settings current offline moderation settings
     * @param speaker  the player's game language, or {@code null}
     * @return flagged reason prefixed with {@code "split_"} if a violation occurred across fragments
     */
    public Optional<String> recordAndCheck(
            UUID player,
            String message,
            OfflineModerationSettings settings,
            String speaker
    ) {
        return recordAndCheck(player, message, settings, speaker, System.currentTimeMillis());
    }

    /**
     * Records a chat fragment at an explicit timestamp and evaluates the joined window.
     *
     * @param player   the player sending the message
     * @param message  the raw chat message, which on its own broke no rule
     * @param settings current offline moderation settings
     * @param speaker  the player's game language, or {@code null}
     * @param nowMs    current epoch milliseconds
     * @return flagged reason prefixed with {@code "split_"} if a violation occurred across fragments
     */
    public Optional<String> recordAndCheck(
            UUID player,
            String message,
            OfflineModerationSettings settings,
            String speaker,
            long nowMs
    ) {
        if (settings == null || !settings.enabled() || player == null || message == null) {
            return Optional.empty();
        }

        if (windows.size() > MAX_ENTRIES_BEFORE_CLEANUP) {
            cleanupStale(nowMs);
        }

        String trimmed = message.trim();
        if (trimmed.length() > MAX_FRAGMENT_LENGTH) {
            clear(player);
            return Optional.empty();
        }

        Deque<Entry> deque = windows.computeIfAbsent(player, k -> new ArrayDeque<>());
        synchronized (deque) {
            long cutoff = nowMs - DEFAULT_WINDOW_MS;
            while (!deque.isEmpty() && deque.peekFirst().timestamp() < cutoff) {
                deque.pollFirst();
            }

            deque.addLast(new Entry(nowMs, trimmed));
            while (deque.size() > MAX_WINDOW_MESSAGES) {
                deque.pollFirst();
            }

            if (deque.size() < 2) {
                return Optional.empty();
            }

            StringBuilder joined = new StringBuilder();
            for (Entry entry : deque) {
                if (!joined.isEmpty()) {
                    joined.append(' ');
                }
                joined.append(entry.fragment());
            }

            OfflineModerationResult joinedResult = OfflineModerationEngine.evaluate(joined.toString(), settings, speaker);
            if (joinedResult.flagged()) {
                deque.clear();
                // Messages are always read across a space here; "split_" already says so.
                return Optional.of("split_" + joinedResult.reason().replaceFirst("^spaced_", ""));
            }

            return Optional.empty();
        }
    }

    /**
     * Clears any buffered fragments for the specified player.
     *
     * @param player the player whose window to clear
     */
    public void clear(UUID player) {
        if (player == null) {
            return;
        }
        Deque<Entry> deque = windows.remove(player);
        if (deque != null) {
            synchronized (deque) {
                deque.clear();
            }
        }
    }

    private void cleanupStale(long nowMs) {
        long cutoff = nowMs - DEFAULT_WINDOW_MS;
        windows.entrySet().removeIf(entry -> {
            Deque<Entry> deque = entry.getValue();
            synchronized (deque) {
                while (!deque.isEmpty() && deque.peekFirst().timestamp() < cutoff) {
                    deque.pollFirst();
                }
                return deque.isEmpty();
            }
        });
    }
}
