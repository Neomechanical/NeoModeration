package com.neomechanical.neomoderation.moderation;

import com.neomechanical.neomoderation.config.ModerationAction;
import com.neomechanical.neomoderation.config.ModerationActionType;
import com.neomechanical.neomoderation.config.StrikeSettings;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StrikeServiceTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private static StrikeSettings settings() {
        return new StrikeSettings(true, 30, List.of(
                new StrikeSettings.Escalation(3, action(ModerationActionType.MUTE)),
                new StrikeSettings.Escalation(5, action(ModerationActionType.KICK))
        ));
    }

    private static ModerationAction action(ModerationActionType type) {
        return new ModerationAction(type, "", "", 300, "test");
    }

    @Test
    void escalatesExactlyAtEachRung() {
        StrikeService service = new StrikeService();
        long now = 1_000_000L;

        assertTrue(service.recordStrike(PLAYER, settings(), now).escalation().isEmpty());
        assertTrue(service.recordStrike(PLAYER, settings(), now + 1).escalation().isEmpty());

        StrikeService.Result third = service.recordStrike(PLAYER, settings(), now + 2);
        assertEquals(3, third.strikes());
        assertEquals(ModerationActionType.MUTE, third.escalation().orElseThrow().type());

        assertTrue(service.recordStrike(PLAYER, settings(), now + 3).escalation().isEmpty());
        assertEquals(ModerationActionType.KICK,
                service.recordStrike(PLAYER, settings(), now + 4).escalation().orElseThrow().type());
    }

    @Test
    void decayResetsTheCounter() {
        StrikeService service = new StrikeService();
        long now = 1_000_000L;
        service.recordStrike(PLAYER, settings(), now);
        service.recordStrike(PLAYER, settings(), now + 1);

        long afterDecay = now + 31L * 60_000L;
        assertEquals(0, service.current(PLAYER, settings(), afterDecay));
        StrikeService.Result result = service.recordStrike(PLAYER, settings(), afterDecay);
        assertEquals(1, result.strikes());
        assertTrue(result.escalation().isEmpty());
    }

    @Test
    void disabledStrikesAreInert() {
        StrikeService service = new StrikeService();
        StrikeSettings off = new StrikeSettings(false, 30, settings().escalation());
        for (int i = 0; i < 10; i++) {
            assertEquals(0, service.recordStrike(PLAYER, off, i).strikes());
        }
        assertFalse(service.recordStrike(PLAYER, off, 11).escalation().isPresent());
    }

    @Test
    void decayedCountsAreDroppedOnceManyPlayersHaveOne() {
        StrikeService strikes = new StrikeService();
        for (int i = 0; i <= 1_000; i++) {
            strikes.recordStrike(UUID.randomUUID(), settings(), 0L);
        }
        assertEquals(1_001, strikes.tracked());

        strikes.recordStrike(PLAYER, settings(), 31 * 60_000L);

        assertEquals(1, strikes.tracked(), "every count older than the 30-minute decay goes");
    }
}
