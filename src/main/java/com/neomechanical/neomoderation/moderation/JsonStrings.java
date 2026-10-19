package com.neomechanical.neomoderation.moderation;

/**
 * The one JSON string escaper for every request body the plugin builds by hand
 * (cloud events, trial claims, Discord webhooks). Escapes quotes, backslashes
 * and every control character, so no value can break out of its JSON string.
 */
public final class JsonStrings {
    private JsonStrings() {
    }

    /** {@code value} escaped for use inside a JSON string literal; {@code ""} for null. */
    public static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }
}
