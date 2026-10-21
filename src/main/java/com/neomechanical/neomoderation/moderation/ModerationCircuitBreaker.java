package com.neomechanical.neomoderation.moderation;

import java.util.EnumSet;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Logger;

public final class ModerationCircuitBreaker {
    private static final int TRANSIENT_TRIP_THRESHOLD = 3;
    private static final long PAUSE_NANOS = TimeUnit.SECONDS.toNanos(60);
    /**
     * After a 401 or 402, chat and map art stop asking for this long, then let calls through again; each refusal
     * starts it over. Messages already queued when the refusal lands still go out. The platform scans a message
     * before it refuses an empty wallet, so asking for every message cost a scan each until 1.7.1.
     * {@code /nmod test} still asks, so a fixed key shows at once.
     */
    static final long REFUSAL_PAUSE_NANOS = TimeUnit.MINUTES.toNanos(10);

    private final Logger logger;
    private final Object lock = new Object();
    private int transientFailures;
    private long pausedUntilNanos;
    private long refusedUntilNanos;
    private ModerationApiResult.Kind lastResultKind;
    private final EnumSet<ModerationApiResult.Kind> loggedFailures =
            EnumSet.noneOf(ModerationApiResult.Kind.class);
    /** Told the first 401 or 402 of each run of them, in place of the log line, by whoever can say why. */
    private volatile Consumer<ModerationApiResult.Kind> refusalListener;
    /** Told the first check that works after a reset or any failure. */
    private volatile Runnable successListener;

    public ModerationCircuitBreaker(Logger logger) {
        this.logger = logger;
    }

    /** {@link CloudPlanWatch#refused}, so an ended trial is never logged as a rejected key. */
    public void onRefusal(Consumer<ModerationApiResult.Kind> listener) {
        this.refusalListener = listener;
    }

    /** {@link CloudPlanWatch#succeeded}: an ended trial that works again is asked about, not shown as ended. */
    public void onFirstSuccess(Runnable listener) {
        this.successListener = listener;
    }

    public void reset() {
        synchronized (lock) {
            transientFailures = 0;
            pausedUntilNanos = 0L;
            refusedUntilNanos = 0L;
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

    /**
     * Whether chat and map art may ask the cloud now: the circuit is closed and no refusal in the last ten
     * minutes. Status, doctor and telemetry read {@link #isRemoteCallAllowed()}, so a refused key is never shown
     * as a network failure.
     */
    public boolean shouldCallAutomatically() {
        if (!isRemoteCallAllowed()) {
            return false;
        }
        synchronized (lock) {
            if (refusedUntilNanos != 0L && System.nanoTime() - refusedUntilNanos >= 0) {
                refusedUntilNanos = 0L;
            }
            return refusedUntilNanos == 0L;
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
        Report report = recordLocked(result);
        if (report.firstSuccess()) {
            Runnable listener = successListener;
            if (listener != null) {
                listener.run();
            }
        }
        ModerationApiResult.Kind refused = report.refused();
        if (refused != null) {
            // Outside the lock: the listener asks the trial service and may log.
            Consumer<ModerationApiResult.Kind> listener = refusalListener;
            if (listener != null) {
                listener.accept(refused);
            } else if (refused == ModerationApiResult.Kind.CLIENT_AUTH) {
                logger.warning("Cloud moderation rejected the API key. Create or replace it at "
                        + CloudRecovery.API_KEYS_URL + ", then run /nmod setup <key>.");
            } else {
                logger.warning("Cloud moderation has insufficient credits. Add credits at "
                        + CloudRecovery.BILLING_URL + ", then run /nmod test <message>.");
            }
        }
    }

    /** What {@link #record} tells its listeners once the lock is released. */
    private record Report(ModerationApiResult.Kind refused, boolean firstSuccess) {
        static final Report NOTHING = new Report(null, false);
    }

    /** The first 401 or 402 of a run of them, which the caller reports once, or the first success after a failure. */
    private Report recordLocked(ModerationApiResult result) {
        synchronized (lock) {
            ModerationApiResult.Kind before = lastResultKind;
            lastResultKind = result.kind();
            if (result.kind() == ModerationApiResult.Kind.CLIENT_AUTH
                    || result.kind() == ModerationApiResult.Kind.INSUFFICIENT_CREDITS) {
                transientFailures = 0;
                refusedUntilNanos = System.nanoTime() + REFUSAL_PAUSE_NANOS;
                return loggedFailures.add(result.kind()) ? new Report(result.kind(), false) : Report.NOTHING;
            }

            if (result.kind() == ModerationApiResult.Kind.CLIENT_REQUEST) {
                transientFailures = 0;
                if (loggedFailures.add(result.kind())) {
                    logger.warning("Cloud moderation rejected the request without rejecting the API key. "
                            + "Run /nmod doctor and verify moderation.api.endpoint.");
                }
                return Report.NOTHING;
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
                return Report.NOTHING;
            }

            transientFailures = 0;
            pausedUntilNanos = 0L;
            refusedUntilNanos = 0L;
            loggedFailures.clear();
            return new Report(null, before != ModerationApiResult.Kind.FLAGGED && before != ModerationApiResult.Kind.CLEAR);
        }
    }
}
