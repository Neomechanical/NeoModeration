package com.neomechanical.neomoderation.config;

import java.util.Map;
import java.util.Optional;

public record ModerationAction(
        ModerationActionType type,
        String command,
        String role,
        int durationSeconds,
        String reason
) {
    /** Human summary of a single action, shared across commands and alerts. */
    public String describe() {
        return switch (type) {
            case CLEAR_CHAT -> "clear";
            case MUTE -> "mute " + com.neomechanical.neomoderation.commands.DurationParser.format(durationSeconds);
            case KICK -> "kick";
            case BAN -> "ban";
            default -> type.name().toLowerCase(java.util.Locale.ROOT);
        };
    }

    /** Human summary of an action list, shared by status, alerts, test, and doctor. */
    public static String describe(java.util.List<ModerationAction> actions) {
        if (actions.isEmpty()) {
            return "block only";
        }
        java.util.List<String> parts = new java.util.ArrayList<>(actions.size());
        for (ModerationAction action : actions) {
            parts.add(action.describe());
        }
        return String.join(", ", parts);
    }

    public static Optional<ModerationAction> tryFrom(Map<?, ?> raw) {
        Optional<ModerationActionType> type = ModerationActionType.parse(stringValue(raw.get("type")));
        if (type.isEmpty()) {
            return Optional.empty();
        }
        ModerationActionType actionType = type.get();
        String command = stringValue(raw.get("command"));
        if (actionType == ModerationActionType.COMMAND && command.isBlank()) {
            return Optional.empty();
        }
        int durationSeconds = intValue(raw.get("durationSeconds"), 300);
        if (durationSeconds <= 0) {
            durationSeconds = 300;
        }
        durationSeconds = Math.min(durationSeconds, com.neomechanical.neomoderation.commands.InputLimits.MAX_MUTE_SECONDS);
        return Optional.of(new ModerationAction(
                actionType,
                command,
                stringValue(raw.get("role")),
                durationSeconds,
                stringValueOrDefault(raw.get("reason"), "Inappropriate chat message")
        ));
    }

    public static ModerationAction from(Map<?, ?> raw) {
        return tryFrom(raw).orElseThrow(() -> new IllegalArgumentException("Invalid moderation action: " + raw));
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String stringValueOrDefault(Object value, String fallback) {
        String text = stringValue(value);
        return text.isEmpty() ? fallback : text;
    }

    private static int intValue(Object value, int fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
