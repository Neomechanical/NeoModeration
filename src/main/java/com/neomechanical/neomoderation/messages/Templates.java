package com.neomechanical.neomoderation.messages;

import org.bukkit.ChatColor;

import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fills a locale template. Colour tokens ({@code {#brand}}) and {@code &} codes
 * belong to the template and are applied first; placeholders ({@code {player}})
 * are filled last, in one pass and literally. Text a player wrote therefore can
 * neither carry colour codes ("&k" in a chat message stays "&k") nor fill
 * another placeholder ("{prefix}" in a message stays "{prefix}").
 */
final class Templates {
    private static final Pattern COLOR_TOKEN = Pattern.compile("\\{#([a-zA-Z0-9_-]+)}");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-zA-Z0-9_-]+)}");
    private static final Pattern HEX_SEQUENCE = Pattern.compile("&x((?:&[0-9a-fA-F]){6})");

    private Templates() {
    }

    static String render(String template, Map<String, String> values, Function<String, String> colors) {
        if (template == null) {
            return "";
        }
        Matcher tokens = COLOR_TOKEN.matcher(template);
        StringBuilder colored = new StringBuilder(template.length() + 32);
        while (tokens.find()) {
            String code = colors.apply(tokens.group(1));
            tokens.appendReplacement(colored, Matcher.quoteReplacement(code == null ? "" : code));
        }
        tokens.appendTail(colored);
        String translated = translateCodes(colored.toString());
        if (values.isEmpty() || translated.indexOf('{') < 0) {
            return translated;
        }
        Matcher placeholders = PLACEHOLDER.matcher(translated);
        StringBuilder out = new StringBuilder(translated.length() + 64);
        while (placeholders.find()) {
            String value = values.get(placeholders.group(1));
            placeholders.appendReplacement(out, Matcher.quoteReplacement(value == null ? placeholders.group() : value));
        }
        placeholders.appendTail(out);
        return out.toString();
    }

    /**
     * Translates {@code &} codes, including {@code &x&R&R&G&G&B&B} hex colours,
     * which the 1.8 compile API cannot parse itself.
     */
    static String translateCodes(String legacy) {
        if (legacy == null || legacy.indexOf('&') < 0) {
            return legacy == null ? "" : legacy;
        }
        String hexed = HEX_SEQUENCE.matcher(legacy)
                .replaceAll(match -> Matcher.quoteReplacement("§x" + match.group(1).replace('&', '§')));
        return ChatColor.translateAlternateColorCodes('&', hexed);
    }
}
