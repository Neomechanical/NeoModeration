package com.neomechanical.neomoderation.moderation;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;

/**
 * Zero-dependency Discord webhook formatter and sender for staff detection alerts.
 * Restricts delivery to official Discord HTTPS webhook endpoints.
 */
public final class DiscordWebhookNotifier {
    private static final Duration WEBHOOK_TIMEOUT = Duration.ofSeconds(5);

    private final HttpClient httpClient;

    public DiscordWebhookNotifier() {
        this(HttpClient.newBuilder().connectTimeout(WEBHOOK_TIMEOUT).build());
    }

    public DiscordWebhookNotifier(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public static boolean isValidWebhookUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        String trimmed = url.trim().toLowerCase(Locale.ROOT);
        if (!trimmed.startsWith("https://discord.com/api/webhooks/")
                && !trimmed.startsWith("https://discordapp.com/api/webhooks/")
                && !trimmed.startsWith("https://ptb.discord.com/api/webhooks/")
                && !trimmed.startsWith("https://canary.discord.com/api/webhooks/")) {
            return false;
        }
        try {
            URI uri = URI.create(url.trim());
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    public static String buildPayload(String player, String surface, String reason,
                                      String previewText, String actionsText, boolean monitorStyle) {
        String title = monitorStyle ? "NeoModeration Monitor Alert" : "NeoModeration Enforcement Alert";
        int color = monitorStyle ? 0xF59E0B : 0x818CF8;
        String description = "**Player:** `" + escapeJson(player) + "`\\n"
                + "**Surface:** `" + escapeJson(surface) + "`\\n"
                + "**Rule:** `" + escapeJson(reason) + "`\\n"
                + "**Action:** `" + escapeJson(actionsText) + "`\\n"
                + "**Preview:** `" + escapeJson(previewText) + "`";
        return "{\"username\":\"NeoModeration\",\"embeds\":[{\"title\":\""
                + escapeJson(title) + "\",\"color\":" + color
                + ",\"description\":\"" + description + "\"}]}";
    }

    /** JSON-escaped, with backticks swapped for quotes so a value cannot close its Discord code span. */
    static String escapeJson(String value) {
        return JsonStrings.escape(value == null ? null : value.replace('`', '\''));
    }

    public void post(String webhookUrl, String payload) {
        if (!isValidWebhookUrl(webhookUrl)) {
            return;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(webhookUrl.trim()))
                    .timeout(WEBHOOK_TIMEOUT)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("User-Agent", ClientIdentity.userAgent())
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build();
            httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignored) {
            // Webhook delivery failures must never interrupt moderation.
        }
    }
}
