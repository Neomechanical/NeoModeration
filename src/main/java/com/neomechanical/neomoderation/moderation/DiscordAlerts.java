package com.neomechanical.neomoderation.moderation;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Posts staff alerts to a Discord webhook from one background thread.
 *
 * <p>Alerts wait in a short queue and go out together every two seconds, up to
 * ten per post (Discord's embed limit), so a spam wave costs at most 30 posts a
 * minute and never trips the webhook's rate limit. Past that, the overflow is
 * summed up as "and N more". Posts allow no mentions, so a player who writes
 * "@everyone" cannot ping the staff channel. Only official Discord webhook
 * addresses are ever contacted.</p>
 */
public final class DiscordAlerts implements AutoCloseable {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final long FLUSH_SECONDS = 2;
    private static final int EMBEDS_PER_POST = 10;
    private static final int MAX_QUEUED = 50;
    private static final int MAX_FIELD = 1024;
    private static final List<String> WEBHOOK_PREFIXES = List.of(
            "https://discord.com/api/webhooks/",
            "https://discordapp.com/api/webhooks/",
            "https://ptb.discord.com/api/webhooks/",
            "https://canary.discord.com/api/webhooks/");

    /** One alert as Discord shows it: a coloured title and ordered fields. */
    public record Alert(String title, int color, Map<String, String> fields) {
        public Alert {
            fields = new LinkedHashMap<>(fields);
        }
    }

    private final HttpClient http;
    private final ScheduledExecutorService sender;
    private final Deque<Queued> queue = new ArrayDeque<>();
    private int overflow;
    private String overflowText = "";
    private volatile String lastProblem;

    private record Queued(String webhookUrl, Alert alert) {
    }

    public DiscordAlerts() {
        this(HttpClient.newBuilder().connectTimeout(TIMEOUT).build(), FLUSH_SECONDS);
    }

    DiscordAlerts(HttpClient http, long flushSeconds) {
        this.http = http;
        this.sender = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "NeoModeration-discord");
            thread.setDaemon(true);
            return thread;
        });
        sender.scheduleWithFixedDelay(this::flush, flushSeconds, flushSeconds, TimeUnit.SECONDS);
    }

    public static boolean isWebhookUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        String trimmed = url.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (WEBHOOK_PREFIXES.stream().noneMatch(lower::startsWith)) {
            return false;
        }
        try {
            URI uri = URI.create(trimmed);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Queues an alert; never blocks. {@code overflowText} is how the summary
     * embed reads when alerts are dropped, with {@code {count}} filled in.
     */
    public void submit(String webhookUrl, Alert alert, String overflowText) {
        if (!isWebhookUrl(webhookUrl)) {
            return;
        }
        synchronized (queue) {
            this.overflowText = overflowText;
            if (queue.size() >= MAX_QUEUED) {
                overflow++;
                return;
            }
            queue.addLast(new Queued(webhookUrl.trim(), alert));
        }
    }

    /** Sends one alert at once, for /nmod discord test, and reports whether Discord took it. */
    public void sendNow(String webhookUrl, Alert alert, Consumer<Boolean> result) {
        sender.execute(() -> result.accept(isWebhookUrl(webhookUrl) && post(webhookUrl.trim(), List.of(alert))));
    }

    /** Why the last post failed, or {@code null} when it worked. */
    public String lastProblem() {
        return lastProblem;
    }

    void flush() {
        List<Queued> batch = new ArrayList<>(EMBEDS_PER_POST);
        int left;
        String summary;
        synchronized (queue) {
            while (!queue.isEmpty() && batch.size() < EMBEDS_PER_POST) {
                batch.add(queue.pollFirst());
            }
            left = queue.size() + overflow;
            if (left > 0 && batch.size() == EMBEDS_PER_POST) {
                // The last slot sums up everything still waiting; the next post starts fresh.
                batch.remove(batch.size() - 1);
                left++;
                queue.clear();
                overflow = 0;
            } else {
                left = 0;
            }
            summary = overflowText;
        }
        if (batch.isEmpty()) {
            return;
        }
        String url = batch.get(0).webhookUrl();
        List<Alert> alerts = new ArrayList<>(batch.size() + 1);
        for (Queued queued : batch) {
            if (queued.webhookUrl().equals(url)) {
                alerts.add(queued.alert());
            }
        }
        if (left > 0) {
            alerts.add(new Alert(summary.replace("{count}", String.valueOf(left)), 0x8C8C8C, Map.of()));
        }
        post(url, alerts);
    }

    private boolean post(String webhookUrl, List<Alert> alerts) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(webhookUrl))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("User-Agent", ClientIdentity.userAgent())
                    .POST(HttpRequest.BodyPublishers.ofString(payload(alerts), StandardCharsets.UTF_8))
                    .build();
            int status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            lastProblem = status >= 200 && status < 300 ? null : "HTTP " + status;
            return lastProblem == null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            lastProblem = "interrupted";
            return false;
        } catch (Exception e) {
            // A failed alert must never interrupt moderation.
            lastProblem = e.getClass().getSimpleName();
            return false;
        }
    }

    static String payload(List<Alert> alerts) {
        StringBuilder json = new StringBuilder("{\"username\":\"NeoModeration\",\"allowed_mentions\":{\"parse\":[]},\"embeds\":[");
        String now = Instant.now().toString();
        for (int i = 0; i < alerts.size(); i++) {
            Alert alert = alerts.get(i);
            json.append(i == 0 ? "" : ",")
                    .append("{\"title\":\"").append(JsonStrings.escape(cap(alert.title(), 256)))
                    .append("\",\"color\":").append(alert.color())
                    .append(",\"timestamp\":\"").append(now).append("\",\"fields\":[");
            int field = 0;
            for (Map.Entry<String, String> entry : alert.fields().entrySet()) {
                if (entry.getValue() == null || entry.getValue().isBlank()) {
                    continue;
                }
                json.append(field++ == 0 ? "" : ",")
                        .append("{\"name\":\"").append(JsonStrings.escape(cap(entry.getKey(), 256)))
                        .append("\",\"value\":\"").append(JsonStrings.escape(cap(entry.getValue(), MAX_FIELD)))
                        .append("\",\"inline\":").append(entry.getValue().length() <= 40).append('}');
            }
            json.append("]}");
        }
        return json.append("]}").toString();
    }

    private static String cap(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max - 3) + "...";
    }

    @Override
    public void close() {
        sender.shutdownNow();
    }
}
