package com.neomechanical.neomoderation.moderation;

import com.neomechanical.neomoderation.messages.MessageService;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
 * {@code es} adds that pack, and {@code all} adds every one. A pack line starting
 * with {@code +} is a phrase the pack allows, and {@code wordpacks/everyday.txt}
 * holds phrases every server allows.</p>
 *
 * <p>Two more lists shape how words are read: {@code wordpacks/common-words.txt},
 * everyday words a banned word is never read across ("put a" is not "puta"),
 * and {@code wordpacks/languages.txt}, words one language uses every day that
 * another language's list bans ("retard" in French), allowed for that
 * language's speakers.</p>
 */
public final class BundledWordPacks {

    /** Every bundled pack, by the language code a server names it with. */
    public static final List<String> CODES = List.of("es", "fr", "de", "pt", "tr");
    public static final String AUTO = "auto";
    public static final String ALL = "all";
    private static final String EVERYDAY = "everyday";

    /** A pack's banned words and the phrases it allows. */
    private record Pack(List<String> words, List<String> allowed) {
    }

    private static final Map<String, Pack> LOADED = new ConcurrentHashMap<>();
    private static volatile Set<String> commonWords;
    private static volatile Map<String, List<String>> languageWords;

    private BundledWordPacks() {
    }

    /** The words of one pack, or an empty list for a code with no pack. */
    public static List<String> words(String code) {
        return pack(code).words();
    }

    /** The phrases one pack allows while it is on. */
    public static List<String> allowed(String code) {
        return pack(code).allowed();
    }

    /** Phrases every server allows, whatever its packs: see {@code wordpacks/everyday.txt}. */
    public static List<String> everyday() {
        return LOADED.computeIfAbsent(EVERYDAY, BundledWordPacks::load).allowed();
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

    /** The everyday phrases plus the phrases the given packs allow. */
    public static List<String> allowedOf(List<String> packs) {
        Set<String> merged = new LinkedHashSet<>(everyday());
        for (String pack : packs) {
            merged.addAll(allowed(pack));
        }
        return List.copyOf(merged);
    }

    /**
     * Everyday words, normalised the way chat is: a banned word is never read
     * across a space between two of them. See {@code wordpacks/common-words.txt}.
     */
    static Set<String> commonWords() {
        Set<String> words = commonWords;
        if (words == null) {
            Set<String> loaded = new HashSet<>();
            for (String line : lines("common-words")) {
                for (String word : line.split("\\s+")) {
                    String normalized = new String(TextNormalizer.normalize(word, false).chars());
                    if (!normalized.isEmpty()) {
                        loaded.add(normalized);
                    }
                }
            }
            words = Set.copyOf(loaded);
            commonWords = words;
        }
        return words;
    }

    /**
     * Words the speakers of {@code locale}'s language use every day that another
     * language's list bans, such as French "retard". {@code locale} is a game or
     * server locale such as {@code pt_PT} or {@code sv_se}; a list named for a
     * language ({@code sv}) covers all its countries, one named for a country
     * ({@code pt_pt}) only that one. Empty for {@code null}.
     */
    public static List<String> languageWords(String locale) {
        if (locale == null || locale.isBlank()) {
            return List.of();
        }
        Map<String, List<String>> lists = languageWords;
        if (lists == null) {
            Map<String, List<String>> loaded = new LinkedHashMap<>();
            for (String line : lines("languages")) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    loaded.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                            List.of(line.substring(colon + 1).trim().split("\\s+")));
                }
            }
            lists = Map.copyOf(loaded);
            languageWords = lists;
        }
        String code = locale.trim().replace('-', '_').toLowerCase(Locale.ROOT);
        int separator = code.indexOf('_');
        String language = separator > 0 ? code.substring(0, separator) : code;
        List<String> words = new ArrayList<>(lists.getOrDefault(language, List.of()));
        if (!code.equals(language)) {
            words.addAll(lists.getOrDefault(code, List.of()));
        }
        return List.copyOf(words);
    }

    /** The two-letter language of a locale code such as {@code pt-br} or {@code de_AT}. */
    public static String languageOf(String locale) {
        String resolved = MessageService.resolveLocaleCode(locale);
        int separator = resolved.indexOf('_');
        return (separator > 0 ? resolved.substring(0, separator) : resolved).toLowerCase(Locale.ROOT);
    }

    private static Pack pack(String code) {
        String normalized = code == null ? "" : code.trim().toLowerCase(Locale.ROOT);
        return CODES.contains(normalized)
                ? LOADED.computeIfAbsent(normalized, BundledWordPacks::load)
                : new Pack(List.of(), List.of());
    }

    /** Reads {@code wordpacks/<name>.txt}: a word per line, {@code +} before an allowed phrase, {@code #} comments. */
    private static Pack load(String name) {
        List<String> words = new ArrayList<>();
        List<String> allowed = new ArrayList<>();
        for (String entry : lines(name)) {
            if (entry.startsWith("+")) {
                allowed.add(entry.substring(1).trim());
            } else if (EVERYDAY.equals(name)) {
                allowed.add(entry);
            } else {
                words.add(entry);
            }
        }
        return new Pack(List.copyOf(words), List.copyOf(allowed));
    }

    /** The lines of {@code wordpacks/<name>.txt}, trimmed, without blank lines and {@code #} comments. */
    private static List<String> lines(String name) {
        String path = "/wordpacks/" + name + ".txt";
        try (InputStream stream = BundledWordPacks.class.getResourceAsStream(path)) {
            if (stream == null) {
                return List.of();
            }
            List<String> lines = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String entry = line.trim();
                    if (!entry.isEmpty() && !entry.startsWith("#")) {
                        lines.add(entry);
                    }
                }
            }
            return lines;
        } catch (IOException e) {
            throw new UncheckedIOException("Bundled word list " + path + " is unreadable", e);
        }
    }
}
