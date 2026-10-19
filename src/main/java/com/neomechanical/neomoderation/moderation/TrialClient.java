package com.neomechanical.neomoderation.moderation;

import com.neomechanical.neomoderation.config.ModerationApiSettings;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import com.google.gson.JsonObject;
import com.google.gson.JsonElement;

/**
 * Handles communication with the Neomechanical 14-day trial activation service.
 *
 * <p>The distributed signature is a public protocol marker, not proof of
 * installation. Trial eligibility and claiming are enforced server-side.
 */
public final class TrialClient {
    private static final String PLUGIN_SECRET = "nm_trial_sec_2026_eval_sign";
    /** The trial length, as the platform's TRIAL_DURATION_DAYS; used when a reply leaves it out. */
    private static final int TRIAL_DAYS = 14;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();


    public static String computeHmac(String installId, long timestampSeconds) {
        try {
            String message = (installId == null ? "" : installId.trim().toLowerCase()) + ":" + timestampSeconds;
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(PLUGIN_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] rawHmac = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(rawHmac.length * 2);
            for (byte b : rawHmac) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    public TrialResult activateTrial(
            ModerationApiSettings apiSettings,
            String installId,
            String platformInfo
    ) throws TrialException {
        String endpoint = trialUrl(apiSettings.endpoint());
        String body = "{\"installId\":\"" + JsonStrings.escape(installId) + "\",\"platform\":\"" + JsonStrings.escape(platformInfo) + "\"}";
        long timestamp = Instant.now().getEpochSecond();
        String hmac = computeHmac(installId, timestamp);

        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(10))
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .header("User-Agent", ClientIdentity.userAgent())
                    .header("x-plugin-product", "NeoModeration")
                    .header("x-plugin-timestamp", String.valueOf(timestamp))
                    .header("x-plugin-hmac", hmac)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
        } catch (IllegalArgumentException e) {
            throw new TrialException(TrialError.INVALID_ENDPOINT, "Invalid trial endpoint URL");
        }

        try {
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status == 201 || status == 200) {
                return parseSuccess(response.body());
            }
            if (status == 409) {
                throw new TrialException(TrialError.ALREADY_CLAIMED, "Trial has already been claimed for this server");
            }
            if (status == 429) {
                throw new TrialException(TrialError.RATE_LIMITED, "Trial rate limit reached for this network");
            }
            if (status == 403) {
                throw new TrialException(TrialError.FORBIDDEN, "Trial is only accessible to verified NeoModeration installations");
            }
            throw new TrialException(TrialError.SERVER_ERROR, "HTTP " + status);
        } catch (TrialException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TrialException(TrialError.TRANSPORT_ERROR, "Trial request interrupted");
        } catch (Exception e) {
            throw new TrialException(TrialError.TRANSPORT_ERROR, "Trial request failed");
        }
    }

    public TrialStatusResult fetchTrialStatus(ModerationApiSettings apiSettings) throws TrialException {
        String endpoint = trialUrl(apiSettings.endpoint()) + "/status";
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(10))
                    .header("Accept", "application/json")
                    .header("User-Agent", ClientIdentity.userAgent())
                    .header("Authorization", "Bearer " + apiSettings.apiKey())
                    .GET()
                    .build();
        } catch (IllegalArgumentException e) {
            throw new TrialException(TrialError.INVALID_ENDPOINT, "Invalid trial status endpoint URL");
        }

        try {
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status == 200) {
                return parseStatusSuccess(response.body());
            }
            if (status == 401 || status == 403) {
                throw new TrialException(TrialError.FORBIDDEN, "API key rejected");
            }
            throw new TrialException(TrialError.SERVER_ERROR, "HTTP " + status);
        } catch (TrialException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TrialException(TrialError.TRANSPORT_ERROR, "Trial status request interrupted");
        } catch (Exception e) {
            throw new TrialException(TrialError.TRANSPORT_ERROR, "Trial status request failed");
        }
    }

    static String trialUrl(String endpoint) {
        String trimmed = endpoint == null ? "" : endpoint.trim();
        int slash = trimmed.indexOf('/', "https://".length());
        String base = slash > 0 ? trimmed.substring(0, slash) : trimmed;
        return base + "/v1/plugins/neomoderation/trial";
    }

    private static TrialResult parseSuccess(String body) throws TrialException {
        JsonObject response = responseObject(body);
        String apiKey = stringField(response, "apiKey", "");
        if (apiKey.isBlank()) {
            throw new TrialException(TrialError.INVALID_RESPONSE, "Missing apiKey in trial response");
        }
        return new TrialResult(apiKey, stringField(response, "expiresAt", TRIAL_DAYS + " days"),
                daysField(response, TRIAL_DAYS), stringField(response, "claimUrl", CloudRecovery.SIGNUP_URL));
    }

    static TrialStatusResult parseStatusSuccess(String body) throws TrialException {
        JsonObject response = responseObject(body);
        JsonElement trial = response.get("isTrial");
        if (trial == null || !trial.isJsonPrimitive() || !trial.getAsJsonPrimitive().isBoolean()) {
            throw new TrialException(TrialError.INVALID_RESPONSE, "Missing isTrial in trial status response");
        }
        boolean isTrial = trial.getAsBoolean();
        String status = stringField(response, "status", "");
        if ((isTrial && !status.equals("active") && !status.equals("expired"))
                || (!isTrial && !status.equals("standard_workspace"))) {
            throw new TrialException(TrialError.INVALID_RESPONSE, "Invalid trial status response");
        }
        return new TrialStatusResult(isTrial, status, stringField(response, "expiresAt", "unknown"),
                daysField(response, 0), stringField(response, "upgradeUrl",
                stringField(response, "claimUrl", CloudRecovery.BILLING_URL)));
    }

    private static JsonObject responseObject(String body) throws TrialException {
        try {
            JsonObject response = CloudResponseJson.parseObject(body);
            if (response != null) {
                return response;
            }
        } catch (RuntimeException ignored) {
        }
        throw new TrialException(TrialError.INVALID_RESPONSE, "Invalid trial response JSON");
    }

    private static String stringField(JsonObject response, String key, String fallback) throws TrialException {
        JsonElement value = response.get(key);
        if (value == null || value.isJsonNull()) {
            return fallback;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new TrialException(TrialError.INVALID_RESPONSE, "Invalid " + key + " in trial response");
        }
        return value.getAsString();
    }

    private static int daysField(JsonObject response, int fallback) throws TrialException {
        JsonElement value = response.get("daysRemaining");
        if (value == null) {
            return fallback;
        }
        try {
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
                int days = value.getAsBigDecimal().intValueExact();
                if (days >= 0) {
                    return days;
                }
            }
        } catch (ArithmeticException | NumberFormatException ignored) {
        }
        throw new TrialException(TrialError.INVALID_RESPONSE, "Invalid daysRemaining in trial response");
    }

    public enum TrialError {
        ALREADY_CLAIMED,
        RATE_LIMITED,
        FORBIDDEN,
        INVALID_ENDPOINT,
        INVALID_RESPONSE,
        TRANSPORT_ERROR,
        SERVER_ERROR
    }

    public static final class TrialException extends Exception {
        private final TrialError error;

        public TrialException(TrialError error, String message) {
            super(message);
            this.error = error;
        }

        public TrialError error() {
            return error;
        }
    }

    public record TrialResult(String apiKey, String expiresAt, int daysRemaining, String claimUrl) {
        public TrialResult(String apiKey, String expiresAt, int daysRemaining) {
            this(apiKey, expiresAt, daysRemaining, CloudRecovery.SIGNUP_URL);
        }
    }

    public record TrialStatusResult(
            boolean isTrial,
            String status,
            String expiresAt,
            int daysRemaining,
            String upgradeUrl
    ) {
        public boolean isExpired() {
            return isTrial && "expired".equalsIgnoreCase(status);
        }
    }
}
