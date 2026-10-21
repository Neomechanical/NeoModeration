package com.neomechanical.neomoderation.moderation;

import com.neomechanical.neomoderation.config.OfflineModerationSettings;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Compiles and caches {@link OfflineModerationSettings} into the run-collapsed
 * words, allowed phrases and URLs that {@link OfflineModerationEngine} looks for
 * with {@link RuleMatcher}.
 */
final class OfflineRuleCompiler {

    /**
     * A banned word ready to match. {@code spaced} when the rule itself was
     * written with a space ("ass hole"), which reads it across every space.
     * {@code ownList} when it is on the server's own list rather than only in a
     * language pack: only English and the server's language can show it was two
     * everyday words, never a language a player picks for their game (a number
     * after a trusted player's word stays a number, though: "pak 1").
     */
    record CompiledWord(String original, char[] runChars, int[] runLengths, String collapsed, boolean spaced,
                        boolean ownList) {
    }

    /**
     * The everyday words a match may be read as instead, one set per language:
     * English first, then the server's and the speaker's languages, each once.
     * {@code speakerOnly} has a bit for each language here only because the
     * speaker plays in it, and {@code elides} one for each that writes l', d',
     * j', t' (French, Catalan, Italian).
     */
    record Scope(List<Set<String>> languages, int speakerOnly, int elides) {
    }

    /** Languages whose words lose a vowel before an apostrophe: "j'ai", "l'eau", "t'es". */
    private static final Set<String> ELIDING_LANGUAGES = Set.of("fr", "ca", "it");

    record CompiledUrl(String original, String normalized) {
    }

    /**
     * The compiled rules. {@code speakerPhrases} caches, per game language, the
     * words that language's speakers may use although another language bans them;
     * {@code everydayScopes} caches, per speaker's language, the everyday words a
     * match may be read as instead (see {@link EverydayWords}).
     */
    record CompiledRules(
            OfflineModerationSettings source,
            List<CompiledWord> words,
            List<String[]> allowedPhrases,
            List<CompiledUrl> urls,
            List<String> allowedUrls,
            Map<String, List<String[]>> speakerPhrases,
            Map<String, Scope> everydayScopes
    ) {
        /** Every word of {@link #speakerPhrases}, normalised. */
        Set<String> speakerWords(String language) {
            Set<String> words = new HashSet<>();
            for (String[] phrase : speakerPhrases(language)) {
                words.addAll(List.of(phrase));
            }
            return words;
        }

        /** The phrases a speaker of {@code language} may use besides everyone's; empty for {@code null}. */
        List<String[]> speakerPhrases(String language) {
            if (language == null || language.isBlank()) {
                return List.of();
            }
            return speakerPhrases.computeIfAbsent(language.toLowerCase(Locale.ROOT), code ->
                    tokenizeAll(BundledWordPacks.languageWords(code), source.normalizeLeetspeak()));
        }

        /**
         * The everyday words of each language whose words are read as words for
         * {@code speaker}: English, the server's locale and the speaker's game
         * language, each once.
         */
        Scope everydayScope(String speaker) {
            return everydayScopes.computeIfAbsent(BundledWordPacks.language(speaker), language -> {
                Set<String> codes = new LinkedHashSet<>(List.of(
                        BundledWordPacks.ENGLISH, BundledWordPacks.language(source.locale())));
                boolean speakerAdds = codes.add(language);
                List<Set<String>> sets = new ArrayList<>();
                int speakerOnly = 0;
                int elides = 0;
                for (String code : codes) {
                    Set<String> words = BundledWordPacks.everydayWords(code);
                    if (!words.isEmpty()) {
                        if (speakerAdds && code.equals(language)) {
                            speakerOnly |= 1 << sets.size();
                        }
                        if (ELIDING_LANGUAGES.contains(code)) {
                            elides |= 1 << sets.size();
                        }
                        sets.add(words);
                    }
                }
                return new Scope(List.copyOf(sets), speakerOnly, elides);
            });
        }
    }

    private static volatile CompiledRules cachedRules;

    private OfflineRuleCompiler() {
    }

    static CompiledRules rulesFor(OfflineModerationSettings settings) {
        CompiledRules rules = cachedRules;
        if (rules == null || !rules.source().equals(settings)) {
            rules = compile(settings);
            cachedRules = rules;
        }
        return rules;
    }

