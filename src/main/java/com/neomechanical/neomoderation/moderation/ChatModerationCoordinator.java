package com.neomechanical.neomoderation.moderation;

import com.neomechanical.neomoderation.config.ModerationSettings;
import org.bukkit.entity.Player;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Sends chat to the cloud. A message whose cloud decision can block waits for it,
 * at most {@link #MAX_WAIT_MS}; one whose decision can only alert staff goes
 * through at once and is checked in the background. Requests run on a few
 * workers with a short queue, so a burst of chat waits its turn instead of
 * skipping the cloud; only when the queue is full is a message let through
 * unchecked, as an overload.
 */
public final class ChatModerationCoordinator implements AutoCloseable {
    private static final int WORKERS = 8;
    private static final int QUEUED = 32;
    private static final long MAX_WAIT_MS = 2500L;

    private final AtomicInteger workerSeq = new AtomicInteger();
    private final ModerationCircuitBreaker circuit;
    private final ModerationApiClient apiClient;
    private final ExecutorService workers;

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
            workers.execute(() -> {
                ModerationApiResult result = apiClient.moderateText(name, uuid, message, settings.api(),
                        settings.categories());
                circuit.record(result);
                onResult.accept(result);
            });
        } catch (RejectedExecutionException busy) {
            circuit.record(ModerationApiResult.overloaded());
        }
    }

    public ModerationApiResult checkMessage(Player player, String message, ModerationSettings settings) {
        if (!circuit.isRemoteCallAllowed()) {
            return ModerationApiResult.transientTransport();
        }

        long waitMs = Math.min(
                MAX_WAIT_MS,
                (long) settings.api().connectTimeoutMs() + settings.api().readTimeoutMs() + 400L
        );
        Future<ModerationApiResult> future;
        try {
            future = workers.submit(() -> apiClient.moderateText(
                player.getName(),
                player.getUniqueId().toString(),
                message,
                settings.api(),
                settings.categories()
            ));
        } catch (RejectedExecutionException error) {
            ModerationApiResult result = ModerationApiResult.overloaded();
            circuit.record(result);
            return result;
        }

        try {
            ModerationApiResult result = future.get(Math.max(1L, waitMs), TimeUnit.MILLISECONDS);
            circuit.record(result);
            return result;
        } catch (TimeoutException e) {
            future.cancel(true);
            circuit.record(ModerationApiResult.transientTransport());
            return ModerationApiResult.transientTransport();
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            circuit.record(ModerationApiResult.transientTransport());
            return ModerationApiResult.transientTransport();
        } catch (ExecutionException e) {
            circuit.record(ModerationApiResult.transientTransport());
            return ModerationApiResult.transientTransport();
        }
    }
}
