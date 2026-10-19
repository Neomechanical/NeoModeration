package com.neomechanical.neomoderation.moderation;

import com.neomechanical.neomoderation.config.ModerationApiSettings;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Fetches workspace usage and rate-limit information from Neomechanical using the
 * configured API key. The usage endpoint is derived from the moderation endpoint so a
 * single {@code endpoint} setting keeps them in sync.
 */
public final class NeoMechanicalUsageClient {
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public UsageSummary fetchUsage(ModerationApiSettings apiSettings) throws UsageException {
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(usageUrl(apiSettings.endpoint())))
                    .timeout(Duration.ofSeconds(10))
                    .header("Accept", "application/json")
                    .header("User-Agent", ClientIdentity.userAgent())
                    .header("Authorization", "Bearer " + apiSettings.apiKey())
                    .GET()
                    .build();
        } catch (IllegalArgumentException e) {
            throw new UsageException(ModerationApiResult.Kind.CLIENT_REQUEST, "invalid endpoint");
        }

        try {
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                ModerationApiResult result = ModerationApiResult.fromHttpFailureStatus(response.statusCode());
                throw new UsageException(result.kind(), "HTTP " + response.statusCode());
            }
            return UsageSummary.parse(response.body());
        } catch (UsageException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UsageException(ModerationApiResult.Kind.TRANSIENT_TRANSPORT, "interrupted");
        } catch (Exception e) {
            throw new UsageException(ModerationApiResult.Kind.TRANSIENT_TRANSPORT, "request failed");
        }
    }

    /**
     * Derives the workspace-usage URL from the moderation endpoint by replacing its final
     * path segment (e.g. {@code /v1/events}) with {@code /v1/workspace/usage}.
     */
    static String usageUrl(String endpoint) {
        return ModerationApiSettings.resolveSiblingEndpoint(endpoint, "/workspace/usage");
    }

    public static final class UsageException extends Exception {
        private final ModerationApiResult.Kind kind;

        public UsageException(ModerationApiResult.Kind kind, String message) {
            super(message);
            this.kind = kind;
        }

        public ModerationApiResult.Kind kind() {
            return kind;
        }
    }
}
