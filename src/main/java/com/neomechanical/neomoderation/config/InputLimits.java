package com.neomechanical.neomoderation.config;

/**
 * Keeps in-game command payloads within Minecraft RCON / chat-safe sizes.
 */
public final class InputLimits {
    public static final int MAX_API_KEY_LENGTH = 256;
    public static final int MAX_RULE_VALUE_LENGTH = 128;
    /** Hard cap for mute duration: 30 days. */
    public static final int MAX_MUTE_SECONDS = 30 * 24 * 60 * 60;

    private InputLimits() {
    }

    public static boolean isApiKeyLengthValid(String value) {
        return value != null && !value.isBlank() && value.length() <= MAX_API_KEY_LENGTH;
    }

    public static boolean isRuleValueLengthValid(String value) {
        return value != null && !value.isBlank() && value.length() <= MAX_RULE_VALUE_LENGTH;
    }
}
