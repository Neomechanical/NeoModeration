package com.neomechanical.neomoderation.moderation;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.time.Instant;

/**
 * A server's free cloud plan, as the trial service describes it from 1.8.0 on: so many cloud checks and map pictures
 * a day, counted per UTC day, with no end date. Unclaimed until someone makes an account for it and verifies their
 * email; "claim pending" while an account is made but not yet verified. A claimed plan's checks past the day's cap are
 * billed to its credits at the normal price.
 *
 * @param resetsAt when today's counts start again (00:00 UTC), as ISO; empty when the service did not say
 */
public record FreePlan(int dailyChecks, int checksToday, int dailyImages, int imagesToday, String resetsAt,
                       boolean claimed, boolean claimPending) {

    /** Today's cloud checks are used up. */
    public boolean checksUsedUp() {
        return dailyChecks > 0 && checksToday >= dailyChecks;
    }

    /** Today's map pictures are used up. */
    public boolean picturesUsedUp() {
        return dailyImages > 0 && imagesToday >= dailyImages;
    }

    /** When today's counts start again, or the next 00:00 UTC after {@code nowMillis} when the service did not say. */
    public Instant resetsAtOr(long nowMillis) {
        try {
            return Instant.parse(resetsAt);
        } catch (RuntimeException unreadable) {
            long day = 86_400_000L;
            return Instant.ofEpochMilli((Math.floorDiv(nowMillis, day) + 1) * day);
        }
    }

    /**
     * The {@code freePlan} object of a reply, or null when the reply has none or one this version cannot read: a
     * count that is no whole number from 0 up, or a flag that is no boolean.
     */
    static FreePlan read(JsonObject reply) {
        JsonElement value = reply.get("freePlan");
        if (value == null || !value.isJsonObject()) {
            return null;
        }
        JsonObject plan = value.getAsJsonObject();
        int dailyChecks = count(plan, "dailyChecks");
        int checksToday = count(plan, "checksToday");
        int dailyImages = count(plan, "dailyImages");
        int imagesToday = count(plan, "imagesToday");
        Boolean claimed = flag(plan, "claimed");
        Boolean claimPending = flag(plan, "claimPending");
        if (dailyChecks < 0 || checksToday < 0 || dailyImages < 0 || imagesToday < 0 || claimed == null
                || claimPending == null) {
            return null;
        }
        JsonElement resets = plan.get("resetsAt");
        String resetsAt = resets != null && resets.isJsonPrimitive() && resets.getAsJsonPrimitive().isString()
                ? resets.getAsString() : "";
        return new FreePlan(dailyChecks, checksToday, dailyImages, imagesToday, resetsAt, claimed,
                !claimed && claimPending);
    }

    /** A whole number from 0 up, or -1. */
    private static int count(JsonObject plan, String key) {
        JsonElement value = plan.get(key);
        try {
            if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
                int number = value.getAsBigDecimal().intValueExact();
                return number >= 0 ? number : -1;
            }
        } catch (ArithmeticException | NumberFormatException ignored) {
        }
        return -1;
    }

    private static Boolean flag(JsonObject plan, String key) {
        JsonElement value = plan.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()
                ? value.getAsBoolean() : null;
    }
}
