package com.neomechanical.neomoderation.moderation;

import com.neomechanical.neomoderation.config.OfflineModerationSettings;

import java.util.ArrayList;
import java.util.List;

/**
 * Compiles and caches {@link OfflineModerationSettings} into run-collapsed token
 * matchers and allowlist masks for {@link OfflineModerationEngine}.
 */
final class OfflineRuleCompiler {

    record CompiledWord(String original, char[] runChars, int[] runLengths, String collapsed) {
    }

    record CompiledUrl(String original, String normalized) {
    }

    record CompiledRules(
            OfflineModerationSettings source,
            List<CompiledWord> words,
            List<String[]> allowedPhrases,
            List<CompiledUrl> urls,
            List<String> allowedUrls
    ) {
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
     * Finds the first match of {@code word} at or after normalised index
     * {@code from}, or {@code null}. A match must start where a token starts and
     * finish where one finishes; between those points separators are ignored,
     * except the apostrophe inside a contraction, which a match never crosses.
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
                if (position >= length || chars[position] != runChars[run]
                        || (position > start && joinBlocked[position])) {
                    matched = false;
                    break;
                }
                int runLength = 0;
                while (position + runLength < length && chars[position + runLength] == runChars[run]) {
                    if (masked[position + runLength] || (runLength > 0 && joinBlocked[position + runLength])) {
                        break;
                    }
                    if (run == runChars.length - 1
                            && runLength >= runLengths[run]
                            && tokenEnd[position + runLength - 1]
                            && tokenStart[position + runLength]
                            && !tokenEnd[position + runLength]) {
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

    private static List<int[]> tokensOf(TextNormalizer.Normalized text) {
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
        List<CompiledWord> words = new ArrayList<>(bannedWords.size());
        for (String bannedWord : bannedWords) {
            CompiledWord compiled = compileWord(bannedWord, settings.normalizeLeetspeak());
            if (compiled != null) {
                words.add(compiled);
            }
        }
        List<String> allowedWords = settings.effectiveAllowedWords();
        List<String[]> allowedPhrases = new ArrayList<>(allowedWords.size());
        for (String allowedWord : allowedWords) {
            String[] tokens = tokenizeRule(allowedWord, settings.normalizeLeetspeak());
            if (tokens.length > 0) {
                allowedPhrases.add(tokens);
            }
        }
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
                List.copyOf(allowedPhrases),
                List.copyOf(urls),
                List.copyOf(allowedUrls)
        );
    }

    private static CompiledWord compileWord(String bannedWord, boolean normalizeLeetspeak) {
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
        return new CompiledWord(bannedWord, chars, lengths, normalized.collapsed());
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
