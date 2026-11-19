package com.neomechanical.neomoderation.moderation;

import com.neomechanical.neomoderation.config.ModerationApiSettings;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Handles communication with the Neomechanical trial service: a new install's free plan (1.8.0 on) or 14-day trial,
 * its status, and a running trial's move onto the free plan.
 *
 * <p>The distributed signature is a public protocol marker, not proof of
 * installation. Trial eligibility and claiming are enforced server-side.
 *
 * <p>Status and conversion carry this server's install id in {@value #INSTALL_ID_HEADER}, so a free plan's claim link
 * goes only to its own install; the moderation API never gets it.
 */
public final class TrialClient {
    private static final String PLUGIN_SECRET = "nm_trial_sec_2026_eval_sign";
    /** The trial length, as the platform's TRIAL_DURATION_DAYS; used when a reply leaves it out. */
    static final int TRIAL_DAYS = 14;
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    static final String INSTALL_ID_HEADER = "X-NeoModeration-Install-Id";
    /**
     * The terms an admin accepts on the start screen ({@code /nmod trial}): the date of Neomechanical's published
     * terms, which the platform stores with the activation. A date it never published is not stored.
     */
    public static final String TERMS_VERSION = "2026-10-12";

    private final Supplier<String> installId;

    /** A client that names no install: status and conversion then carry no install id, and get no claim link. */
    public TrialClient() {
        this(() -> "");
    }

