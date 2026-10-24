package com.neomechanical.neomoderation.moderation;

/**
 * One cloud answer. {@code retryAfterSeconds} is how long the free plan asked the server to wait (its cap, its picture
 * allowance or a busy minute), 0 for everything else.
 */
public record ModerationApiResult(Kind kind, String category, long retryAfterSeconds) {
    public ModerationApiResult(Kind kind) {
        this(kind, null);
    }

    public ModerationApiResult(Kind kind, String category) {
        this(kind, category, 0L);
    }

    public enum Kind {
        FLAGGED,
        CLEAR,
        TRANSIENT_TRANSPORT,
        OVERLOADED,
        CLIENT_AUTH,
        INSUFFICIENT_CREDITS,
        CLIENT_REQUEST,
        /** The free plan's cloud checks for today are used up: chat and map art wait for the reset. */
        FREE_PLAN_CAPPED,
        /** The free plan's map pictures for today are used up: map art waits for the reset, chat goes on. */
        FREE_PLAN_PICTURES,
        /** The free plan is busy this minute: wait the seconds it gave, and tell no player. */
        FREE_PLAN_BUSY,
        /** The free plan refused the request's shape: a bug in this version, logged once. */
        FREE_PLAN_REQUEST_SHAPE
    }

    public boolean isFlagged() {
        return kind == Kind.FLAGGED;
    }

    public String reason() {
        return category == null || category.isBlank() ? "platform" : "platform:" + category;
    }

    public boolean tripsTransientBreaker() {
        return kind == Kind.TRANSIENT_TRANSPORT;
    }

    public static ModerationApiResult flagged() {
        return new ModerationApiResult(Kind.FLAGGED, null);
    }

    public static ModerationApiResult flagged(String category) {
        return new ModerationApiResult(Kind.FLAGGED, category);
    }

    public static ModerationApiResult clear() {
        return new ModerationApiResult(Kind.CLEAR);
    }

    public static ModerationApiResult overloaded() {
        return new ModerationApiResult(Kind.OVERLOADED);
    }

    public static ModerationApiResult transientTransport() {
        return new ModerationApiResult(Kind.TRANSIENT_TRANSPORT);
    }

    public static ModerationApiResult clientAuth() {
        return new ModerationApiResult(Kind.CLIENT_AUTH);
    }

    public static ModerationApiResult insufficientCredits() {
        return new ModerationApiResult(Kind.INSUFFICIENT_CREDITS);
    }

    public static ModerationApiResult clientRequest() {
        return new ModerationApiResult(Kind.CLIENT_REQUEST);
    }

    /**
     * Classifies a refusal by the free plan's own code ({@code free_plan_daily_cap}, {@code free_plan_image_allowance},
     * {@code free_plan_busy}, {@code free_plan_request_shape}), and any other by its status alone.
     */
    public static ModerationApiResult fromRefusal(int status, String code, long retryAfterSeconds) {
        long wait = Math.max(0L, retryAfterSeconds);
        return switch (code == null ? "" : code) {
            case "free_plan_daily_cap" -> new ModerationApiResult(Kind.FREE_PLAN_CAPPED, null, wait);
            case "free_plan_image_allowance" -> new ModerationApiResult(Kind.FREE_PLAN_PICTURES, null, wait);
            case "free_plan_busy" -> new ModerationApiResult(Kind.FREE_PLAN_BUSY, null, wait);
            case "free_plan_request_shape" -> new ModerationApiResult(Kind.FREE_PLAN_REQUEST_SHAPE);
            default -> fromHttpFailureStatus(status);
        };
    }

    /**
     * Classifies a non-success HTTP response without conflating payment state,
     * credentials, and malformed requests.
     */
    public static ModerationApiResult fromHttpFailureStatus(int status) {
        if (status == 401 || status == 403) {
            return clientAuth();
        }
        if (status == 402) {
            return insufficientCredits();
        }
        if (status == 408 || status == 429 || status >= 500 || status < 400) {
            return transientTransport();
        }
        return clientRequest();
    }
}
