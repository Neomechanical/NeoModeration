package com.neomechanical.neomoderation.moderation;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModerationCircuitBreakerTest {
    @Test
    void transientFailuresTripAfterThreshold() {
        ModerationCircuitBreaker breaker = newBreaker();
        breaker.record(ModerationApiResult.transientTransport());
        breaker.record(ModerationApiResult.transientTransport());
        assertTrue(breaker.isRemoteCallAllowed());

        breaker.record(ModerationApiResult.transientTransport());
        assertFalse(breaker.isRemoteCallAllowed());
    }

    @Test
    void clientAuthDoesNotTripCircuit() {
        ModerationCircuitBreaker breaker = newBreaker();
        for (int i = 0; i < 5; i++) {
            breaker.record(ModerationApiResult.clientAuth());
        }
        assertTrue(breaker.isRemoteCallAllowed());
        assertEquals(ModerationApiResult.Kind.CLIENT_AUTH, breaker.lastResultKind());
    }

    @Test
    void insufficientCreditsAndOtherClientErrorsDoNotMasqueradeAsTransportFailures() {
        ModerationCircuitBreaker breaker = newBreaker();
        for (int i = 0; i < 5; i++) {
            breaker.record(ModerationApiResult.insufficientCredits());
            breaker.record(ModerationApiResult.clientRequest());
        }

        assertTrue(breaker.isRemoteCallAllowed());
        assertEquals(ModerationApiResult.Kind.CLIENT_REQUEST, breaker.lastResultKind());
    }

    @Test
    void resetClearsPause() {
        ModerationCircuitBreaker breaker = newBreaker();
        tripBreaker(breaker);
        assertFalse(breaker.isRemoteCallAllowed());

        breaker.reset();
        assertTrue(breaker.isRemoteCallAllowed());
        assertNull(breaker.lastResultKind());
    }

    @Test
    void expiredPauseClearsOnNextCheck() throws Exception {
        ModerationCircuitBreaker breaker = newBreaker();
        tripBreaker(breaker);
        setPausedUntilNanos(breaker, System.nanoTime() - 1L);
        assertTrue(breaker.isRemoteCallAllowed());
    }

    @Test
    void aRefusedKeyOrEmptyWalletPausesChatForTenMinutesWithoutLookingLikeAnOutage() throws Exception {
        for (ModerationApiResult refusal : new ModerationApiResult[]{
                ModerationApiResult.clientAuth(), ModerationApiResult.insufficientCredits()}) {
            ModerationCircuitBreaker breaker = newBreaker();
            breaker.record(refusal);
            assertFalse(breaker.shouldCallAutomatically(), "chat stops asking: a 402 costs the platform a scan");
            assertTrue(breaker.isRemoteCallAllowed(), "status, doctor and /nmod test still see a working circuit");

            setNanos(breaker, "refusedUntilNanos", System.nanoTime() - 1L);
            assertTrue(breaker.shouldCallAutomatically(), "ten minutes on, it tries once more");
        }
    }

    @Test
    void aWorkingAnswerOrAReloadEndsTheRefusalPause() {
        ModerationCircuitBreaker breaker = newBreaker();
        breaker.record(ModerationApiResult.clientAuth());
        breaker.record(ModerationApiResult.clear());
        assertTrue(breaker.shouldCallAutomatically(), "a fixed key, shown by /nmod test, lets chat ask again");

        breaker.record(ModerationApiResult.insufficientCredits());
        breaker.reset();
        assertTrue(breaker.shouldCallAutomatically(), "a reload, which a new key brings, starts afresh");
    }

    @Test
    void aRefusalIsReportedOncePerRunWhoeverListens() {
        ModerationCircuitBreaker breaker = newBreaker();
        java.util.List<ModerationApiResult.Kind> heard = new java.util.ArrayList<>();
        breaker.onRefusal(heard::add);
        breaker.record(ModerationApiResult.clientAuth());
        breaker.record(ModerationApiResult.clientAuth());
        breaker.record(ModerationApiResult.insufficientCredits());
        assertEquals(java.util.List.of(ModerationApiResult.Kind.CLIENT_AUTH,
                ModerationApiResult.Kind.INSUFFICIENT_CREDITS), heard);
        breaker.record(ModerationApiResult.clear());
        breaker.record(ModerationApiResult.clientAuth());
        assertEquals(3, heard.size(), "after a working answer, a new run is reported again");
    }

    @Test
    void theFirstWorkingCheckAfterAResetOrAFailureIsReportedOnce() {
        ModerationCircuitBreaker breaker = newBreaker();
        java.util.concurrent.atomic.AtomicInteger heard = new java.util.concurrent.atomic.AtomicInteger();
        breaker.onFirstSuccess(heard::incrementAndGet);
        breaker.record(ModerationApiResult.clear());
        breaker.record(ModerationApiResult.flagged());
        assertEquals(1, heard.get(), "the first since startup, then no more while it keeps working");
        breaker.record(ModerationApiResult.insufficientCredits());
        breaker.record(ModerationApiResult.clear());
        assertEquals(2, heard.get(), "a key that works again after a refusal");
        breaker.record(ModerationApiResult.transientTransport());
        breaker.record(ModerationApiResult.clear());
        assertEquals(3, heard.get());
        breaker.reset();
        breaker.record(ModerationApiResult.clear());
        assertEquals(4, heard.get());
        breaker.record(ModerationApiResult.overloaded());
        breaker.record(ModerationApiResult.clear());
        assertEquals(4, heard.get(), "a busy server is not the cloud failing");
    }

    private ModerationCircuitBreaker newBreaker() {
        return new ModerationCircuitBreaker(Logger.getLogger("test"));
    }

    private void tripBreaker(ModerationCircuitBreaker breaker) {
        breaker.record(ModerationApiResult.transientTransport());
        breaker.record(ModerationApiResult.transientTransport());
        breaker.record(ModerationApiResult.transientTransport());
    }

    private void setPausedUntilNanos(ModerationCircuitBreaker breaker, long value) throws Exception {
        setNanos(breaker, "pausedUntilNanos", value);
    }

    private void setNanos(ModerationCircuitBreaker breaker, String name, long value) throws Exception {
        Field field = ModerationCircuitBreaker.class.getDeclaredField(name);
        field.setAccessible(true);
        field.setLong(breaker, value);
    }
}
