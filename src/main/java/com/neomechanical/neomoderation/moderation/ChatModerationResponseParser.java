package com.neomechanical.neomoderation.moderation;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.neomechanical.neomoderation.config.ModerationCategorySettings;

public final class ChatModerationResponseParser {

    private ChatModerationResponseParser() {
    }

    public static ModerationApiResult parseResult(String body, ModerationCategorySettings settings) {
        try {
            JsonObject response = CloudResponseJson.parseObject(body);
            if (response == null) {
                return ModerationApiResult.transientTransport();
            }
            JsonObject decision = object(response, "decision");
            JsonObject categories = object(response, "categories");
            JsonObject decisionCategories = decision == null ? null : object(decision, "categories");
            JsonObject scores = object(response, "category_scores");
            boolean hasCategorySignal = false;
            String matched = null;
            for (String category : ModerationCategorySettings.categoryKeys()) {
                String key = ModerationCategorySettings.platformName(category);
                JsonElement value = scores != null ? scores.get(key) : null;
                if (value == null && categories != null) {
                    value = categories.get(key);
                }
                if (value == null && decisionCategories != null) {
                    value = decisionCategories.get(key);
                }
                if (value == null) {
                    value = response.get(key); // Legacy category-only responses.
                }
                if (value == null) {
                    continue;
                }
                if (!value.isJsonPrimitive()) {
                    return ModerationApiResult.transientTransport();
                }
                JsonPrimitive signal = value.getAsJsonPrimitive();
                boolean flagged;
                if (signal.isNumber()) {
                    double score = signal.getAsDouble();
                    if (!Double.isFinite(score) || score < 0 || score > 1) {
                        return ModerationApiResult.transientTransport();
                    }
                    flagged = score >= settings.threshold(category);
                } else if (signal.isBoolean()) {
                    flagged = signal.getAsBoolean();
                } else {
                    return ModerationApiResult.transientTransport();
                }
                hasCategorySignal = true;
                if (matched == null && settings.isEnabled(category) && flagged) {
                    matched = category;
                }
            }
            if (hasCategorySignal) {
                return matched == null ? ModerationApiResult.clear() : ModerationApiResult.flagged(matched);
            }
            JsonElement flagged = response.get("flagged");
            if (flagged != null && flagged.isJsonPrimitive() && flagged.getAsJsonPrimitive().isBoolean()) {
                return flagged.getAsBoolean() ? ModerationApiResult.flagged("") : ModerationApiResult.clear();
            }
            JsonElement status = decision != null ? decision.get("status") : response.get("status");
            if (status != null && status.isJsonPrimitive() && status.getAsJsonPrimitive().isString()) {
                return switch (status.getAsString()) {
                    case "blocked" -> ModerationApiResult.flagged("");
                    case "clean", "review", "allowed" -> ModerationApiResult.clear();
                    default -> ModerationApiResult.transientTransport();
                };
            }
            return ModerationApiResult.transientTransport();
        } catch (RuntimeException error) {
            return ModerationApiResult.transientTransport();
        }
    }

    private static JsonObject object(JsonObject parent, String key) {
        JsonElement value = parent.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }
}
