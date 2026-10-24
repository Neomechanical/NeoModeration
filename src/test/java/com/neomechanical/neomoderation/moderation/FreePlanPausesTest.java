package com.neomechanical.neomoderation.moderation;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/** The free plan's pauses in the circuit breaker: its cap, its picture allowance and a busy minute. */
class FreePlanPausesTest {

    private static final long NOON = Instant.parse("2026-11-02T12:00:00Z").toEpochMilli();

    private final AtomicLong clock = new AtomicLong(NOON);
    private final List<String> warnings = new ArrayList<>();
    private final ModerationCircuitBreaker breaker = new ModerationCircuitBreaker(logger(), clock::get);

    private Logger logger() {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                warnings.add(record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        return logger;
    }

    private static ModerationApiResult refused(String code, long seconds) {
        return ModerationApiResult.fromRefusal(429, code, seconds);
    }

    @Test
    void theCapStopsChatAndMapsUntilTheResetSaysSoOnceAndTellsItsListener() {
        List<Instant> told = new ArrayList<>();
        breaker.onCapped(told::add);
        long twelveHours = 12 * 3600;
        breaker.record(refused("free_plan_daily_cap", twelveHours + 300));
        breaker.record(refused("free_plan_daily_cap", twelveHours + 290));
        Instant back = Instant.parse("2026-11-03T00:05:00Z");
        assertFalse(breaker.shouldCallAutomatically());
        assertFalse(breaker.shouldCheckPictures());
        assertTrue(breaker.isRemoteCallAllowed(), "not an outage: /nmod test still asks");
        assertEquals(back, breaker.cappedUntil().orElseThrow());
        assertEquals(ModerationApiResult.Kind.FREE_PLAN_CAPPED, breaker.lastResultKind());
        assertEquals(List.of(back), told, "told once");
        assertEquals(1, warnings.size(), "logged once");
        assertTrue(warnings.get(0).contains("00:05 UTC"), warnings.get(0));

        clock.set(back.toEpochMilli());
        assertTrue(breaker.shouldCallAutomatically(), "back at the reset");
        assertTrue(breaker.cappedUntil().isEmpty());
        breaker.record(refused("free_plan_daily_cap", 60));
        assertEquals(2, warnings.size(), "a new day's cap is said again");
    }

    @Test
    void aCapWithNoWaitGivenLastsUntilMidnightUtcAndACheckThatWorksEndsIt() {
        breaker.record(refused("free_plan_daily_cap", 0));
        assertEquals(Instant.parse("2026-11-03T00:00:00Z"), breaker.cappedUntil().orElseThrow());
        breaker.record(ModerationApiResult.clear());
        assertTrue(breaker.cappedUntil().isEmpty(), "an account now pays past the cap");
        assertTrue(breaker.shouldCallAutomatically());
    }

    @Test
    void usedUpPicturesStopOnlyMapsAndNeverPauseChat() {
        breaker.record(refused("free_plan_image_allowance", 3600));
        assertTrue(breaker.shouldCallAutomatically(), "chat goes on");
        assertFalse(breaker.shouldCheckPictures());
        assertNull(breaker.lastResultKind(), "not the last check's result: chat may still be working");
        breaker.record(ModerationApiResult.clear());
        assertFalse(breaker.shouldCheckPictures(), "a chat check that works does not bring pictures back");
        clock.addAndGet(3_600_000L);
        assertTrue(breaker.shouldCheckPictures());
    }

    @Test
    void aBusyMinuteWaitsWhatItAsksAtMostAMinuteAndTellsNobody() {
        breaker.record(refused("free_plan_busy", 5));
        assertFalse(breaker.shouldCallAutomatically());
        assertTrue(warnings.isEmpty());
        assertNull(breaker.lastResultKind());
        clock.addAndGet(5_000L);
        assertTrue(breaker.shouldCallAutomatically());
        breaker.record(refused("free_plan_busy", 3600));
        clock.addAndGet(ModerationCircuitBreaker.MAX_BUSY_SECONDS * 1000L);
        assertTrue(breaker.shouldCallAutomatically(), "never longer than a minute");
    }

    @Test
    void manyFreePlanRefusalsNeverTripTheOutageBreaker() {
        for (int i = 0; i < 10; i++) {
            breaker.record(refused("free_plan_busy", 1));
            clock.addAndGet(1_000L);
        }
        assertTrue(breaker.isRemoteCallAllowed());
    }

    @Test
    void aRefusedRequestShapeIsABugLoggedOnce() {
        breaker.record(ModerationApiResult.fromRefusal(400, "free_plan_request_shape", 0));
        breaker.record(ModerationApiResult.fromRefusal(400, "free_plan_request_shape", 0));
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains(CloudRecovery.ISSUES_URL));
        assertEquals(ModerationApiResult.Kind.FREE_PLAN_REQUEST_SHAPE, breaker.lastResultKind());
        assertTrue(breaker.shouldCallAutomatically(), "chat goes on with local rules and the next checks");
    }

    @Test
    void aPlanClaimedAfterItsCapChecksAgainAtOnce() {
        breaker.record(refused("free_plan_daily_cap", 3600));
        assertFalse(breaker.shouldCallAutomatically());
        breaker.freeChecksBack();
        assertTrue(breaker.shouldCallAutomatically(), "its account now pays past the cap");
        assertTrue(breaker.cappedUntil().isEmpty());
        assertNull(breaker.lastResultKind());
    }
}
