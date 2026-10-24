package com.neomechanical.neomoderation.moderation;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
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
    /** The longest a busy free plan is waited for, whatever it asks: a minute's budget turns each minute. */
    static final long MAX_BUSY_SECONDS = 60;
    private static final long DAY_MILLIS = TimeUnit.DAYS.toMillis(1);
    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    private final Logger logger;
    /** The wall clock, which the free plan's pauses run on: its reset is a time of day (00:00 UTC). */
    private final LongSupplier wallClock;
    private final Object lock = new Object();
    private int transientFailures;
    private long pausedUntilNanos;
    private long refusedUntilNanos;
    private ModerationApiResult.Kind lastResultKind;
    /** Until when the free plan's checks for today are used up (chat and map art wait); 0 when they are not. */
    private long cappedUntilMillis;
    /** Until when the free plan's map pictures for today are used up (map art waits); 0 when they are not. */
    private long picturesUntilMillis;
    /** Until when the free plan asked for a pause while it is busy; 0 when it did not. */
    private long busyUntilMillis;
    private final EnumSet<ModerationApiResult.Kind> loggedFailures =
            EnumSet.noneOf(ModerationApiResult.Kind.class);
    /** Told the first 401 or 402 of each run of them, in place of the log line, by whoever can say why. */
    private volatile Consumer<ModerationApiResult.Kind> refusalListener;
    /** Told the free plan's cap when a check first finds it, with when checks come back. */
    private volatile Consumer<Instant> cappedListener;
    /** Told the first check that works after a reset or any failure. */
    private volatile Runnable successListener;

    public ModerationCircuitBreaker(Logger logger) {
        this(logger, System::currentTimeMillis);
    }

    public ModerationCircuitBreaker(Logger logger, LongSupplier wallClock) {
        this.logger = logger;
        this.wallClock = wallClock;
    }

    /** Told when the free plan's checks for today are first found used up, with when they come back. */
    public void onCapped(Consumer<Instant> listener) {
        this.cappedListener = listener;
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
            cappedUntilMillis = 0L;
            picturesUntilMillis = 0L;
            busyUntilMillis = 0L;
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
            long now = wallClock.getAsLong();
            return refusedUntilNanos == 0L && !freePlanPaused(now);
        }
    }

    /** Whether map art may ask the cloud now: as {@link #shouldCallAutomatically}, unless today's pictures are used up. */
    public boolean shouldCheckPictures() {
        if (!shouldCallAutomatically()) {
            return false;
        }
        synchronized (lock) {
            if (picturesUntilMillis != 0L && wallClock.getAsLong() >= picturesUntilMillis) {
                picturesUntilMillis = 0L;
                loggedFailures.remove(ModerationApiResult.Kind.FREE_PLAN_PICTURES);
            }
            return picturesUntilMillis == 0L;
        }
    }

    /** When the free plan's checks come back, while today's are used up; empty otherwise. */
    public Optional<Instant> cappedUntil() {
        synchronized (lock) {
            freePlanPaused(wallClock.getAsLong());
            return cappedUntilMillis == 0L ? Optional.empty() : Optional.of(Instant.ofEpochMilli(cappedUntilMillis));
        }
    }

    /** When the free plan's map pictures come back, while today's are used up; empty otherwise. */
    public Optional<Instant> picturesUntil() {
        synchronized (lock) {
            if (picturesUntilMillis != 0L && wallClock.getAsLong() >= picturesUntilMillis) {
                picturesUntilMillis = 0L;
                loggedFailures.remove(ModerationApiResult.Kind.FREE_PLAN_PICTURES);
            }
            return picturesUntilMillis == 0L ? Optional.empty() : Optional.of(Instant.ofEpochMilli(picturesUntilMillis));
        }
    }

    /** Under the lock: whether the free plan's cap or a busy minute holds now; a pause that ran out is let go. */
    private boolean freePlanPaused(long now) {
        if (cappedUntilMillis != 0L && now >= cappedUntilMillis) {
            cappedUntilMillis = 0L;
            loggedFailures.remove(ModerationApiResult.Kind.FREE_PLAN_CAPPED);
            if (lastResultKind == ModerationApiResult.Kind.FREE_PLAN_CAPPED) {
                lastResultKind = null;
            }
        }
        if (busyUntilMillis != 0L && now >= busyUntilMillis) {
            busyUntilMillis = 0L;
        }
        return cappedUntilMillis != 0L || busyUntilMillis != 0L;
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
        if (recordFreePlanPause(result)) {
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

    /**
     * The free plan's pauses: its cap (chat and map art wait until the reset, logged once, its listener told), its
     * picture allowance (map art waits, logged once) and a busy minute (everything waits the seconds it gave, at most
     * {@link #MAX_BUSY_SECONDS}, and nobody is told). Neither a picture allowance nor a busy minute is the last
     * check's result: chat may still be working. True when {@code result} was one of them.
     */
    private boolean recordFreePlanPause(ModerationApiResult result) {
        ModerationApiResult.Kind kind = result.kind();
        if (kind != ModerationApiResult.Kind.FREE_PLAN_CAPPED && kind != ModerationApiResult.Kind.FREE_PLAN_PICTURES
                && kind != ModerationApiResult.Kind.FREE_PLAN_BUSY) {
            return false;
        }
        long now = wallClock.getAsLong();
        Instant capped = null;
        synchronized (lock) {
            transientFailures = 0;
            if (kind == ModerationApiResult.Kind.FREE_PLAN_BUSY) {
                long seconds = Math.min(MAX_BUSY_SECONDS, Math.max(1L, result.retryAfterSeconds()));
                busyUntilMillis = Math.max(busyUntilMillis, now + seconds * 1000L);
                return true;
            }
            long until = result.retryAfterSeconds() > 0
                    ? now + Math.min(DAY_MILLIS, result.retryAfterSeconds() * 1000L)
                    : (Math.floorDiv(now, DAY_MILLIS) + 1) * DAY_MILLIS;
            if (kind == ModerationApiResult.Kind.FREE_PLAN_PICTURES) {
                picturesUntilMillis = Math.max(picturesUntilMillis, until);
                if (loggedFailures.add(kind)) {
                    logger.warning("Today's free map checks are used up until " + LOG_TIME.format(Instant.ofEpochMilli(until))
                            + ". Map art waits until then; chat checks go on.");
                }
                return true;
            }
            cappedUntilMillis = Math.max(cappedUntilMillis, until);
            lastResultKind = kind;
            if (loggedFailures.add(kind)) {
                capped = Instant.ofEpochMilli(cappedUntilMillis);
                logger.warning("Today's free cloud checks are used up until " + LOG_TIME.format(capped)
                        + ". Local rules still run. Run /nmod trial status to add an account and keep checking.");
            }
        }
        Consumer<Instant> listener = cappedListener;
        if (capped != null && listener != null) {
            listener.accept(capped);
        }
        return true;
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

            if (result.kind() == ModerationApiResult.Kind.FREE_PLAN_REQUEST_SHAPE) {
                transientFailures = 0;
                if (loggedFailures.add(result.kind())) {
                    logger.warning("The free plan refused a request this version of NeoModeration sent. This is a bug: "
                            + "please report it at " + CloudRecovery.ISSUES_URL + ". Local rules still run.");
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
            // A check that works means the day's checks are back (a reset, or an account that pays past the cap).
            cappedUntilMillis = 0L;
            busyUntilMillis = 0L;
            loggedFailures.retainAll(EnumSet.of(ModerationApiResult.Kind.FREE_PLAN_PICTURES));
            return new Report(null, before != ModerationApiResult.Kind.FLAGGED && before != ModerationApiResult.Kind.CLEAR);
        }
    }
}
