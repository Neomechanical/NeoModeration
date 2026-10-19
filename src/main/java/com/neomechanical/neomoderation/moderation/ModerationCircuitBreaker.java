package com.neomechanical.neomoderation.moderation;

import java.util.EnumSet;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public final class ModerationCircuitBreaker {
    private static final int TRANSIENT_TRIP_THRESHOLD = 3;
    private static final long PAUSE_NANOS = TimeUnit.SECONDS.toNanos(60);

    private final Logger logger;
    private final Object lock = new Object();
    private int transientFailures;
    private long pausedUntilNanos;
    private ModerationApiResult.Kind lastResultKind;
    private final EnumSet<ModerationApiResult.Kind> loggedFailures =
            EnumSet.noneOf(ModerationApiResult.Kind.class);

    public ModerationCircuitBreaker(Logger logger) {
        this.logger = logger;
    }

    public void reset() {
        synchronized (lock) {
            transientFailures = 0;
            pausedUntilNanos = 0L;
            lastResultKind = null;
            loggedFailures.clear();
        }
    }

    public boolean isRemoteCallAllowed() {
        synchronized (lock) {
            long now = System.nanoTime();
            if (pausedUntilNanos != 0L && now >= pausedUntilNanos) {
                pausedUntilNanos = 0L;
                transientFailures = 0;
                loggedFailures.remove(ModerationApiResult.Kind.TRANSIENT_TRANSPORT);
            }
            return pausedUntilNanos == 0L;
        }
    }

    public ModerationApiResult.Kind lastResultKind() {
        synchronized (lock) {
            return lastResultKind;
        }
    }

    public void record(ModerationApiResult result) {
        if (result.kind() == ModerationApiResult.Kind.OVERLOADED) {
            // A busy server, not something the cloud did: ChatModerationCoordinator counts it.
            return;
        }
        synchronized (lock) {
            lastResultKind = result.kind();
            if (result.kind() == ModerationApiResult.Kind.CLIENT_AUTH) {
                transientFailures = 0;
                if (loggedFailures.add(result.kind())) {
                    logger.warning("Cloud moderation rejected the API key. Create or replace it at "
                            + CloudRecovery.API_KEYS_URL + ", then run /nmod setup <key>.");
                }
                return;
            }

            if (result.kind() == ModerationApiResult.Kind.INSUFFICIENT_CREDITS) {
                transientFailures = 0;
                if (loggedFailures.add(result.kind())) {
                    logger.warning("Cloud moderation has insufficient credits. Add credits at "
                            + CloudRecovery.BILLING_URL + ", then run /nmod test <message>.");
                }
                return;
            }

            if (result.kind() == ModerationApiResult.Kind.CLIENT_REQUEST) {
                transientFailures = 0;
                if (loggedFailures.add(result.kind())) {
                    logger.warning("Cloud moderation rejected the request without rejecting the API key. "
                            + "Run /nmod doctor and verify moderation.api.endpoint.");
                }
                return;
            }

            if (result.tripsTransientBreaker()) {
                transientFailures++;
                if (transientFailures >= TRANSIENT_TRIP_THRESHOLD) {
                    pausedUntilNanos = System.nanoTime() + PAUSE_NANOS;
                    transientFailures = 0;
                    if (loggedFailures.add(result.kind())) {
                        logger.warning("Cloud moderation paused for 60s after errors. Local rules still run. Use /nmod reload to resume early.");
                    }
                }
                return;
            }

            transientFailures = 0;
            pausedUntilNanos = 0L;
            loggedFailures.clear();
        }
    }
}
