package com.neomechanical.neomoderation.moderation;

import java.util.List;
import java.util.Set;

/**
 * Tells two everyday words from one banned word split up.
 *
 * <p>A match that runs across a space is two words, not one disguised word,
 * when every piece of it is an everyday word of one language, written plainly,
 * and that language is English, the server's or the speaker's: "put a" is not
 * "puta", and for a Spanish speaker "Mari con" is not "maricon". Pieces from
 * different languages ("pa ki", "bean er"), pieces that are not words
 * ("f uck") and pieces typed with digits or symbols ("g0 ok") are still read
 * as one word. A lone digit read as the letter just before it only stretches
 * that letter, so it changes nothing: "put a 4 in the hopper" is still
 * everyday words.</p>
 */
final class EverydayWords {

    /** {@link #languagesOf} for a token that only stretches the letter before it. */
    private static final int STRETCH = -1;

    private final TextNormalizer.Normalized text;
    private final List<Set<String>> languages;
    private int[] tokenAt;
    private int[] languagesOf;

    /**
     * @param text      the normalised message
     * @param languages the everyday words of each language in scope, at most 31
     */
    EverydayWords(TextNormalizer.Normalized text, List<Set<String>> languages) {
        this.text = text;
        this.languages = languages;
    }

    /**
     * Whether the match from {@code start} to {@code end} runs across a space and
     * every token in it is an everyday word of one language in scope.
     */
    boolean innocent(int start, int end) {
        if (!OfflineRuleCompiler.crossesSpace(text, start, end)) {
            return false;
        }
        index();
        int shared = -1;
        boolean anyWord = false;
        for (int token = tokenAt[start]; token <= tokenAt[end - 1]; token++) {
            shared &= languagesOf[token];
            anyWord |= languagesOf[token] != STRETCH;
            if (shared == 0) {
                return false;
            }
        }
        return anyWord;
    }

    /**
     * Whether the text has a plainly written everyday word of {@code words}
     * that is not an English everyday word, so it reads as that language.
     */
    static boolean writtenIn(TextNormalizer.Normalized text, Set<String> words) {
        if (words.isEmpty()) {
            return false;
        }
        Set<String> english = BundledWordPacks.everydayWords(BundledWordPacks.ENGLISH);
        for (int[] token : OfflineRuleCompiler.tokensOf(text)) {
            String plain = plain(text, token);
            if (plain != null && words.contains(plain) && !english.contains(plain)) {
                return true;
            }
        }
        return false;
    }

    /** Numbers the tokens and marks, per token, a bit for each language it is an everyday word of. */
    private void index() {
        if (tokenAt != null) {
            return;
        }
        List<int[]> tokens = OfflineRuleCompiler.tokensOf(text);
        tokenAt = new int[text.length()];
        languagesOf = new int[tokens.size()];
        for (int t = 0; t < tokens.size(); t++) {
            int[] token = tokens.get(t);
            for (int i = token[0]; i < token[1]; i++) {
                tokenAt[i] = t;
            }
            String plain = plain(text, token);
            if (plain == null) {
                if (stretches(token)) {
                    languagesOf[t] = STRETCH;
                }
                continue;
            }
            for (int language = 0; language < languages.size(); language++) {
                if (languages.get(language).contains(plain)) {
                    languagesOf[t] |= 1 << language;
                }
            }
        }
    }

    /** Whether the token is one character typed as a digit or symbol that repeats the letter before it. */
    private boolean stretches(int[] token) {
        return token[1] - token[0] == 1 && token[0] > 0 && text.chars()[token[0]] == text.chars()[token[0] - 1];
    }

    /** The token's letters, or {@code null} when any was typed as a digit or symbol. */
    private static String plain(TextNormalizer.Normalized text, int[] token) {
        for (int i = token[0]; i < token[1]; i++) {
            if (text.leet()[i]) {
                return null;
            }
        }
        return new String(text.chars(), token[0], token[1] - token[0]);
    }
}
