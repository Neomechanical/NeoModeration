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
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        // Every 5 seconds for four minutes: never calm, so never another line.
        for (int i = 0; i < 48; i++) {
            assertEquals(QUIET, throttle.admit("Steve", now += 5_000).kind());
        }

        AlertThrottle.Decision back = throttle.admit("Steve", now + 60_000);

        assertEquals(SHOW, back.kind());
        assertEquals(new Missed("Steve", 48, 1), back.missed());
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

        // Thirty raiders every 2 seconds for ten minutes: one line every five minutes, saying what it held.
        long now = 1_000L;
        List<AlertThrottle.Decision> shown = new ArrayList<>();
        for (int second = 0; second < 600; second += 2) {
            now += 2_000;
            for (int raider = 0; raider < 30; raider++) {
                AlertThrottle.Decision decision = throttle.admit("bot" + raider, now);
                if (decision.kind() != QUIET) {
                    shown.add(decision);
                }
            }
        }
        assertEquals(2, shown.size(), "one line every five minutes: " + shown);
        for (AlertThrottle.Decision line : shown) {
            assertEquals(PAUSE_ALL, line.kind(), "a new pause line, carrying what the last one held");
            assertEquals(30, line.missed().players());
        }

        assertEquals(QUIET, throttle.admit("Steve", now + 30_000).kind(), "half a minute is not yet calm");
        AlertThrottle.Decision after = throttle.admit("Steve", now + 91_000);
        assertEquals(SHOW, after.kind());
        assertTrue(after.missed().alerts() > 0, "the line after the raid says what the last pause held");
    }

    @Test
    void aRaidInWavesStaysOnePause() {
        AlertThrottle throttle = new AlertThrottle();
        long now = 0;
        int shown = 0;
        // Fourteen new accounts every 12 seconds for five minutes, each pacing itself under the spam limit.
        for (int wave = 0; wave < 25; wave++) {
            now += 12_000;
            for (int bot = 0; bot < 14; bot++) {
                if (throttle.admit("wave" + wave + "bot" + bot, now + bot).kind() != QUIET) {
                    shown++;
                }
            }
        }

        assertEquals(13, shown, "a dozen alerts and one pause line, not a dozen every wave");
        assertEquals(new Missed(null, 337, 337), throttle.admit("Steve", now + 61_000).missed());
    }

    @Test
    void theSummaryCountsWhatAPlayersOwnPauseHeldBack() {
        AlertThrottle throttle = new AlertThrottle();
        for (int i = 0; i < 6; i++) {
            throttle.admit("Steve", i);  // three shown, one pause line, two held
        }
        for (int i = 0; i < 13; i++) {
            throttle.admit("raider" + i, 20_000 + i);  // a dozen shown, then the pause line
        }
        throttle.admit("raider13", 20_100);

        AlertThrottle.Decision back = throttle.admit("Alex", 90_000);

        assertEquals(new Missed(null, 3, 2), back.missed(), "Steve's two and raider13's one");
    }

    @Test
    void aPauseThatHeldNothingBackEndsWithoutALine() {
        AlertThrottle throttle = new AlertThrottle();
        for (int i = 0; i < 4; i++) {
            throttle.admit("Steve", i);
        }

        AlertThrottle.Decision back = throttle.admit("Steve", 61_000L);

        assertEquals(SHOW, back.kind());
        assertNull(back.missed());
    }

    @Test
    void aBusyServerSeesAlertsAgainFiveMinutesAfterARaid() {
        AlertThrottle throttle = new AlertThrottle();
        for (int bot = 0; bot < 30; bot++) {
            throttle.admit("bot" + bot, 1_000L + bot);
        }
        // Then fifteen ordinary detections a minute from different players, for half an hour.
        long shownAfterPause = 0;
        AlertThrottle.Decision first = null;
        for (int i = 0; i < 450; i++) {
            AlertThrottle.Decision decision = throttle.admit("player" + i, 5_000L + i * 4_000L);
            if (decision.kind() == SHOW) {
                shownAfterPause++;
                if (first == null) {
                    first = decision;
                }
            }
        }

        assertEquals(new Missed(null, 18 + 74, 92), first.missed(), "the pause ends with what it held");
        assertEquals(375, shownAfterPause, "after at most five minutes every alert shows again");
    }

    @Test
    void aRaidPacedUnderTheTenSecondLimitStillPauses() {
        AlertThrottle throttle = new AlertThrottle();
        int shown = 0;
        for (int i = 0; i < 120; i++) {
            // Eleven alerts every ten seconds from rotating accounts, for almost two minutes.
            if (throttle.admit("bot" + (i % 40), i * 10_000L / 11).kind() != QUIET) {
                shown++;
            }
        }

        assertEquals(31, shown, "thirty alerts and one pause line, not eleven every ten seconds");
    }
}
