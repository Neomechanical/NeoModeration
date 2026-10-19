package com.neomechanical.neomoderation.moderation;

import com.neomechanical.neomoderation.config.OfflineModerationSettings;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Compiles and caches {@link OfflineModerationSettings} into run-collapsed token
 * matchers and allowlist masks for {@link OfflineModerationEngine}.
 */
final class OfflineRuleCompiler {

    /**
     * A banned word ready to match. {@code spaced} when the rule itself was
     * written with a space ("ass hole"), which reads it across every space.
     * {@code ownList} when it is on the server's own list rather than only in a
     * language pack: only English and the server's language can show it was two
     * everyday words, never a language a player picks for their game.
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
            Set<String> words = new java.util.HashSet<>();
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

    /**
     * The first match of {@code word} at or after {@code from} that is not
     * everyday words of one language (see {@link EverydayWords}), or {@code null}.
     * A word written with a space is read across every space.
     */
    static int[] findWord(
            TextNormalizer.Normalized text,
            CompiledWord word,
            boolean[] masked,
            EverydayWords everyday,
            int from
    ) {
        int[] match;
        while ((match = findMatch(text, word, masked, from)) != null) {
            // A word written with a space is read across every space, unless a French elision inside
            // the match shows French words: "j'ai pas pu, t'es où ?" is no "pu tes".
            boolean readAsWords = (!word.spaced() || everyday.throughElision(match[0], match[1]))
                    && everyday.innocent(match[0], match[1], !word.ownList());
            if (!readAsWords) {
                return match;
            }
            from = match[0] + 1;
        }
        return null;
    }

    /**
     * Finds the first match of {@code word} at or after normalised index
     * {@code from}, or {@code null}. A match must start where a token starts and
     * finish where one finishes; between those points separators are ignored,
     * apostrophes included ("f'uck"), but a match never starts at an English
     * contraction's ending ("let's hit" is not "s hit").
     */
    static int[] findMatch(
            TextNormalizer.Normalized text,
            CompiledWord word,
            boolean[] masked,
            int from
    ) {
        char[] chars = text.chars();
        boolean[] tokenStart = text.tokenStart();
        boolean[] tokenEnd = text.tokenEnd();
        boolean[] joinBlocked = text.joinBlocked();
        char[] runChars = word.runChars();
        int[] runLengths = word.runLengths();
        int length = chars.length;

        for (int start = from; start < length; start++) {
            if (!tokenStart[start] || masked[start] || joinBlocked[start]) {
                continue;
            }
            int position = start;
            int firstRunEnd = start + 1;
            boolean matched = true;
            for (int run = 0; run < runChars.length; run++) {
                if (position >= length || chars[position] != runChars[run]) {
                    matched = false;
                    break;
                }
                int runLength = 0;
                while (position + runLength < length && chars[position + runLength] == runChars[run]) {
                    if (masked[position + runLength]) {
                        break;
                    }
                    // The last letter ends the match at the first word end it can:
                    // "put a 4" is "put a" and a number, and "fuck kids" ends at "fuck".
                    if (run == runChars.length - 1
                            && runLength >= runLengths[run]
                            && tokenEnd[position + runLength - 1]) {
                        break;
                    }
                    runLength++;
                }
                if (run == 0) {
                    firstRunEnd = position + runLength;
                }
                if (runLength < runLengths[run]) {
                    matched = false;
                    break;
                }
                position += runLength;
            }
            if (matched && position > start && tokenEnd[position - 1]) {
                return new int[]{start, position};
            }
            start = Math.max(start, firstRunEnd - 1);
        }
        return null;
    }

    /**
     * Marks the characters of every allowed phrase so no banned word can match
     * inside one. Phrases match whole tokens in sequence.
     */
    static boolean[] maskAllowedPhrases(TextNormalizer.Normalized text, List<String[]> phrases) {
        boolean[] masked = new boolean[text.length()];
        if (phrases.isEmpty()) {
            return masked;
        }
        List<int[]> tokens = tokensOf(text);
        for (String[] phrase : phrases) {
            for (int t = 0; t + phrase.length <= tokens.size(); t++) {
                boolean allMatch = true;
                for (int p = 0; p < phrase.length; p++) {
                    if (!tokenEquals(text, tokens.get(t + p), phrase[p])) {
                        allMatch = false;
                        break;
                    }
                }
                if (allMatch) {
                    int begin = tokens.get(t)[0];
                    int end = tokens.get(t + phrase.length - 1)[1];
                    for (int i = begin; i < end; i++) {
                        masked[i] = true;
                    }
                }
            }
        }
        return masked;
    }

