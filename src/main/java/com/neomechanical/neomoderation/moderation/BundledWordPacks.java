package com.neomechanical.neomoderation.moderation;

import com.neomechanical.neomoderation.messages.MessageService;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Starter lists of swear words and slurs in languages other than English, kept
 * in {@code wordpacks/<code>.txt}. English lives in config.yml's own list.
 *
 * <p>A server picks packs with {@code moderation.offline.languagePacks}:
 * {@code auto} adds the pack for the language of {@code locale}, a code such as
 * {@code es} adds that pack, and {@code all} adds every one.</p>
 */
public final class BundledWordPacks {

    /** Every bundled pack, by the language code a server names it with. */
    public static final List<String> CODES = List.of("es", "fr", "de", "pt", "tr");
    public static final String AUTO = "auto";
    public static final String ALL = "all";

    private static final Map<String, List<String>> LOADED = new ConcurrentHashMap<>();

    private BundledWordPacks() {
    }

    /** The words of one pack, or an empty list for a code with no pack. */
    public static List<String> words(String code) {
        String normalized = code == null ? "" : code.trim().toLowerCase(Locale.ROOT);
        if (!CODES.contains(normalized)) {
            return List.of();
        }
        return LOADED.computeIfAbsent(normalized, BundledWordPacks::load);
    }

    /**
     * The packs a server's settings select, in a stable order and without
     * duplicates. {@code auto} only counts when {@code autoAllowed}: an operator
     * who emptied the English list has switched word filtering off.
     */
    public static List<String> select(List<String> requested, String locale, boolean autoAllowed) {
        Set<String> selected = new LinkedHashSet<>();
        for (String raw : requested == null ? List.<String>of() : requested) {
            String entry = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
            if (ALL.equals(entry)) {
                selected.addAll(CODES);
            } else if (AUTO.equals(entry)) {
                String language = languageOf(locale);
                if (autoAllowed && CODES.contains(language)) {
                    selected.add(language);
                }
            } else if (CODES.contains(entry)) {
                selected.add(entry);
            }
        }
        return List.copyOf(selected);
    }

    /** All words of the given packs, de-duplicated, in pack order. */
    public static List<String> wordsOf(List<String> packs) {
        Set<String> merged = new LinkedHashSet<>();
        for (String pack : packs) {
            merged.addAll(words(pack));
        }
        return List.copyOf(merged);
    }

    /** The two-letter language of a locale code such as {@code pt-br} or {@code de_AT}. */
    static String languageOf(String locale) {
        String resolved = MessageService.resolveLocaleCode(locale);
        int separator = resolved.indexOf('_');
        return (separator > 0 ? resolved.substring(0, separator) : resolved).toLowerCase(Locale.ROOT);
    }

    private static List<String> load(String code) {
        String path = "/wordpacks/" + code + ".txt";
        try (InputStream stream = BundledWordPacks.class.getResourceAsStream(path)) {
            if (stream == null) {
                return List.of();
            }
            List<String> words = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String word = line.trim();
                    if (!word.isEmpty() && !word.startsWith("#")) {
                        words.add(word);
                    }
                }
            }
            return List.copyOf(words);
        } catch (IOException e) {
            throw new UncheckedIOException("Bundled word pack " + path + " is unreadable", e);
        }
    }
}
