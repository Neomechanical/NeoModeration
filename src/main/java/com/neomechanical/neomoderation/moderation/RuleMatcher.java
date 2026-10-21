package com.neomechanical.neomoderation.moderation;

import java.util.ArrayList;
import java.util.List;

/**
 * Where a banned word compiled by {@link OfflineRuleCompiler} matches in a
 * normalised message: from a token's start to a token's end, across the
 * separators between, never inside an allowed phrase, and never where the
 * match reads as everyday words ({@link EverydayWords}).
 */
final class RuleMatcher {

    private RuleMatcher() {
    }

    /**
     * The first match of {@code word} at or after {@code from} that is not
     * everyday words of one language (see {@link EverydayWords}), nor a word and
     * the number after it ("pak 1 diamant"), or {@code null}. A word written with
     * a space is read across every space.
     */
    static int[] findWord(
            TextNormalizer.Normalized text,
            OfflineRuleCompiler.CompiledWord word,
            boolean[] masked,
            EverydayWords everyday,
            int from
    ) {
        int[] match;
        while ((match = findMatch(text, word, masked, from)) != null) {
            // A word written with a space is read across every space, unless a French elision inside
            // the match shows French words: "j'ai pas pu, t'es où ?" is no "pu tes". It never reads
            // its last digit as a number after its own pieces ("pu te 5"), and one the server wrote
            // with a space never reads a digit between English words as a number either: the owner's
            // "ass hole" catches "as 5 hole", while a pack's "pu tain" leaves "put 4 in" alone.
            boolean readAsWords = !word.spaced() && everyday.endsInCount(match[0], match[1])
                    || !(word.spaced() && word.ownList()) && everyday.aroundCount(match[0], match[1])
                    || (!word.spaced() || everyday.throughElision(match[0], match[1]))
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
     * contraction's ending ("let's hit" is not "s hit") or at a digit read as the
     * last letter of the piece before it ("lvl 5 hit").
     */
    static int[] findMatch(
            TextNormalizer.Normalized text,
            OfflineRuleCompiler.CompiledWord word,
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
            if (!tokenStart[start] || masked[start] || joinBlocked[start] || text.noStart()[start]) {
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
}
