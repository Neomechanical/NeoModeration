package com.neomechanical.neomoderation.moderation;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Each player's share of the free plan's day, so one player cannot spend it. */
class PlayerCloudBudgetTest {

    private static final long MIDNIGHT = Instant.parse("2026-11-02T00:00:00Z").toEpochMilli();

    @Test
    void aPlayerAtTheSpamLimitAllDaySpendsAQuarterOfTheDayAndNoMore() {
        PlayerCloudBudget budget = new PlayerCloudBudget();
        UUID spammer = UUID.randomUUID();
        int dailyChecks = 2000;
        int checked = 0;
        // The spam limit lets 5 lines through every 10 seconds: 43,200 lines in a day.
        for (long t = 0; t < 86_400_000L; t += 2_000L) {
            if (budget.tryTake(spammer, dailyChecks, MIDNIGHT + t)) {
                checked++;
            }
        }
        assertEquals(500, checked, "a quarter of 2,000");
        assertEquals(43_200 - 500, budget.skipped());
        UUID other = UUID.randomUUID();
        assertTrue(budget.tryTake(other, dailyChecks, MIDNIGHT + 86_399_000L), "everyone else goes on");
        assertTrue(budget.tryTake(spammer, dailyChecks, MIDNIGHT + 86_400_000L), "a new UTC day, a new share");
    }

    @Test
    void aSmallDayStillGivesEachPlayerAFewChecksAndOtherKeysAreNeverLimited() {
        assertEquals(PlayerCloudBudget.MINIMUM, PlayerCloudBudget.shareOf(40));
        PlayerCloudBudget budget = new PlayerCloudBudget();
        UUID player = UUID.randomUUID();
        for (int i = 0; i < 10_000; i++) {
            assertTrue(budget.tryTake(player, 0, MIDNIGHT), "no free plan, no share");
        }
        assertEquals(0, budget.skipped());
    }
}
