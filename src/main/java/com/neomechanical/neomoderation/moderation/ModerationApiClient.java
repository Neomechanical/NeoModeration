package com.neomechanical.neomoderation.moderation;

import com.neomechanical.neomoderation.config.ModerationApiSettings;
import com.neomechanical.neomoderation.config.ModerationCategorySettings;

import java.io.IOException;
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

        HttpRequest request;
        try {
            request = NeomechanicalHttp.withKey(apiSettings.endpoint(), Duration.ofMillis(totalMs), apiSettings.apiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
        } catch (IllegalArgumentException e) {
            return ModerationApiResult.clientRequest();
        }

        try {
            HttpResponse<String> response = NeomechanicalHttp.CLIENT.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                return ModerationApiResult.fromHttpFailureStatus(status);
            }
            return ChatModerationResponseParser.parseResult(response.body(), categorySettings);
        } catch (IOException e) {
            return ModerationApiResult.transientTransport();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ModerationApiResult.transientTransport();
        }
    }
}