    private static CompiledRules compile(OfflineModerationSettings settings) {
        List<String> bannedWords = settings.effectiveWords();
        Set<String> ownList = new HashSet<>();
        for (String word : settings.bannedWords()) {
            ownList.add(ruleKey(word));
        }
        List<CompiledWord> words = new ArrayList<>(bannedWords.size());
        for (String bannedWord : bannedWords) {
            CompiledWord compiled = compileWord(bannedWord, settings.normalizeLeetspeak(),
                    ownList.contains(ruleKey(bannedWord)));
            if (compiled != null) {
                words.add(compiled);
            }
        }
        List<String[]> allowedPhrases = tokenizeAll(allowedWithJoinedForms(settings, words), settings.normalizeLeetspeak());
        List<CompiledUrl> urls = settings.bannedUrls().stream()
                .map(url -> new CompiledUrl(url, ruleKey(url)))
                .filter(url -> !url.normalized().isEmpty())
                .toList();
        List<String> allowedUrls = settings.allowedUrls().stream()
                .map(OfflineRuleCompiler::ruleKey)
                .filter(url -> !url.isEmpty())
                .toList();
        return new CompiledRules(
                settings,
                List.copyOf(words),
                allowedPhrases,
                urls,
                allowedUrls,
                new ConcurrentHashMap<>(),
                new ConcurrentHashMap<>()
        );
    }

    /**
     * The allowed phrases, plus the joined form of each that names a banned word
     * written with a space: allowing "pu tain" from a case lets "putain" through
     * too, as the admin meant. Other phrases are never joined, so allowing "put a"
     * leaves "puta" banned.
     */
    private static List<String> allowedWithJoinedForms(OfflineModerationSettings settings, List<CompiledWord> words) {
        Set<String> spacedWords = new HashSet<>();
        for (CompiledWord word : words) {
            if (word.spaced()) {
                spacedWords.add(ruleKey(word.original()));
            }
        }
        List<String> allowed = new ArrayList<>(settings.effectiveAllowedWords());
        for (String phrase : settings.effectiveAllowedWords()) {
            if (spacedWords.contains(ruleKey(phrase))) {
                allowed.add(phrase.replaceAll("\\s+", ""));
            }
        }
        return allowed;
    }

    private static List<String[]> tokenizeAll(List<String> phrases, boolean normalizeLeetspeak) {
        List<String[]> tokenized = new ArrayList<>(phrases.size());
        for (String phrase : phrases) {
            String[] tokens = tokenizeRule(phrase, normalizeLeetspeak);
            if (tokens.length > 0) {
                tokenized.add(tokens);
            }
        }
        return List.copyOf(tokenized);
    }

    private static CompiledWord compileWord(String bannedWord, boolean normalizeLeetspeak, boolean ownList) {
        TextNormalizer.Normalized normalized = TextNormalizer.normalize(bannedWord, normalizeLeetspeak);
        char[] letters = normalized.chars();
        if (letters.length == 0) {
            return null;
        }
        StringBuilder runChars = new StringBuilder();
        List<Integer> runLengths = new ArrayList<>();
        int index = 0;
        while (index < letters.length) {
            char current = letters[index];
            int run = 0;
            while (index + run < letters.length && letters[index + run] == current) {
                run++;
            }
            runChars.append(current);
            runLengths.add(run);
            index += run;
        }
        char[] chars = new char[runChars.length()];
        runChars.getChars(0, runChars.length(), chars, 0);
        int[] lengths = new int[runLengths.size()];
        for (int i = 0; i < lengths.length; i++) {
            lengths[i] = runLengths.get(i);
        }
        boolean spaced = RuleMatcher.crossesSpace(normalized, 0, letters.length);
        return new CompiledWord(bannedWord, chars, lengths, normalized.collapsed(), spaced, ownList);
    }

    private static String[] tokenizeRule(String rule, boolean normalizeLeetspeak) {
        TextNormalizer.Normalized normalized = TextNormalizer.normalize(rule, normalizeLeetspeak);
        return RuleMatcher.tokensOf(normalized).stream()
                .map(token -> new String(normalized.chars(), token[0], token[1] - token[0]))
                .toArray(String[]::new);
    }

    /** A word, phrase or URL of a rule as rules are compared: lower case, trimmed. */
    private static String ruleKey(String rule) {
        return lower(rule).trim();
    }

    /**
     * Lower case that keeps every character where it was, so a span found in the
     * result is the same span in the original: "İ" becomes "i", not two characters.
     */
    static String lower(String value) {
        if (value == null) {
            return "";
        }
        char[] chars = value.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            chars[i] = Character.toLowerCase(chars[i]);
        }
        return new String(chars);
    }
}
