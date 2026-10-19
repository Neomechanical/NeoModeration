package com.neomechanical.neomoderation.moderation;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Tells everyday words from one banned word split up.
 *
 * <p>A match that runs across a space is everyday words, not one disguised word,
 * when every piece of it is an everyday word of one language, written plainly,
 * and that language is English, the server's or the speaker's: "put a torch" is
 * no "puta", and for a Spanish speaker "Mari con" is no "maricon". A word on the
 * server's own list can only be read as English or the server's language, never
 * as a language a player picks for their game. Pieces from different languages
 * ("pa ki", "bean er"), pieces that are not words ("f uck") and pieces typed
 * with digits or symbols ("g0 ok") are still read as one word. A lone digit
 * read as the letter just before it only stretches that letter, so it changes
 * nothing, and an English contraction's ending ("who're") is an English word.</p>
 */
final class EverydayWords {

    /** {@link #languagesOf} for a token that only stretches the letter before it. */
    private static final int STRETCH = -1;
    /** The bit of English, which {@link OfflineRuleCompiler.Scope} always lists first. */
    private static final int ENGLISH = 1;
    /** Fewer letters than this are no evidence of a language ("e", "y"). */
    private static final int EVIDENCE_LETTERS = 2;

    private final TextNormalizer.Normalized text;
    private final OfflineRuleCompiler.Scope scope;
    private int[] tokenAt;
    private int[] languagesOf;

    EverydayWords(TextNormalizer.Normalized text, OfflineRuleCompiler.Scope scope) {
        this.text = text;
        this.scope = scope;
    }

    /**
     * Whether the match from {@code start} to {@code end} runs across a space and
     * every token in it is an everyday word of one language in scope; the
     * speaker's own language counts only when {@code speakerCounts}.
     */
    boolean innocent(int start, int end, boolean speakerCounts) {
        if (!OfflineRuleCompiler.crossesSpace(text, start, end)) {
            return false;
        }
        index();
        int shared = speakerCounts ? -1 : ~scope.speakerOnly();
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
     * Whether the text is written in the language whose everyday words are
     * {@code words}: it has more different plainly written words of it, two
     * letters or longer, that English does not use, than words only English
     * uses ("lol" and "gg" count as English). "ce retard me tue" reads as
     * French; "you retard y" and "shut up retard le" do not.
     */
    static boolean writtenIn(TextNormalizer.Normalized text, Set<String> words) {
        if (words.isEmpty()) {
            return false;
        }
        Set<String> english = BundledWordPacks.everydayWords(BundledWordPacks.ENGLISH);
        Set<String> theirs = new HashSet<>();
        Set<String> englishOnly = new HashSet<>();
        for (int[] token : OfflineRuleCompiler.tokensOf(text)) {
            String plain = plain(text, token);
            if (plain == null || plain.length() < EVIDENCE_LETTERS) {
                continue;
            }
            boolean ours = words.contains(plain);
            boolean englishWord = english.contains(plain);
            if (ours && !englishWord) {
                theirs.add(plain);
            } else if (englishWord && !ours) {
                englishOnly.add(plain);
            }
        }
        return !theirs.isEmpty() && theirs.size() > englishOnly.size();
    }

    /** Numbers the tokens and marks, per token, a bit for each language it is an everyday word of. */
    private void index() {
        if (tokenAt != null) {
            return;
        }
        List<int[]> tokens = OfflineRuleCompiler.tokensOf(text);
        List<Set<String>> languages = scope.languages();
        tokenAt = new int[text.length()];
        languagesOf = new int[tokens.size()];
        for (int t = 0; t < tokens.size(); t++) {
            int[] token = tokens.get(t);
            for (int i = token[0]; i < token[1]; i++) {
                tokenAt[i] = t;
            }
            if (text.joinBlocked()[token[0]]) {
                languagesOf[t] = ENGLISH;  // the end of an English contraction: who're, let's
                continue;
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