    /** @param installId this server's install id ({@code InstallIdentity}), read when a request needs it */
    public TrialClient(Supplier<String> installId) {
        this.installId = installId;
    }

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
        return activate(apiSettings, installId, platformInfo, false);
    }

    /**
     * Starts this server's cloud plan, having shown the admin the terms ({@link #TERMS_VERSION}). From 1.8.0 a new
     * install gets the free plan once it is open, else a trial. {@code comeBack}: a trial that ran out asks to come
     * back on the free plan, once ({@code /nmod trial start} after it ended).
     */
    public TrialResult activate(
            ModerationApiSettings apiSettings,
            String installId,
            String platformInfo,
            boolean comeBack
    ) throws TrialException {
        String body = "{\"installId\":\"" + JsonStrings.escape(installId) + "\",\"platform\":\"" + JsonStrings.escape(platformInfo)
                + "\",\"termsVersion\":\"" + TERMS_VERSION + "\"" + (comeBack ? ",\"plan\":\"free\"" : "") + "}";
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
        throw refusal(response);
    }

    /**
     * Moves this server's running trial onto the free plan, keeping its key: the admin said yes in game. Answers the
     * free plan's status; a refusal names its {@link TrialException#code()} ({@code trial_ended}, {@code
     * install_mismatch}, {@code free_plan_not_open}...).
     */
    public TrialStatusResult convertToFreePlan(ModerationApiSettings apiSettings) throws TrialException {
        HttpResponse<String> response = send("Free plan", () -> withInstallId(NeomechanicalHttp.withKey(
                        trialUrl(apiSettings.endpoint()).replaceFirst("/trial$", "/plan"), TIMEOUT, apiSettings.apiKey()))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"consent\":true}")));
        if (response.statusCode() == 200) {
            return parseStatusSuccess(response.body());
        }
        throw refusal(response);
    }

    public TrialStatusResult fetchTrialStatus(ModerationApiSettings apiSettings) throws TrialException {
        HttpResponse<String> response = send("Trial status", () -> withInstallId(NeomechanicalHttp.withKey(
                        trialUrl(apiSettings.endpoint()) + "/status", TIMEOUT, apiSettings.apiKey()))
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

    /** The request with this server's install id, when it has one. */
    private HttpRequest.Builder withInstallId(HttpRequest.Builder request) {
        String id = installId.get();
        return id == null || id.isBlank() ? request : request.header(INSTALL_ID_HEADER, id.trim());
    }

    static TrialException refusal(HttpResponse<String> response) {
        return refusal(response.statusCode(), response.body(),
                response.headers().firstValueAsLong("Retry-After").orElse(0L));
    }

    static TrialException refusal(int status, String text, long retryAfterHeader) {
        return TrialResponseParser.refusal(status, text, retryAfterHeader);
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
        return TrialResponseParser.parseSuccess(body);
    }

    static TrialStatusResult parseStatusSuccess(String body) throws TrialException {
        return TrialResponseParser.parseStatusSuccess(body);
    }

    public enum TrialError {
        ALREADY_CLAIMED,
        /** Too many requests, for no reason the service named. */
        RATE_LIMITED,
        /** This network has had its lifetime's trials: the way on is a free account. */
        LIFETIME_LIMIT,
        /** A trial started from this network recently: another can start from {@link TrialException#retryAt()}. */
        COOLDOWN,
        /** The service is busy: try again after {@link TrialException#retryAfterSeconds()}. */
        BUSY,
        /** New free plans are paused; existing ones keep working. */
        FREE_PLAN_CLOSED,
        /** Refused for the reason its {@link TrialException#code()} names. */
        REFUSED,
        FORBIDDEN,
        INVALID_ENDPOINT,
        INVALID_RESPONSE,
        TRANSPORT_ERROR,
        SERVER_ERROR
    }

    public static final class TrialException extends Exception {
        private final TrialError error;
        private final String code;
        private final String retryAt;
        private final long retryAfterSeconds;

        public TrialException(TrialError error, String message) {
            this(error, message, "", "", 0L);
        }

        public TrialException(TrialError error, String message, String code, String retryAt, long retryAfterSeconds) {
            super(message);
            this.error = error;
            this.code = code == null ? "" : code;
            this.retryAt = retryAt == null ? "" : retryAt;
            this.retryAfterSeconds = Math.max(0L, retryAfterSeconds);
        }

        public TrialError error() {
            return error;
        }

        /** The service's own code for the refusal ({@code trial_ended}, {@code install_mismatch}...), or empty. */
        public String code() {
            return code;
        }

        /** The day another trial can start from this network (a cooldown), as ISO; empty when none was given. */
        public String retryAt() {
            return retryAt;
        }

        /** How long to wait before asking again; 0 when the service did not say. */
        public long retryAfterSeconds() {
            return retryAfterSeconds;
        }
    }

    /**
     * A started plan: its key, and either a trial's end ({@code freePlan} null) or the free plan ({@code expiresAt}
     * empty, as a free plan has no end).
     */
    public record TrialResult(String apiKey, String expiresAt, int daysRemaining, String claimUrl, FreePlan freePlan) {
        public TrialResult(String apiKey, String expiresAt, int daysRemaining) {
            this(apiKey, expiresAt, daysRemaining, CloudRecovery.SIGNUP_URL);
        }

        public TrialResult(String apiKey, String expiresAt, int daysRemaining, String claimUrl) {
            this(apiKey, expiresAt, daysRemaining, claimUrl, null);
        }

        public boolean onFreePlan() {
            return freePlan != null;
        }
    }

    /**
     * What the trial service says of a key: a trial (running, ended or revoked), the free plan ({@code freePlan}, whose
     * {@code upgradeUrl} is its claim link while unclaimed on this install, its account's billing once claimed, else
     * empty) or a key from an account. {@code freePlanOpen}: a trial this install may move onto the free plan now (a
     * running one with the admin's yes, an ended one once); false when the service does not say.
     */
    public record TrialStatusResult(
            boolean isTrial,
            String status,
            String expiresAt,
            int daysRemaining,
            String upgradeUrl,
            String activatedAt,
            FreePlan freePlan,
            boolean freePlanOpen
    ) {
        private static final long DAY_MILLIS = TimeUnit.DAYS.toMillis(1);

        public TrialStatusResult(boolean isTrial, String status, String expiresAt, int daysRemaining, String upgradeUrl) {
            this(isTrial, status, expiresAt, daysRemaining, upgradeUrl, "");
        }

        public TrialStatusResult(boolean isTrial, String status, String expiresAt, int daysRemaining, String upgradeUrl,
                                 String activatedAt) {
            this(isTrial, status, expiresAt, daysRemaining, upgradeUrl, activatedAt, null, false);
        }

        public boolean onFreePlan() {
            return freePlan != null;
        }

        /** A free plan whose key no longer works: revoked, or ended by a key rotation that passed. */
        public boolean freePlanEnded() {
            return !isTrial && freePlan == null && ("revoked".equals(status) || "expired".equals(status));
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
