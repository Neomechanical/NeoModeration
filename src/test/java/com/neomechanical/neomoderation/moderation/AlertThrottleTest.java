package com.neomechanical.neomoderation.moderation;

import com.neomechanical.neomoderation.moderation.AlertThrottle.Kind;
import com.neomechanical.neomoderation.moderation.AlertThrottle.Missed;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.neomechanical.neomoderation.moderation.AlertThrottle.Kind.PAUSE_ALL;
import static com.neomechanical.neomoderation.moderation.AlertThrottle.Kind.PAUSE_PLAYER;
import static com.neomechanical.neomoderation.moderation.AlertThrottle.Kind.QUIET;
import static com.neomechanical.neomoderation.moderation.AlertThrottle.Kind.SHOW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AlertThrottleTest {
    @Test
    void onePlayerGetsThreeAlertsThenOnePauseLine() {
        AlertThrottle throttle = new AlertThrottle();

        List<Kind> kinds = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            kinds.add(throttle.admit("Steve", 1_000L + i).kind());
        }

        assertEquals(List.of(SHOW, SHOW, SHOW, PAUSE_PLAYER, QUIET, QUIET), kinds);
        assertEquals(QUIET, throttle.admit("steve", 2_000L).kind(), "names count the same whatever their case");
    }

    @Test
    void aPlayerWhoKeepsGoingStaysPausedAndTheFirstAlertAfterSaysHowManyWereMissed() {
        AlertThrottle throttle = new AlertThrottle();
        long now = 0;
        for (int i = 0; i < 4; i++) {
            throttle.admit("Steve", now += 1_000);
        }
        // Every 5 seconds for five minutes: never calm, so never another line.
        for (int i = 0; i < 60; i++) {
            assertEquals(QUIET, throttle.admit("Steve", now += 5_000).kind());
        }

        AlertThrottle.Decision back = throttle.admit("Steve", now + 60_000);

        assertEquals(SHOW, back.kind());
        assertEquals(new Missed("Steve", 60, 1), back.missed());
    }

    @Test
    void anotherPlayerIsStillShownWhileOneIsPaused() {
        AlertThrottle throttle = new AlertThrottle();
        for (int i = 0; i < 5; i++) {
            throttle.admit("Steve", i);
        }

        assertEquals(SHOW, throttle.admit("Alex", 10L).kind());
    }

    @Test
    void aRaidGetsADozenAlertsThenOnePauseLineForAsLongAsItLasts() {
        AlertThrottle throttle = new AlertThrottle();

        List<Kind> kinds = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            kinds.add(throttle.admit("bot" + i, 1_000L).kind());
        }
        assertEquals(12, kinds.stream().filter(SHOW::equals).count());
        assertEquals(PAUSE_ALL, kinds.get(12));
        assertEquals(List.of(QUIET, QUIET), kinds.subList(13, 15));

        // Thirty raiders every 2 seconds for ten minutes: nothing more in staff chat.
        long now = 1_000L;
        int shown = 0;
        for (int second = 0; second < 600; second += 2) {
            now += 2_000;
            for (int raider = 0; raider < 30; raider++) {
                if (throttle.admit("bot" + raider, now).kind() != QUIET) {
                    shown++;
                }
            }
        }
        assertEquals(0, shown);

        AlertThrottle.Decision after = throttle.admit("Steve", now + 15_000);
        assertEquals(SHOW, after.kind());
        assertEquals(new Missed(null, 9_002, 30), after.missed());
    }

    @Test
    void aPauseThatHeldNothingBackEndsWithoutALine() {
        AlertThrottle throttle = new AlertThrottle();
        for (int i = 0; i < 4; i++) {
            throttle.admit("Steve", i);
        }

        AlertThrottle.Decision back = throttle.admit("Steve", 60_000L);

        assertEquals(SHOW, back.kind());
        assertNull(back.missed());
    }
}
