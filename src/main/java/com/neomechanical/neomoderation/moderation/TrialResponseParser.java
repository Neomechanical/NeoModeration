package com.neomechanical.neomoderation.moderation;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.neomechanical.neomoderation.moderation.TrialClient.TrialError;
import com.neomechanical.neomoderation.moderation.TrialClient.TrialException;
import com.neomechanical.neomoderation.moderation.TrialClient.TrialResult;
import com.neomechanical.neomoderation.moderation.TrialClient.TrialStatusResult;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Pure JSON response and refusal parser for the Neomechanical trial service.
 * Kept separate from HTTP transport in {@link TrialClient} so protocol rules
 * have one owner and stay easy to test directly.
 */
final class TrialResponseParser {
    private TrialResponseParser() {
    }

    /**
     * What a refusal from the trial service means, read from its {@code error} code. An older service, or a proxy,
     * that names none is read from the status alone, as before 1.8.0.
     */
    static TrialException refusal(int status, String text, long retryAfterHeader) {
        JsonObject body = replyObject(text);
        String code = "";
        String retryAt = "";
        if (body != null) {
            code = lenient(() -> stringField(body, "error", ""), "");
            retryAt = lenient(() -> stringField(body, "retryAt", ""), "");
        }
        long retryAfter = retryAfterSeconds(retryAfterHeader, body);
        TrialError error = switch (code) {
            case "trial_already_claimed" -> TrialError.ALREADY_CLAIMED;
            case "ip_trial_lifetime_limit" -> TrialError.LIFETIME_LIMIT;
            case "ip_trial_limit_reached" -> TrialError.COOLDOWN;
            case "trial_activation_busy", "activation_rate_limited", "global_trial_rate_limit" -> TrialError.BUSY;
            case "free_plan_closed" -> TrialError.FREE_PLAN_CLOSED;
            case "invalid_api_key", "missing_authorization" -> TrialError.FORBIDDEN;
            default -> switch (status) {
                case 409 -> code.isEmpty() ? TrialError.ALREADY_CLAIMED : TrialError.REFUSED;
                case 429 -> TrialError.RATE_LIMITED;
                case 401 -> TrialError.FORBIDDEN;
                case 403 -> code.isEmpty() ? TrialError.FORBIDDEN : TrialError.REFUSED;
                case 400 -> code.isEmpty() ? TrialError.SERVER_ERROR : TrialError.REFUSED;
                default -> TrialError.SERVER_ERROR;
            };
        };
        return new TrialException(error, code.isEmpty() ? "HTTP " + status : code, code, retryAt, retryAfter);
    }

    static TrialResult parseSuccess(String body) throws TrialException {
        JsonObject response = responseObject(body);
        String apiKey = stringField(response, "apiKey", "");
        if (apiKey.isBlank()) {
            throw new TrialException(TrialError.INVALID_RESPONSE, "Missing apiKey in trial response");
        }
        // The key is issued and single-use per install: nothing after this point may lose it, so whatever else this
        // version cannot read falls back. A newer trial service's fields must never strand an install.
        if (onFreePlan(response)) {
            // A free plan has no end. Limits this version cannot read show as unknown (0), never as a trial's days.
            FreePlan plan = FreePlan.read(response);
            return new TrialResult(apiKey, "", 0, lenient(() -> stringField(response, "claimUrl", ""), ""),
                    plan != null ? plan : new FreePlan(0, 0, 0, 0, "", false, false));
        }
        int days = lenient(() -> daysField(response, TrialClient.TRIAL_DAYS), TrialClient.TRIAL_DAYS);
        String expires = lenient(() -> expiresAt(response, days),
                Instant.now().plus(days, ChronoUnit.DAYS).toString());
        String claimUrl = lenient(() -> stringField(response, "claimUrl", CloudRecovery.SIGNUP_URL),
                CloudRecovery.SIGNUP_URL);
        return new TrialResult(apiKey, expires, days, claimUrl);
    }

    static TrialStatusResult parseStatusSuccess(String body) throws TrialException {
        JsonObject response = responseObject(body);
        JsonElement trial = response.get("isTrial");
        if (trial == null || !trial.isJsonPrimitive() || !trial.getAsJsonPrimitive().isBoolean()) {
            throw new TrialException(TrialError.INVALID_RESPONSE, "Missing isTrial in trial status response");
        }
        boolean isTrial = trial.getAsBoolean();
        String status = stringField(response, "status", "");
        if (!isTrial && onFreePlan(response) && (status.equals("revoked") || status.equals("expired"))) {
            // A free plan whose key was revoked, or given an end by a rotation that passed: its way on is an account.
            return new TrialStatusResult(false, status, "", 0, CloudRecovery.SIGNUP_URL, "", null, false);
        }
        if (!isTrial && onFreePlan(response)) {
            FreePlan plan = FreePlan.read(response);
            if (plan == null || !status.equals("free_plan")) {
                throw new TrialException(TrialError.INVALID_RESPONSE, "Invalid free plan in trial status response");
            }
            // An unclaimed plan's link comes only to its own install; a claimed plan's way to credits is its account.
            String claimUrl = stringField(response, "claimUrl", "");
            return new TrialStatusResult(false, status, "", 0,
                    plan.claimed() ? CloudRecovery.BILLING_URL : claimUrl, "", plan, false);
        }
        // A trial the platform revoked ends like one that ran out; "revoked" has been sent since the trial service began.
        if ((isTrial && !status.equals("active") && !status.equals("expired") && !status.equals("revoked"))
                || (!isTrial && !status.equals("standard_workspace"))) {
            throw new TrialException(TrialError.INVALID_RESPONSE, "Invalid trial status response");
        }
        int days = daysField(response, 0);
        // A trial without a link gets none here, so whoever shows it picks the page for its state (CloudPlanNotices);
        // billing is a key from an account's way on, never a trial's, which has no account to sign in to.
        JsonElement open = response.get("freePlanOpen");
        return new TrialStatusResult(isTrial, status, expiresAt(response, days), days,
                stringField(response, "upgradeUrl", stringField(response, "claimUrl",
                        isTrial ? "" : CloudRecovery.BILLING_URL)),
                lenient(() -> stringField(response, "activatedAt", ""), ""), null,
                isTrial && open != null && open.isJsonPrimitive() && open.getAsJsonPrimitive().isBoolean()
                        && open.getAsBoolean());
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

    /** The reply as a JSON object, or null when it is none. */
    private static JsonObject replyObject(String text) {
        try {
            return CloudResponseJson.parseObject(text);
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    /** The Retry-After header's seconds, else the reply's {@code retryAfterSeconds}; 0 when neither says. */
    private static long retryAfterSeconds(long header, JsonObject body) {
        if (header > 0) {
            return header;
        }
        JsonElement value = body == null ? null : body.get("retryAfterSeconds");
        try {
            if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
                return Math.max(0L, value.getAsLong());
            }
        } catch (NumberFormatException ignored) {
        }
        return 0L;
    }

    /** The expiry the service sent, else today plus the days it says are left, so a date can always be shown. */
    private static String expiresAt(JsonObject response, int daysLeft) throws TrialException {
        String sent = stringField(response, "expiresAt", "");
        return sent.isBlank() ? Instant.now().plus(Math.max(0, daysLeft), ChronoUnit.DAYS).toString() : sent;
    }

    /** A reply about a free plan ({@code plan: "free"}), which carries no trial's end. */
    private static boolean onFreePlan(JsonObject response) {
        return "free".equals(lenient(() -> stringField(response, "plan", ""), ""));
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
}
