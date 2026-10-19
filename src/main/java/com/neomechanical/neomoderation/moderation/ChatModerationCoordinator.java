package com.neomechanical.neomoderation.moderation;

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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Sends chat to the cloud. A message whose cloud decision can block waits for it,
 * at most {@link #MAX_WAIT_MS}; one whose decision can only alert staff goes
 * through at once and is checked in the background. Requests run on a few
 * workers with a short queue, so a burst of chat waits its turn instead of
 * skipping the cloud; when the queue is full, or a message's wait runs out while
 * it is still queued, it goes through unchecked as an overload.
 *
 * <p>Only what the cloud itself did counts towards pausing it: each worker
 * records its own call's result, and a call that answered too slowly for chat
 * counts as a failure. Time a message spent waiting for a free worker is the
 * server's load, never the cloud's fault.</p>
 */
public final class ChatModerationCoordinator implements AutoCloseable {
    private static final int WORKERS = 8;
    private static final int QUEUED = 32;
    private static final long MAX_WAIT_MS = 2500L;

    private final AtomicInteger workerSeq = new AtomicInteger();
    private final ModerationCircuitBreaker circuit;
    private final ModerationApiClient apiClient;
    private final ThreadPoolExecutor workers;

    public ChatModerationCoordinator(Logger logger) {
        this(new ModerationCircuitBreaker(logger), new ModerationApiClient());
    }

    ChatModerationCoordinator(ModerationCircuitBreaker circuit, ModerationApiClient apiClient) {
        this(circuit, apiClient, WORKERS, QUEUED);
    }

    ChatModerationCoordinator(ModerationCircuitBreaker circuit, ModerationApiClient apiClient,
                              int workerCount, int queueCapacity) {
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

    /** One cloud call on a worker, recorded as the cloud's own result; slower than chat can wait counts as a failure. */
    private ModerationApiResult ask(String name, String uuid, String message, ModerationSettings settings,
                                    long chatWaitMs) {
        long started = System.nanoTime();
        ModerationApiResult result = apiClient.moderateText(name, uuid, message, settings.api(), settings.categories());
        long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        circuit.record(tookMs > chatWaitMs ? ModerationApiResult.transientTransport() : result);
        return result;
    }

    public void resetCircuit() {
        circuit.reset();
    }

    public ModerationApiClient apiClient() {
        return apiClient;
    }

    public boolean isRemoteCallAllowed() {
        return circuit.isRemoteCallAllowed();
    }

    public ModerationApiResult.Kind lastCloudResultKind() {
        return circuit.lastResultKind();
    }

    /**
     * Records moderation-event calls made outside the normal chat path, such as
     * /nmod test and map-art scans. Account/usage calls must not enter this state.
     */
    public void recordApiResult(ModerationApiResult result) {
        circuit.record(result);
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
        if (!circuit.isRemoteCallAllowed()) {
            return;
        }
        String name = player.getName();
        String uuid = player.getUniqueId().toString();
        try {
            workers.execute(() -> onResult.accept(ask(name, uuid, message, settings, Long.MAX_VALUE)));
        } catch (RejectedExecutionException busy) {
            circuit.record(ModerationApiResult.overloaded());
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
        if (!circuit.isRemoteCallAllowed()) {
            return ModerationApiResult.transientTransport();
        }

        long waitMs = Math.max(1L, Math.min(
                MAX_WAIT_MS,
                (long) settings.api().connectTimeoutMs() + settings.api().readTimeoutMs() + 400L
        ));
        String name = player.getName();
        String uuid = player.getUniqueId().toString();
        // Whoever gets here first decides: the worker with an answer, or the chat thread giving up.
        AtomicReference<Waiter> waiter = new AtomicReference<>(Waiter.WAITING);
        Future<ModerationApiResult> future;
        try {
            future = workers.submit(() -> {
                ModerationApiResult result = ask(name, uuid, message, settings, waitMs);
                if (!waiter.compareAndSet(Waiter.WAITING, Waiter.ANSWERED)) {
                    late.accept(result);
                }
                return result;
            });
        } catch (RejectedExecutionException error) {
            ModerationApiResult result = ModerationApiResult.overloaded();
            circuit.record(result);
            return result;
        }

        try {
            return future.get(waitMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            if (!waiter.compareAndSet(Waiter.WAITING, Waiter.GAVE_UP)) {
                // The answer arrived just now.
                return answerOf(future);
            }
            // Still queued, it never reaches the cloud; already asking, its worker records what the cloud did.
            future.cancel(false);
            workers.purge();
            ModerationApiResult result = ModerationApiResult.overloaded();
            circuit.record(result);
            return result;
        } catch (InterruptedException e) {
            future.cancel(false);
            Thread.currentThread().interrupt();
            return ModerationApiResult.transientTransport();
        } catch (ExecutionException e) {
            ModerationApiResult result = ModerationApiResult.transientTransport();
            circuit.record(result);
            return result;
        }
    }

    private enum Waiter {
        WAITING,
        ANSWERED,
        GAVE_UP
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
