package com.neomechanical.neomoderation.config;



import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-category cloud sensitivity. Each category maps to a score threshold sent to
 * the platform: {@code true} keeps the historical default (0.7), a number between
 * 0.05 and 0.99 is a custom threshold (lower = stricter), and {@code false} (or
 * any value clamping to 1.0) disables the category.
 */
public record ModerationCategorySettings(Map<String, Double> thresholds) {
    public static final double DEFAULT_THRESHOLD = 0.7D;
    public static final double DISABLED_THRESHOLD = 1.0D;
    private static final double MIN_THRESHOLD = 0.05D;

    private static final String[] CATEGORY_KEYS = {
            "sexual",
            "hate",
            "harassment",
            "violence",
            "scam",
            "spam",
            "illicit",
            "selfHarm"
    };

    public static ModerationCategorySettings from(ConfigView config) {
        Map<String, Double> thresholds = new LinkedHashMap<>();
        for (String key : CATEGORY_KEYS) {
            thresholds.put(key, parseThreshold(config.get("moderation.categories." + key)));
        }
        return new ModerationCategorySettings(Map.copyOf(thresholds));
    }

    /** How the moderation API names a category: config.yml's "selfHarm" is "self-harm" there. */
    public static String platformName(String category) {
        return "selfHarm".equals(category) ? "self-harm" : category;
    }

    public static String[] categoryKeys() {
        return CATEGORY_KEYS.clone();
    }

    public double threshold(String key) {
        return thresholds.getOrDefault(key, DISABLED_THRESHOLD);
    }

    public boolean isEnabled(String key) {
        return threshold(key) < DISABLED_THRESHOLD;
    }

    public long enabledCount() {
        return thresholds.values().stream().filter(value -> value < DISABLED_THRESHOLD).count();
    }

    private static double parseThreshold(Object raw) {
        if (raw instanceof Boolean flag) {
            return flag ? DEFAULT_THRESHOLD : DISABLED_THRESHOLD;
        }
        if (raw instanceof Number number) {
            return clamp(number.doubleValue());
        }
        if (raw instanceof String text) {
            try {
                return clamp(Double.parseDouble(text.trim()));
            } catch (NumberFormatException ignored) {
                return DEFAULT_THRESHOLD;
            }
        }
        return DEFAULT_THRESHOLD;
    }

    private static double clamp(double value) {
        return Math.max(MIN_THRESHOLD, Math.min(DISABLED_THRESHOLD, value));
    }
}
