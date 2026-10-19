package com.neomechanical.neomoderation.moderation;

import com.neomechanical.neomoderation.config.OfflineModerationSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SplitMessageWindowTest {

    private SplitMessageWindow window;
    private OfflineModerationSettings settings;
    private UUID player;

    @BeforeEach
    void setUp() {
        window = new SplitMessageWindow();
        settings = new OfflineModerationSettings(
                true, false, true, List.of("fuck", "bitch", "scam"), List.of(), List.of(), List.of());
        player = UUID.randomUUID();
    }

    @Test
    void splitWordWithinWindowFlagsOnLastFragmentAndClearsWindow() {
        long t = 1_000L;

        assertEquals(Optional.empty(), window.recordAndCheck(player, "f", settings, null, t));
        assertEquals(Optional.empty(), window.recordAndCheck(player, "u", settings, null, t + 1000));
        assertEquals(Optional.empty(), window.recordAndCheck(player, "c", settings, null, t + 2000));

        Optional<String> fourth = window.recordAndCheck(player, "k", settings, null, t + 3000);
        assertTrue(fourth.isPresent(), "Expected split message to flag on the fourth fragment");
        assertEquals("split_blocked_word:fuck", fourth.get());

        // The window must be cleared after a detection, so a subsequent message does not re-flag
        assertEquals(Optional.empty(), window.recordAndCheck(player, "ing", settings, null, t + 4000));
    }

    @Test
    void fragmentsSeparatedByMoreThanWindowDoNotFlag() {
        // DEFAULT_WINDOW_MS is 6,000ms; messages separated by 7,000ms should evict previous fragments
        long t = 0L;

        assertEquals(Optional.empty(), window.recordAndCheck(player, "f", settings, null, t));
        assertEquals(Optional.empty(), window.recordAndCheck(player, "u", settings, null, t + 7_000L));
        assertEquals(Optional.empty(), window.recordAndCheck(player, "c", settings, null, t + 14_000L));
        assertEquals(Optional.empty(), window.recordAndCheck(player, "k", settings, null, t + 21_000L));
    }

    @Test
    void normalShortChatDoesNotFlag() {
        long t = 1_000L;

        assertEquals(Optional.empty(), window.recordAndCheck(player, "gg", settings, null, t));
        assertEquals(Optional.empty(), window.recordAndCheck(player, "wp", settings, null, t + 500));
        assertEquals(Optional.empty(), window.recordAndCheck(player, "ty", settings, null, t + 1000));
        assertEquals(Optional.empty(), window.recordAndCheck(player, "bye", settings, null, t + 1500));
    }

    @Test
    void longNormalMessageResetsFragmentWindow() {
        long t = 1_000L;

        assertEquals(Optional.empty(), window.recordAndCheck(player, "f", settings, null, t));
        assertEquals(Optional.empty(), window.recordAndCheck(player, "u", settings, null, t + 1000));

        // Normal chat message exceeds MAX_FRAGMENT_LENGTH (12 chars), resetting the window
        assertEquals(Optional.empty(),
                window.recordAndCheck(player, "I am building a castle near spawn", settings, null, t + 2000));

        // Only "c" and "k" remain; joined is "c k", which does not match "fuck"
        assertEquals(Optional.empty(), window.recordAndCheck(player, "c", settings, null, t + 3000));
        assertEquals(Optional.empty(), window.recordAndCheck(player, "k", settings, null, t + 4000));
    }

    @Test
    void multiCharacterSplitPhrasesFlag() {
        long t = 1_000L;

        assertEquals(Optional.empty(), window.recordAndCheck(player, "f u", settings, null, t));
        Optional<String> second = window.recordAndCheck(player, "c k", settings, null, t + 1000);

        assertTrue(second.isPresent());
        assertEquals("split_blocked_word:fuck", second.get());
    }

    @Test
    void singleMessageProfanityDoesNotFlagAsSplitMessage() {
        // Direct violations are handled by per-message evaluation; split window should not double-flag
        assertEquals(Optional.empty(), window.recordAndCheck(player, "fuck", settings, null, 1_000L));
    }

    @Test
    void disabledSettingsOrNullInputsReturnEmpty() {
        OfflineModerationSettings disabled = new OfflineModerationSettings(
                false, false, true, List.of("fuck"), List.of(), List.of(), List.of());

        assertEquals(Optional.empty(), window.recordAndCheck(player, "f", disabled, null, 1_000L));
        assertEquals(Optional.empty(), window.recordAndCheck(null, "f", settings, null, 1_000L));
        assertEquals(Optional.empty(), window.recordAndCheck(player, null, settings, null, 1_000L));
    }

    @Test
    void clearPlayerExplicitlyResetsWindow() {
        long t = 1_000L;

        assertEquals(Optional.empty(), window.recordAndCheck(player, "f", settings, null, t));
        assertEquals(Optional.empty(), window.recordAndCheck(player, "u", settings, null, t + 1000));

        window.clear(player);

        assertEquals(Optional.empty(), window.recordAndCheck(player, "c", settings, null, t + 2000));
        assertEquals(Optional.empty(), window.recordAndCheck(player, "k", settings, null, t + 3000));
    }

    @Test
    void stalePlayerCleanupTriggersWhenMapExceedsLimit() {
        long oldTime = 1_000L;
        // Populate 1,001 players with fragments at oldTime
        for (int i = 0; i < 1_001; i++) {
            window.recordAndCheck(UUID.randomUUID(), "hi", settings, null, oldTime);
        }

        // Now advance time past DEFAULT_WINDOW_MS; next recordAndCheck should trigger cleanup
        long laterTime = oldTime + 10_000L;
        UUID activePlayer = UUID.randomUUID();
        Optional<String> res = window.recordAndCheck(activePlayer, "test", settings, null, laterTime);
        assertEquals(Optional.empty(), res);
    }
}