    /** Whether a match from {@code start} to {@code end} runs across a space. */
    static boolean crossesSpace(TextNormalizer.Normalized text, int start, int end) {
        for (int i = start + 1; i < end; i++) {
            if (text.tokenStart()[i]) {
                return true;
            }
        }
        return false;
    }

    /** Each token's start and end in the normalised text. */
    static List<int[]> tokensOf(TextNormalizer.Normalized text) {
        List<int[]> tokens = new ArrayList<>();
        boolean[] tokenStart = text.tokenStart();
        boolean[] tokenEnd = text.tokenEnd();
        int begin = -1;
        for (int i = 0; i < text.length(); i++) {
            if (tokenStart[i]) {
                begin = i;
            }
            if (tokenEnd[i] && begin >= 0) {
                tokens.add(new int[]{begin, i + 1});
                begin = -1;
            }
        }
        return tokens;
    }

    private static boolean tokenEquals(TextNormalizer.Normalized text, int[] token, String expected) {
        if (token[1] - token[0] != expected.length()) {
            return false;
        }
        char[] chars = text.chars();
        for (int i = 0; i < expected.length(); i++) {
            if (chars[token[0] + i] != expected.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static CompiledRules compile(OfflineModerationSettings settings) {
        List<String> bannedWords = settings.effectiveWords();
        Set<String> ownList = new java.util.HashSet<>();
        for (String word : settings.bannedWords()) {
            ownList.add(lower(word).trim());
        }
        List<CompiledWord> words = new ArrayList<>(bannedWords.size());
        for (String bannedWord : bannedWords) {
            CompiledWord compiled = compileWord(bannedWord, settings.normalizeLeetspeak(),
                    ownList.contains(lower(bannedWord).trim()));
            if (compiled != null) {
                words.add(compiled);
            }
        }
        List<String[]> allowedPhrases = tokenizeAll(allowedWithJoinedForms(settings, words), settings.normalizeLeetspeak());
        List<CompiledUrl> urls = new ArrayList<>(settings.bannedUrls().size());
        for (String bannedUrl : settings.bannedUrls()) {
            String normalized = lower(bannedUrl).trim();
            if (!normalized.isEmpty()) {
                urls.add(new CompiledUrl(bannedUrl, normalized));
            }
        }
        List<String> allowedUrls = new ArrayList<>(settings.allowedUrls().size());
        for (String allowedUrl : settings.allowedUrls()) {
            String normalized = lower(allowedUrl).trim();
            if (!normalized.isEmpty()) {
                allowedUrls.add(normalized);
            }
        }
        return new CompiledRules(
                settings,
                List.copyOf(words),
                allowedPhrases,
                List.copyOf(urls),
                List.copyOf(allowedUrls),
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
        Set<String> spacedWords = new java.util.HashSet<>();
        for (CompiledWord word : words) {
            if (word.spaced()) {
                spacedWords.add(lower(word.original()).trim());
            }
        }
        List<String> allowed = new ArrayList<>(settings.effectiveAllowedWords());
        for (String phrase : settings.effectiveAllowedWords()) {
            if (spacedWords.contains(lower(phrase).trim())) {
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
        boolean spaced = crossesSpace(normalized, 0, letters.length);
        return new CompiledWord(bannedWord, chars, lengths, normalized.collapsed(), spaced, ownList);
    }

    private static String[] tokenizeRule(String rule, boolean normalizeLeetspeak) {
        TextNormalizer.Normalized normalized = TextNormalizer.normalize(rule, normalizeLeetspeak);
        List<String> tokens = new ArrayList<>();
        char[] chars = normalized.chars();
        int begin = -1;
        for (int i = 0; i < chars.length; i++) {
            if (normalized.tokenStart()[i]) {
                begin = i;
            }
            if (normalized.tokenEnd()[i] && begin >= 0) {
                tokens.add(new String(chars, begin, i + 1 - begin));
                begin = -1;
            }
        }
        return tokens.toArray(new String[0]);
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
