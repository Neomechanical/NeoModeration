package com.neomechanical.neomoderation.moderation;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;

/**
 * Parsed view of the Neomechanical workspace usage endpoint, decoded through
 * the shared strict JSON parser ({@link CloudResponseJson}).
 */
public record UsageSummary(
        String workspace,
        String tier,
        long requestsPerMinuteLimit,
        long creditsRemaining,
        long requestsToday,
        long requestsLast7Days,
        long nsfwImagesRemaining,
        long nsfwImagesCap,
        long nsfwTextUnitsRemaining,
        long nsfwTextUnitsCap,
        long nsfwVideosRemaining,
        long nsfwVideosCap
) {
    public static UsageSummary parse(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("empty usage response");
        }
        JsonObject object;
        try {
            object = CloudResponseJson.parseObject(json);
        } catch (JsonSyntaxException | IllegalStateException e) {
            throw new IllegalArgumentException("invalid usage response", e);
        }
        if (object == null) {
            throw new IllegalArgumentException("empty usage response");
        }
        return new UsageSummary(
                string(object, "workspace", "Workspace"),
                string(object, "tier", "free"),
                number(object, "requestsPerMinuteLimit"),
                number(object, "creditsRemaining"),
                number(object, "requestsToday"),
                number(object, "requestsLast7Days"),
                number(object, "nsfwImagesRemaining"),
                number(object, "nsfwImagesCap"),
                number(object, "nsfwTextUnitsRemaining"),
                number(object, "nsfwTextUnitsCap"),
                number(object, "nsfwVideosRemaining"),
                number(object, "nsfwVideosCap")
        );
    }

    private static long number(JsonObject object, String field) {
        JsonElement element = object.get(field);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            return 0L;
        }
        try {
            return element.getAsLong();
        } catch (NumberFormatException | UnsupportedOperationException ignored) {
            return 0L;
        }
    }

    private static String string(JsonObject object, String field, String fallback) {
        JsonElement element = object.get(field);
        if (element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            String value = element.getAsString();
            if (!value.isBlank()) {
                return value;
            }
        }
        return fallback;
    }
}
