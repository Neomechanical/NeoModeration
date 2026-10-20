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
    /** The reason an action gets when config.yml gives none; players read it in their own language. */
    public static final String DEFAULT_REASON = "Inappropriate chat message";

    /** The same action with another reason, such as the default one in a player's language. */
    public ModerationAction withReason(String newReason) {
        return new ModerationAction(type, command, role, durationSeconds, newReason);
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
        durationSeconds = Math.min(durationSeconds, InputLimits.MAX_MUTE_SECONDS);
        return Optional.of(new ModerationAction(
                actionType,
                command,
                stringValue(raw.get("role")),
                durationSeconds,
                stringValueOrDefault(raw.get("reason"), DEFAULT_REASON)
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
