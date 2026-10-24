package com.neomechanical.neomoderation.moderation;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.neomechanical.neomoderation.config.ModerationApiSettings;
import com.neomechanical.neomoderation.config.ModerationCategorySettings;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public final class ModerationApiClient {

    public ModerationApiResult moderateText(
            String playerName,
            String playerUuid,
            String message,
            ModerationApiSettings apiSettings,
            ModerationCategorySettings categorySettings
    ) {
        String body = ModerationPayloadBuilder.buildText(playerName, playerUuid, message, categorySettings);
        return sendRequest(body, apiSettings, categorySettings);
    }

    public ModerationApiResult moderateImage(
            String playerName,
            String playerUuid,
            String base64Image,
            ModerationApiSettings apiSettings,
            ModerationCategorySettings categorySettings
    ) {
        String body = ModerationPayloadBuilder.buildImage(playerName, playerUuid, base64Image, categorySettings);
        return sendRequest(body, apiSettings, categorySettings);
    }

    private ModerationApiResult sendRequest(
            String body,
            ModerationApiSettings apiSettings,
            ModerationCategorySettings categorySettings
    ) {
        long connectMs = Math.max(1, apiSettings.connectTimeoutMs());
        long readMs = Math.max(1, apiSettings.readTimeoutMs());
        long totalMs = Math.min(60_000L, connectMs + readMs + 500L);

        HttpResponse<String> response;
        try {
            response = NeomechanicalHttp.send(() -> NeomechanicalHttp.withKey(apiSettings.endpoint(), Duration.ofMillis(totalMs),
                            apiSettings.apiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)));
        } catch (NeomechanicalHttp.NoAnswer noAnswer) {
            return noAnswer.reason() == NeomechanicalHttp.NoAnswer.Reason.INVALID_REQUEST
                    ? ModerationApiResult.clientRequest()
                    : ModerationApiResult.transientTransport();
        }
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            return refusal(response);
        }
        return ChatModerationResponseParser.parseResult(response.body(), categorySettings);
    }

    /** A refusal, read by the free plan's code in its body and the wait in its Retry-After header (else its body). */
    static ModerationApiResult refusal(HttpResponse<String> response) {
        return refusal(response.statusCode(), response.body(),
                response.headers().firstValueAsLong("Retry-After").orElse(0L));
    }

    /** As {@link #refusal(HttpResponse)}, from the status, the body and the Retry-After header's seconds (0: none). */
    static ModerationApiResult refusal(int status, String text, long retryAfterHeader) {
        String code = "";
        long wait = retryAfterHeader;
        try {
            JsonObject body = CloudResponseJson.parseObject(text);
            if (body != null) {
                JsonElement sent = body.get("code");
                code = sent != null && sent.isJsonPrimitive() && sent.getAsJsonPrimitive().isString() ? sent.getAsString() : "";
                JsonElement seconds = body.get("retryAfterSeconds");
                if (wait <= 0 && seconds != null && seconds.isJsonPrimitive() && seconds.getAsJsonPrimitive().isNumber()) {
                    wait = seconds.getAsLong();
                }
            }
        } catch (RuntimeException unreadable) {
            // A body this version cannot read (a proxy's page): the status alone says what happened.
        }
        return ModerationApiResult.fromRefusal(status, code, wait);
    }
}
