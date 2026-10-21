package com.neomechanical.neomoderation.moderation;

import com.neomechanical.neomoderation.config.ModerationApiSettings;
import com.neomechanical.neomoderation.config.ModerationSettings;
import org.bukkit.entity.Player;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Sends chat to the cloud. A message whose cloud decision can block waits for it,
 * at most {@link #MAX_WAIT_MS}; one whose decision can only alert staff goes
 * through at once and is checked in the background. Requests run on a few
 * workers with a short queue, so a burst of chat waits its turn instead of
 * skipping the cloud; when the queue is full, or a message's wait runs out while
 * it is still queued, it goes through unchecked as an overload, which
 * {@link #busySkipped()} counts.
 *
 * <p>Only what the cloud itself did counts towards pausing it, or shows as the
 * last cloud result: each worker records its own call's result, and a call that
 * answered too slowly for chat counts as a failure. Time a message spent waiting
 * for a free worker is the server's load, never the cloud's fault. A call made with
 * a key or endpoint the server has since replaced is not recorded at all: an old
 * key's 401 says nothing about the new one.</p>
 */
public final class ChatModerationCoordinator implements AutoCloseable {
    private static final int WORKERS = 8;
    private static final int QUEUED = 32;
    private static final long MAX_WAIT_MS = 2500L;
    /** The console hears about a busy server at most this often. */
    private static final long BUSY_WARNING_NANOS = TimeUnit.MINUTES.toNanos(10);

    private final AtomicInteger workerSeq = new AtomicInteger();
    private final AtomicLong busySkipped = new AtomicLong();
    private final AtomicLong lastBusyWarning = new AtomicLong();
    private final Logger logger;
    private final ModerationCircuitBreaker circuit;
    private final ModerationApiClient apiClient;
    private final ThreadPoolExecutor workers;
    /** The settings cloud calls are made with now; null until the plugin says. */
    private volatile ModerationApiSettings currentApi;

    public ChatModerationCoordinator(Logger logger) {
        this(new ModerationCircuitBreaker(logger), new ModerationApiClient(), WORKERS, QUEUED, logger);
    }

    ChatModerationCoordinator(ModerationCircuitBreaker circuit, ModerationApiClient apiClient) {
        this(circuit, apiClient, WORKERS, QUEUED);
    }

    ChatModerationCoordinator(ModerationCircuitBreaker circuit, ModerationApiClient apiClient,
                              int workerCount, int queueCapacity) {
        this(circuit, apiClient, workerCount, queueCapacity, Logger.getLogger("NeoModeration"));
    }

    private ChatModerationCoordinator(ModerationCircuitBreaker circuit, ModerationApiClient apiClient,
                                      int workerCount, int queueCapacity, Logger logger) {
        this.logger = logger;
        this.circuit = circuit;
        this.apiClient = apiClient;
        ThreadPoolExecutor pool = new ThreadPoolExecutor(workerCount, workerCount, 30L, TimeUnit.SECONDS,
                queueCapacity > 0 ? new ArrayBlockingQueue<>(queueCapacity) : new SynchronousQueue<>(), task -> {
            Thread thread = new Thread(task, "NeoModeration-worker-" + workerSeq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        pool.allowCoreThreadTimeOut(true);
        this.workers = pool;
    }

    /**
     * One cloud call on a worker, recorded as the cloud's own result. An answer slower than chat can wait counts as a
     * failure, except a refusal, which stays one: a late 401 or 402 still pauses chat's calls and is reported.
     */
    private ModerationApiResult ask(String name, String uuid, String message, ModerationSettings settings,
                                    long chatWaitMs) {
        long started = System.nanoTime();
        ModerationApiResult result = apiClient.moderateText(name, uuid, message, settings.api(), settings.categories());
        long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        boolean refused = result.kind() == ModerationApiResult.Kind.CLIENT_AUTH
                || result.kind() == ModerationApiResult.Kind.INSUFFICIENT_CREDITS
                || result.kind() == ModerationApiResult.Kind.CLIENT_REQUEST;
        recordApiResult(tookMs > chatWaitMs && !refused ? ModerationApiResult.transientTransport() : result,
                settings.api());
        return result;
    }

    /** The key and endpoint the server calls the cloud with from now on (startup and every reload). */
    public void useApi(ModerationApiSettings api) {
        this.currentApi = api;
    }

    public void resetCircuit() {
        circuit.reset();
    }

    /** Who hears the first 401 or 402 of each run of them: {@link CloudPlanWatch#refused}. */
    public void onRefusal(Consumer<ModerationApiResult.Kind> listener) {
        circuit.onRefusal(listener);
    }

    /** Who hears the first check that works after a reset or a failure. */
    public void onFirstSuccess(Runnable listener) {
        circuit.onFirstSuccess(listener);
    }

    public ModerationApiClient apiClient() {
        return apiClient;
    }

    public boolean isRemoteCallAllowed() {
        return circuit.isRemoteCallAllowed();
    }

    /** Whether chat and map art may ask the cloud now; see {@link ModerationCircuitBreaker#shouldCallAutomatically}. */
    public boolean shouldCallAutomatically() {
        return circuit.shouldCallAutomatically();
    }

    public ModerationApiResult.Kind lastCloudResultKind() {
        return circuit.lastResultKind();
    }

    /** How many messages went without a cloud check since startup because every worker was busy. */
    public long busySkipped() {
        return busySkipped.get();
    }

    /** A message skipped the cloud because every worker was busy: counted, and told to the console now and then. */
    private ModerationApiResult skippedWhileBusy() {
        busySkipped.incrementAndGet();
        long now = System.nanoTime();
        long last = lastBusyWarning.get();
        if ((last == 0L || now - last >= BUSY_WARNING_NANOS) && lastBusyWarning.compareAndSet(last, now)) {
            logger.warning("Chat came faster than the cloud workers could check it, so some messages had only "
                    + "the local rules. /nmod status counts them.");
        }
        return ModerationApiResult.overloaded();
    }

    /**
     * Records the result of a moderation call made with {@code askedWith}: chat's own, /nmod test's and map art's.
     * Account and usage calls never enter this state. A result for settings the server has replaced since is dropped.
     */
    public void recordApiResult(ModerationApiResult result, ModerationApiSettings askedWith) {
        ModerationApiSettings current = currentApi;
        if (current == null || current.equals(askedWith)) {
            circuit.record(result);
        }
    }

    @Override
    public void close() {
        workers.shutdownNow();
    }

    /**
     * Asks the cloud without holding the message, for a decision that can only
     * alert: {@code onResult} runs on a worker thread when the answer comes.
     * Nothing is asked while the circuit is open or the queue is full.
     */
    public void checkInBackground(Player player, String message, ModerationSettings settings,
                                  Consumer<ModerationApiResult> onResult) {
        if (!circuit.shouldCallAutomatically()) {
            return;
        }
        String name = player.getName();
        String uuid = player.getUniqueId().toString();
        try {
            workers.execute(() -> onResult.accept(ask(name, uuid, message, settings, Long.MAX_VALUE)));
        } catch (RejectedExecutionException busy) {
            skippedWhileBusy();
        }
    }

    public ModerationApiResult checkMessage(Player player, String message, ModerationSettings settings) {
        return checkMessage(player, message, settings, ignored -> {
        });
    }

    /**
     * Asks the cloud and waits for its answer, at most {@link #MAX_WAIT_MS}. When
     * the wait runs out while the cloud is already working on the message, the
     * message goes through unchecked and {@code late} gets the cloud's answer when
     * it comes, on a worker thread, so staff can still hear about it.
     */
    public ModerationApiResult checkMessage(Player player, String message, ModerationSettings settings,
                                            Consumer<ModerationApiResult> late) {
        if (!circuit.shouldCallAutomatically()) {
            return ModerationApiResult.transientTransport();
        }

        long waitMs = Math.max(1L, Math.min(
                MAX_WAIT_MS,
                (long) settings.api().connectTimeoutMs() + settings.api().readTimeoutMs() + 400L
        ));
        String name = player.getName();
        String uuid = player.getUniqueId().toString();
        // Whoever gets here first decides: the worker starting or answering, or the chat thread giving up.
        AtomicReference<Waiter> waiter = new AtomicReference<>(Waiter.QUEUED);
        Future<ModerationApiResult> future;
        try {
            future = workers.submit(() -> {
                if (!waiter.compareAndSet(Waiter.QUEUED, Waiter.ASKING)) {
                    return null;  // The message went on without the cloud while it waited for a worker.
                }
                ModerationApiResult result = ask(name, uuid, message, settings, waitMs);
                if (!waiter.compareAndSet(Waiter.ASKING, Waiter.ANSWERED)) {
                    late.accept(result);
                }
                return result;
            });
        } catch (RejectedExecutionException error) {
            return skippedWhileBusy();
        }

        try {
            return future.get(waitMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            if (waiter.compareAndSet(Waiter.QUEUED, Waiter.SKIPPED)) {
                // Still queued: it never reaches the cloud.
                future.cancel(false);
                workers.purge();
                return skippedWhileBusy();
            }
            if (waiter.compareAndSet(Waiter.ASKING, Waiter.LATE)) {
                // Already asking: its worker records what the cloud did, and passes a late answer on.
                return ModerationApiResult.transientTransport();
            }
            // The answer arrived just now.
            return answerOf(future);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (waiter.compareAndSet(Waiter.QUEUED, Waiter.SKIPPED)) {
                future.cancel(false);
                workers.purge();
            } else {
                waiter.compareAndSet(Waiter.ASKING, Waiter.LATE);
            }
            return ModerationApiResult.transientTransport();
        } catch (ExecutionException e) {
            ModerationApiResult result = ModerationApiResult.transientTransport();
            recordApiResult(result, settings.api());
            return result;
        }
    }

    /** Where one message's cloud check stands, between its worker and the chat thread waiting for it. */
    private enum Waiter {
        /** Waiting for a free worker. */
        QUEUED,
        /** A worker is asking the cloud. */
        ASKING,
        /** The cloud answered while chat still waited. */
        ANSWERED,
        /** Chat went on before a worker was free; the cloud is never asked. */
        SKIPPED,
        /** Chat went on while the cloud was being asked; the answer goes to the late consumer. */
        LATE
    }

    private static ModerationApiResult answerOf(Future<ModerationApiResult> answered) {
        try {
            return answered.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ModerationApiResult.transientTransport();
        } catch (ExecutionException e) {
            return ModerationApiResult.transientTransport();
        }
    }
}
