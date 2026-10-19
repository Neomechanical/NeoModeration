package com.neomechanical.neomoderation.moderation;

import com.neomechanical.neomoderation.config.SpamSettings;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpamDetectorTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static SpamSettings settings() {
        return new SpamSettings(true, 5, 3, 0.9D, 70, 8, 8, 8);
    }

    @Test
    void flagsMessageRateAboveLimit() {
        SpamDetector detector = new SpamDetector();
        long now = 1_000_000L;
        for (int i = 0; i < 5; i++) {
            assertEquals(Optional.empty(),
                    detector.checkMessage(PLAYER, "Tester", "hello " + i, settings(), now + i * 100L));
        }
        assertEquals(Optional.of("spam:rate"),
                detector.checkMessage(PLAYER, "Tester", "hello 6", settings(), now + 600L));
        // Outside the 10s window the counter resets.
        assertEquals(Optional.empty(),
                detector.checkMessage(PLAYER, "Tester", "hello later", settings(), now + 60_000L));
    }

    @Test
    void flagsRepeatedAndNearDuplicateMessages() {
        SpamDetector detector = new SpamDetector();
        long now = 1_000_000L;
        assertEquals(Optional.empty(),
                detector.checkMessage(PLAYER, "Tester", "buy my stuff", settings(), now));
        assertEquals(Optional.empty(),
                detector.checkMessage(PLAYER, "Tester", "BUY my stuff", settings(), now + 3_000L));
        assertEquals(Optional.of("spam:duplicate"),
                detector.checkMessage(PLAYER, "Tester", "buy my stuff!", settings(), now + 6_000L));
    }

    @Test
    void theSameShortMessageAfterEachGameIsNotSpam() {
        SpamDetector detector = new SpamDetector();
        long now = 1_000_000L;
        for (int game = 0; game < 5; game++) {
            assertEquals(Optional.empty(),
                    detector.checkMessage(PLAYER, "Tester", "gg", settings(), now + game * 120_000L),
                    "\"gg\" two minutes after the last one is a new message, not a repeat");
        }
    }

    @Test
    void flagsCapsAbuseButExemptsPlayerNames() {
        SpamSettings s = settings();
        assertTrue(SpamDetector.isCapsAbuse("STOP SHOUTING PLEASE", "Tester", s));
        assertTrue(!SpamDetector.isCapsAbuse("hi", "Tester", s), "below min length");
        assertTrue(!SpamDetector.isCapsAbuse("hey TESTERNAME come", "TESTERNAME", s),
                "player names should not count as caps abuse");
    }

    @Test
    void flagsCharacterFloods() {
        assertTrue(SpamDetector.hasCharRun("aaaaaaaaaaaa", 8));
        assertTrue(!SpamDetector.hasCharRun("aaaa normal", 8));
    }

    @Test
    void flagsCommandRateOnly() {
        SpamDetector detector = new SpamDetector();
        long now = 1_000_000L;
        for (int i = 0; i < 8; i++) {
            assertEquals(Optional.empty(), detector.checkCommand(PLAYER, settings(), now + i * 100L));
        }
        assertEquals(Optional.of("spam:command_rate"), detector.checkCommand(PLAYER, settings(), now + 900L));
    }

    @Test
    void disabledModuleAndZeroLimitsAreInert() {
        SpamDetector detector = new SpamDetector();
        SpamSettings off = new SpamSettings(false, 1, 1, 0.9D, 1, 1, 1, 1);
        assertEquals(Optional.empty(), detector.checkMessage(PLAYER, "Tester", "AAAAAAAAAAAA", off, 0L));

        SpamSettings zeros = new SpamSettings(true, 0, 0, 0.9D, 0, 0, 0, 0);
        for (int i = 0; i < 20; i++) {
            assertEquals(Optional.empty(),
                    detector.checkMessage(PLAYER, "Tester", "AAAAAAAAAAAA", zeros, i));
        }
    }
}
