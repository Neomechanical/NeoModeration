package com.neomechanical.neomoderation.moderation;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.neomechanical.neomoderation.moderation.AlertThrottle.Decision.PAUSE_ALL;
import static com.neomechanical.neomoderation.moderation.AlertThrottle.Decision.PAUSE_PLAYER;
import static com.neomechanical.neomoderation.moderation.AlertThrottle.Decision.QUIET;
import static com.neomechanical.neomoderation.moderation.AlertThrottle.Decision.SHOW;
import static org.junit.jupiter.api.Assertions.assertEquals;

class AlertThrottleTest {
    @Test
    void onePlayerGetsThreeAlertsThenOnePauseLineUntilHalfAMinutePasses() {
        AlertThrottle throttle = new AlertThrottle();

        List<AlertThrottle.Decision> decisions = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            decisions.add(throttle.admit("Steve", 1_000L + i));
        }

        assertEquals(List.of(SHOW, SHOW, SHOW, PAUSE_PLAYER, QUIET, QUIET), decisions);
        assertEquals(QUIET, throttle.admit("steve", 30_999L), "names count the same whatever their case");
        assertEquals(SHOW, throttle.admit("Steve", 31_000L));
    }

    @Test
    void anotherPlayerIsStillShownWhileOneIsPaused() {
        AlertThrottle throttle = new AlertThrottle();
        for (int i = 0; i < 5; i++) {
            throttle.admit("Steve", i);
        }

        assertEquals(SHOW, throttle.admit("Alex", 10L));
    }

    @Test
    void manyPlayersAtOnceGetADozenAlertsThenOnePauseLineForTenSeconds() {
        AlertThrottle throttle = new AlertThrottle();

        List<AlertThrottle.Decision> decisions = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            decisions.add(throttle.admit("bot" + i, 1_000L));
        }

        assertEquals(12, decisions.stream().filter(SHOW::equals).count());
        assertEquals(PAUSE_ALL, decisions.get(12));
        assertEquals(List.of(QUIET, QUIET), decisions.subList(13, 15));
        assertEquals(QUIET, throttle.admit("someone", 10_999L));
        assertEquals(SHOW, throttle.admit("someone", 11_000L));
    }
}
