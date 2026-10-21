package com.neomechanical.neomoderation.moderation;

import com.neomechanical.neomoderation.config.ModerationApiSettings;

import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Fetches workspace usage and rate-limit information from Neomechanical using the
 * configured API key. The usage endpoint is derived from the moderation endpoint so a
 * single {@code endpoint} setting keeps them in sync.
 */
public final class NeoMechanicalUsageClient {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    public UsageSummary fetchUsage(ModerationApiSettings apiSettings) throws UsageException {
        HttpResponse<String> response;
        try {
            response = NeomechanicalHttp.send(() -> NeomechanicalHttp.withKey(usageUrl(apiSettings.endpoint()), TIMEOUT,
                            apiSettings.apiKey())
                    .header("Accept", "application/json")
                    .GET());
        } catch (NeomechanicalHttp.NoAnswer noAnswer) {
            throw switch (noAnswer.reason()) {
                case INVALID_REQUEST -> new UsageException(ModerationApiResult.Kind.CLIENT_REQUEST, "invalid endpoint");
                case INTERRUPTED -> new UsageException(ModerationApiResult.Kind.TRANSIENT_TRANSPORT, "interrupted");
                case FAILED -> new UsageException(ModerationApiResult.Kind.TRANSIENT_TRANSPORT, "request failed");
            };
        }
        if (response.statusCode() != 200) {
            ModerationApiResult result = ModerationApiResult.fromHttpFailureStatus(response.statusCode());
            throw new UsageException(result.kind(), "HTTP " + response.statusCode());
        }
        try {
            return UsageSummary.parse(response.body());
        } catch (IllegalArgumentException unreadable) {
            // A reply this version cannot read says nothing about the account, so it reads like one that never came.
            throw new UsageException(ModerationApiResult.Kind.TRANSIENT_TRANSPORT, unreadable.getMessage());
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
