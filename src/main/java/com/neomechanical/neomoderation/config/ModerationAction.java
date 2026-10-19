package com.neomechanical.neomoderation.config;

import com.neomechanical.neomoderation.commands.InputLimits;

import java.util.Map;
import java.util.Optional;

public record ModerationAction(
        ModerationActionType type,
        String command,
        String role,
        int durationSeconds,
        String reason
) {
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
        durationSeconds = Math.min(durationSeconds, InputLimits.MAX_MUTE_SECONDS);
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
