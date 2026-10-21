package com.neomechanical.neomoderation.moderation;

import com.neomechanical.neomoderation.config.ModerationApiSettings;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Why the cloud refuses this server's key, in the trial service's own words. A cloud check only sees a 401 or a
 * 402; the trial service can say whether that is a trial that ended, a trial whose credits ran out or a key that is
 * wrong, and each needs a different step from the admin. Before 1.7.1 an ended trial read "key rejected" and sent
 * the admin to a page that needs an account, which a trial user does not have.
 *
 * <p>Asked when a key is set or changes; on a refusal, at most once in ten minutes; on the first success after an
 * ended trial or a rejected key, as the key may work again; and once an answer is old, every hour while a trial runs
 * (it can be kept in an account at any time), once when a running trial's end date passes, and every six hours
 * otherwise ({@link #refreshIfStale}). Fed by {@code /nmod trial status}. A question that gets no answer (the service
 * unreachable, or an answer this version cannot read) keeps the last one, and the next refresh asks an hour later.
 * Until the service confirms it, a running trial whose end date has passed reads as ended. It never holds the key,
 * only a fingerprint to notice a new one.
 */
public final class CloudPlanWatch {

    /** How often a refusal, or a success after an ended trial, may ask the trial service again. */
    static final long RECHECK_MILLIS = TimeUnit.MINUTES.toMillis(10);
    /** How old an answer about a running trial, or no answer, may grow before {@link #refreshIfStale} asks. */
    static final long TRIAL_REFRESH_MILLIS = TimeUnit.HOURS.toMillis(1);
    /** How old any other answer may grow. */
    static final long REFRESH_MILLIS = TimeUnit.HOURS.toMillis(6);
    /** How long after a question that got no answer {@link #refreshIfStale} waits before it asks again. */
    static final long RETRY_MILLIS = TimeUnit.HOURS.toMillis(1);

    public enum State {
        /** No key, or no answer yet. */
        UNKNOWN,
        TRIAL_ACTIVE,
        TRIAL_ENDED,
        /** A key from an account, or a trial someone kept in one. */
        STANDARD,
        /** The trial service does not know the key either. */
        KEY_REJECTED
    }

    /**
     * What the trial service said last, and the last 401 or 402 since the key last worked (null when none): a
     * running trial refused with a 402 has used its credits.
     */
    public record Snapshot(State state, TrialClient.TrialStatusResult status, ModerationApiResult.Kind refusal) {
        public static final Snapshot UNKNOWN = new Snapshot(State.UNKNOWN, null, null);

        Snapshot withRefusal(ModerationApiResult.Kind kind) {
            return kind == refusal ? this : new Snapshot(state, status, kind);
        }
    }

    private final TrialClient client;
    private final Supplier<ModerationApiSettings> api;
    private final Consumer<Runnable> async;
    private final LongSupplier clock;
    /** Told every answer: the snapshot, and the refusal it answers (null when nothing was refused). */
    private final BiConsumer<Snapshot, ModerationApiResult.Kind> onAnswer;

    private final Object lock = new Object();
    // Guarded by lock.
    private Snapshot latest = Snapshot.UNKNOWN;
    private String keyFingerprint = "";
    private long answeredAt = Long.MIN_VALUE;
    /** When the last question got no answer. */
    private long unansweredAt = Long.MIN_VALUE;
    /** Counts recorded answers: a question landing after a newer answer was recorded is dropped. */
    private long answers;
    private long lastRecheck = Long.MIN_VALUE;
    /** The refusal the last recheck asked about: another kind is news, and asks at once. */
    private ModerationApiResult.Kind lastRecheckFor;
    /** The question in flight, or null: only its answer lands. */
    private Ask inFlight;
    /** The refusal the answer in flight will report: the one that asked, or a later one. */
    private ModerationApiResult.Kind unanswered;

    /**
     * A question for the trial service: the settings to ask with, taken with the key they hold, and how many answers
     * were known when it was asked. Compared by identity: only the question in flight lands.
     */
    private static final class Ask {
        private final ModerationApiSettings api;
        private final long answersBefore;
        /** A check worked while it was out, so its answer may already be old; guarded by the watch's lock. */
        private boolean outdatedBySuccess;

        private Ask(ModerationApiSettings api, long answersBefore) {
            this.api = api;
            this.answersBefore = answersBefore;
        }
    }

    public CloudPlanWatch(TrialClient client, Supplier<ModerationApiSettings> api, Consumer<Runnable> async,
                          LongSupplier clock, BiConsumer<Snapshot, ModerationApiResult.Kind> onAnswer) {
        this.client = client;
        this.api = api;
        this.async = async;
        this.clock = clock;
        this.onAnswer = onAnswer;
    }

    public Snapshot current() {
        Snapshot snapshot;
        synchronized (lock) {
            snapshot = latest;
        }
        return asOfNow(snapshot);
    }

    public State state() {
        return current().state();
    }

    /** The watch's clock, which counts a trial's days left wherever they are shown. */
    public long now() {
        return clock.getAsLong();
    }

    /**
     * The key in config.yml, after every load: a new key forgets what was known about the old one and asks about
     * the new one; the same key changes nothing. True when the key changed.
     */
    public boolean keyChanged(String apiKey) {
        String fingerprint = fingerprintOf(apiKey);
        Ask ask;
        synchronized (lock) {
            if (fingerprint.equals(keyFingerprint)) {
                return false;
            }
            keyFingerprint = fingerprint;
            latest = Snapshot.UNKNOWN;
            answeredAt = Long.MIN_VALUE;
            unansweredAt = Long.MIN_VALUE;
            lastRecheck = Long.MIN_VALUE;
            lastRecheckFor = null;
            // An answer still in flight is about the old key, and is dropped when it lands.
            inFlight = null;
            unanswered = null;
            ask = fingerprint.isEmpty() ? null : startAsking(null);
        }
        submit(ask);
        return true;
    }

    /**
     * A cloud check was refused (401 or 402). Asks why, unless it asked about the same kind of refusal in the last ten
     * minutes, in which case it answers with what it already knows; a 401 after a 402 (a trial that ran out of credits
     * and then ended) asks at once. While an answer is in flight, that answer reports this refusal.
     */
    public void refused(ModerationApiResult.Kind refusal) {
        Snapshot known = null;
        Ask ask = null;
        synchronized (lock) {
            latest = latest.withRefusal(refusal);
            long now = clock.getAsLong();
            if (inFlight != null) {
                unanswered = refusal;
            } else if (lastRecheck != Long.MIN_VALUE && now - lastRecheck < RECHECK_MILLIS
                    && refusal == lastRecheckFor) {
                known = latest;
            } else {
                lastRecheck = now;
                lastRecheckFor = refusal;
                ask = startAsking(refusal);
            }
        }
        if (known != null) {
            onAnswer.accept(asOfNow(known), refusal);
        }
        submit(ask);
    }

    /**
     * The first cloud check to work after a reset or a refusal. The last refusal no longer holds, and an ended trial
     * or a rejected key that works again (a trial support extended, a key the platform restored) is asked about.
     */
    public void succeeded() {
        Ask ask = null;
        synchronized (lock) {
            latest = latest.withRefusal(null);
            State state = asOfNow(latest).state();
            long now = clock.getAsLong();
            if ((state == State.TRIAL_ENDED || state == State.KEY_REJECTED) && inFlight != null) {
                // The question out was asked before the key worked: its answer is old the moment it lands.
                inFlight.outdatedBySuccess = true;
            } else if (state == State.TRIAL_ENDED || state == State.KEY_REJECTED) {
                if (lastRecheck == Long.MIN_VALUE || now - lastRecheck >= RECHECK_MILLIS) {
                    lastRecheck = now;
                    lastRecheckFor = null;
                    ask = startAsking(null);
                } else {
                    // Asked moments ago: the answer is old now, so the next refresh asks rather than in six hours.
                    answeredAt = Long.MIN_VALUE;
                }
            }
        }
        submit(ask);
    }

    /**
     * Asks again once the last answer is old: an hour while a trial runs or nothing is known, six hours otherwise, and
     * once when a running trial's end date passes after its answer, so a trial kept in its last hour does not read as
     * ended for long. A server clock ahead of the service's keeps the end passed while the service still answers
     * "active", so only an end that passed since the answer counts. An hour after a question that got no answer.
     */
    public void refreshIfStale() {
        Ask ask = null;
        synchronized (lock) {
            long now = clock.getAsLong();
            boolean retryDue = unansweredAt == Long.MIN_VALUE || now - unansweredAt >= RETRY_MILLIS;
            if (!keyFingerprint.isEmpty() && inFlight == null && retryDue) {
                boolean endPassed = latest.state() == State.TRIAL_ACTIVE && latest.status().endedBy(now)
                        && !latest.status().endedBy(answeredAt);
                long limit = latest.state() == State.TRIAL_ACTIVE || latest.state() == State.UNKNOWN
                        ? TRIAL_REFRESH_MILLIS : REFRESH_MILLIS;
                if (endPassed || answeredAt == Long.MIN_VALUE || now - answeredAt >= limit) {
                    ask = startAsking(null);
                }
            }
        }
        submit(ask);
    }

    /** A status {@code /nmod trial status} fetched for {@code apiKey}, so the watch does not ask again for it. */
    public void accept(String apiKey, TrialClient.TrialStatusResult status) {
        record(apiKey, new Snapshot(classify(status), status, null));
    }

    /** {@code /nmod trial status} was refused for {@code apiKey} with a 401: the trial service does not know it. */
    public void rejected(String apiKey) {
        record(apiKey, new Snapshot(State.KEY_REJECTED, null, null));
    }

    private void record(String apiKey, Snapshot answer) {
        String fingerprint = fingerprintOf(apiKey);
        if (fingerprint.isEmpty()) {
            return;
        }
        synchronized (lock) {
            if (fingerprint.equals(keyFingerprint)) {
                latest = new Snapshot(answer.state(), answer.status(), latest.refusal());
                answeredAt = clock.getAsLong();
                unansweredAt = Long.MIN_VALUE;
                answers++;
            }
        }
    }

    /**
     * Under the lock: marks an answer in flight and returns what to ask with, or null when the settings already hold
     * another key, whose own {@link #keyChanged} is on its way.
     */
    private Ask startAsking(ModerationApiResult.Kind refusal) {
        ModerationApiSettings settings = api.get();
        if (settings == null || !fingerprintOf(settings.apiKey()).equals(keyFingerprint)) {
            return null;
        }
        unanswered = refusal;
        inFlight = new Ask(settings, answers);
        return inFlight;
    }

    private void submit(Ask ask) {
        if (ask == null) {
            return;
        }
        try {
            async.accept(() -> {
                Snapshot fetched = null;
                try {
                    fetched = fetch(ask.api);
                } finally {
                    // Whatever the fetch threw, the question lands, or the watch would wait on it for good.
                    land(ask, fetched);
                }
            });
        } catch (RuntimeException refused) {
            // A scheduler that refuses the task (a plugin being disabled) must not leave the watch waiting for good.
            giveUp(ask);
        } catch (Error fatal) {
            giveUp(ask);
            throw fatal;
        }
    }

    private void giveUp(Ask ask) {
        synchronized (lock) {
            if (inFlight == ask) {
                inFlight = null;
                unanswered = null;
            }
        }
    }

    /**
     * The answer to {@code ask}, or null when there was none. No answer keeps the last one. So does an answer to a
     * question asked before {@code /nmod trial status} recorded a newer one, as that one is the admin's.
     */
    private void land(Ask ask, Snapshot fetched) {
        Snapshot answer;
        ModerationApiResult.Kind refusal;
        synchronized (lock) {
            if (inFlight != ask) {
                return; // the key changed while it was asked, and another question replaced it
            }
            inFlight = null;
            refusal = unanswered;
            unanswered = null;
            long now = clock.getAsLong();
            if (fetched == null) {
                unansweredAt = now;
            } else if (ask.answersBefore == answers) {
                latest = new Snapshot(fetched.state(), fetched.status(), latest.refusal());
                answeredAt = ask.outdatedBySuccess ? Long.MIN_VALUE : now;
                unansweredAt = Long.MIN_VALUE;
                answers++;
            }
            answer = latest;
        }
        onAnswer.accept(asOfNow(answer), refusal);
    }

    /** What the trial service said, or null when it said nothing this version can use. A 401 is an answer. */
    private Snapshot fetch(ModerationApiSettings settings) {
        try {
            TrialClient.TrialStatusResult status = client.fetchTrialStatus(settings);
            return status == null ? null : new Snapshot(classify(status), status, null);
        } catch (TrialClient.TrialException e) {
            return e.error() == TrialClient.TrialError.FORBIDDEN ? new Snapshot(State.KEY_REJECTED, null, null) : null;
        } catch (RuntimeException unexpected) {
            return null;
        }
    }

    /** A running trial whose end date has passed reads as ended. */
    private Snapshot asOfNow(Snapshot snapshot) {
        if (snapshot.state() == State.TRIAL_ACTIVE && snapshot.status().endedBy(clock.getAsLong())) {
            return new Snapshot(State.TRIAL_ENDED, snapshot.status(), snapshot.refusal());
        }
        return snapshot;
    }

    static State classify(TrialClient.TrialStatusResult status) {
        if (status == null) {
            return State.UNKNOWN;
        }
        if (status.isTrial()) {
            return status.hasEnded() ? State.TRIAL_ENDED : State.TRIAL_ACTIVE;
        }
        return State.STANDARD;
    }

    /** A key's fingerprint as the watch keeps it: none for a blank key. */
    private static String fingerprintOf(String apiKey) {
        String key = apiKey == null ? "" : apiKey.trim();
        return key.isEmpty() ? "" : fingerprint(key);
    }

    private static String fingerprint(String key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is missing", e);
        }
    }
}
