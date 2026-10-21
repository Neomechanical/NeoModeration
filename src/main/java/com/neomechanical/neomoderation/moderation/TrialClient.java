package com.neomechanical.neomoderation.moderation;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.neomechanical.neomoderation.config.ModerationApiSettings;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

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
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    public static String computeHmac(String installId, long timestampSeconds) {
        try {
            String message = (installId == null ? "" : installId.trim().toLowerCase(Locale.ROOT)) + ":" + timestampSeconds;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(PLUGIN_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            return "";  // every Java has HmacSHA256; an unsigned request is refused by the service, not by the plugin
        }
    }

    public TrialResult activateTrial(
            ModerationApiSettings apiSettings,
            String installId,
            String platformInfo
    ) throws TrialException {
        String body = "{\"installId\":\"" + JsonStrings.escape(installId) + "\",\"platform\":\"" + JsonStrings.escape(platformInfo) + "\"}";
        long timestamp = Instant.now().getEpochSecond();
        String hmac = computeHmac(installId, timestamp);
        HttpResponse<String> response = send("Trial", () -> NeomechanicalHttp.request(trialUrl(apiSettings.endpoint()), TIMEOUT)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("x-plugin-product", "NeoModeration")
                .header("x-plugin-timestamp", String.valueOf(timestamp))
                .header("x-plugin-hmac", hmac)
                .POST(HttpRequest.BodyPublishers.ofString(body)));
        int status = response.statusCode();
        if (status == 201 || status == 200) {
            return parseSuccess(response.body());
        }
        throw switch (status) {
            case 409 -> new TrialException(TrialError.ALREADY_CLAIMED, "Trial has already been claimed for this server");
            case 429 -> new TrialException(TrialError.RATE_LIMITED, "Trial rate limit reached for this network");
            case 403 -> new TrialException(TrialError.FORBIDDEN, "Trial is only accessible to verified NeoModeration installations");
            default -> new TrialException(TrialError.SERVER_ERROR, "HTTP " + status);
        };
    }

    public TrialStatusResult fetchTrialStatus(ModerationApiSettings apiSettings) throws TrialException {
        HttpResponse<String> response = send("Trial status", () -> NeomechanicalHttp.withKey(
                        trialUrl(apiSettings.endpoint()) + "/status", TIMEOUT, apiSettings.apiKey())
                .header("Accept", "application/json")
                .GET());
        int status = response.statusCode();
        if (status == 200) {
            return parseStatusSuccess(response.body());
        }
        if (status == 401) {
            throw new TrialException(TrialError.FORBIDDEN, "API key rejected");
        }
        // The trial service itself never answers 403; a proxy in front of it may, and says nothing about the key.
        throw new TrialException(TrialError.SERVER_ERROR, "HTTP " + status);
    }

    /** Sends one request to the trial service; {@code what} ("Trial", "Trial status") names it when no reply came. */
    private static HttpResponse<String> send(String what, Supplier<HttpRequest.Builder> request) throws TrialException {
        try {
            return NeomechanicalHttp.send(request);
        } catch (NeomechanicalHttp.NoAnswer noAnswer) {
            throw switch (noAnswer.reason()) {
                case INVALID_REQUEST -> new TrialException(TrialError.INVALID_ENDPOINT,
                        "Invalid " + what.toLowerCase(Locale.ROOT) + " endpoint URL");
                case INTERRUPTED -> new TrialException(TrialError.TRANSPORT_ERROR, what + " request interrupted");
                case FAILED -> new TrialException(TrialError.TRANSPORT_ERROR, what + " request failed");
            };
        }
    }

    static String trialUrl(String endpoint) {
        String trimmed = endpoint == null ? "" : endpoint.trim();
        int slash = trimmed.indexOf('/', "https://".length());
        String base = slash > 0 ? trimmed.substring(0, slash) : trimmed;
        return base + "/v1/plugins/neomoderation/trial";
    }

    static TrialResult parseSuccess(String body) throws TrialException {
        JsonObject response = responseObject(body);
        String apiKey = stringField(response, "apiKey", "");
        if (apiKey.isBlank()) {
            throw new TrialException(TrialError.INVALID_RESPONSE, "Missing apiKey in trial response");
        }
        // The key is issued and single-use per install: nothing after this point may lose it, so whatever else this
        // version cannot read falls back. A newer trial service's fields must never strand an install.
        int days = lenient(() -> daysField(response, TRIAL_DAYS), TRIAL_DAYS);
        String expires = lenient(() -> expiresAt(response, days),
                Instant.now().plus(days, ChronoUnit.DAYS).toString());
        String claimUrl = lenient(() -> stringField(response, "claimUrl", CloudRecovery.SIGNUP_URL),
                CloudRecovery.SIGNUP_URL);
        return new TrialResult(apiKey, expires, days, claimUrl);
    }

    private interface Read<T> {
        T get() throws TrialException;
    }

    private static <T> T lenient(Read<T> read, T fallback) {
        try {
            return read.get();
        } catch (TrialException unreadable) {
            return fallback;
        }
    }

    /** The expiry the service sent, else today plus the days it says are left, so a date can always be shown. */
    private static String expiresAt(JsonObject response, int daysLeft) throws TrialException {
        String sent = stringField(response, "expiresAt", "");
        return sent.isBlank() ? Instant.now().plus(Math.max(0, daysLeft), ChronoUnit.DAYS).toString() : sent;
    }

    static TrialStatusResult parseStatusSuccess(String body) throws TrialException {
        JsonObject response = responseObject(body);
        JsonElement trial = response.get("isTrial");
        if (trial == null || !trial.isJsonPrimitive() || !trial.getAsJsonPrimitive().isBoolean()) {
            throw new TrialException(TrialError.INVALID_RESPONSE, "Missing isTrial in trial status response");
        }
        boolean isTrial = trial.getAsBoolean();
        String status = stringField(response, "status", "");
        // A trial the platform revoked ends like one that ran out; "revoked" has been sent since the trial service began.
        if ((isTrial && !status.equals("active") && !status.equals("expired") && !status.equals("revoked"))
                || (!isTrial && !status.equals("standard_workspace"))) {
            throw new TrialException(TrialError.INVALID_RESPONSE, "Invalid trial status response");
        }
        int days = daysField(response, 0);
        // A trial without a link gets none here, so whoever shows it picks the page for its state (CloudPlanNotices);
        // billing is a key from an account's way on, never a trial's, which has no account to sign in to.
        return new TrialStatusResult(isTrial, status, expiresAt(response, days), days,
                stringField(response, "upgradeUrl", stringField(response, "claimUrl",
                        isTrial ? "" : CloudRecovery.BILLING_URL)),
                lenient(() -> stringField(response, "activatedAt", ""), ""));
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
            String upgradeUrl,
            String activatedAt
    ) {
        private static final long DAY_MILLIS = TimeUnit.DAYS.toMillis(1);

        public TrialStatusResult(boolean isTrial, String status, String expiresAt, int daysRemaining, String upgradeUrl) {
            this(isTrial, status, expiresAt, daysRemaining, upgradeUrl, "");
        }

        public boolean isExpired() {
            return isTrial && "expired".equalsIgnoreCase(status);
        }

        public boolean isRevoked() {
            return isTrial && "revoked".equalsIgnoreCase(status);
        }

        /** A trial that ran out or was revoked: its key no longer works for cloud checks. */
        public boolean hasEnded() {
            return isExpired() || isRevoked();
        }

        /**
         * A trial that has ended by {@code nowMillis}: the service said so, or its end date has passed since it
         * answered. A trial key stops working at its end date, so an older answer of "active" no longer holds.
         */
        public boolean endedBy(long nowMillis) {
            long end = endMillis();
            return hasEnded() || (isTrial && end != Long.MIN_VALUE && end <= nowMillis);
        }

        /**
         * The days a running trial has left at {@code nowMillis}, counted as the trial service counts them (any
         * part of a day is a day); the days it sent when its end date cannot be read.
         */
        public int daysLeft(long nowMillis) {
            long end = endMillis();
            if (end == Long.MIN_VALUE) {
                return daysRemaining;
            }
            long left = end - nowMillis;
            return left <= 0 ? 0 : (int) Math.max(1, (left + DAY_MILLIS - 1) / DAY_MILLIS);
        }

        private long endMillis() {
            try {
                return Instant.parse(expiresAt).toEpochMilli();
            } catch (RuntimeException unreadable) {
                return Long.MIN_VALUE;
            }
        }
    }
}
