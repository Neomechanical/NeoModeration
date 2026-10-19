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
 * ("pa ki", "bean er"), pieces that are not words ("f uck"), pieces typed
 * with digits or symbols ("g0 ok") and pieces joined by an apostrophe inside a
 * word ("go'ok", which nobody writes for two words) are still read as one word.
 * A lone digit read as the letter just before it only stretches that letter, so
 * it changes nothing. An English contraction's ending ("who're") and a "re"
 * before a hyphen ("who re-did") are English words, and a French elision ("pu,
 * t'es") separates words as a space does.</p>
 */
final class EverydayWords {

    /** {@link #languagesOf} for a token that only stretches the letter before it. */
    private static final int STRETCH = -1;
    /** The bit of English, which {@link OfflineRuleCompiler.Scope} always lists first. */
    private static final int ENGLISH = 1;
    /** French elided words, which an apostrophe joins to the next word: l', d', j', t', s', m', n', c', qu'. */
    private static final Set<String> ELISIONS = Set.of("l", "d", "j", "t", "s", "m", "n", "c", "qu");
    /** Fewer letters than this are no evidence of a language ("e", "y"). */
    private static final int EVIDENCE_LETTERS = 2;

    private final TextNormalizer.Normalized text;
    private final OfflineRuleCompiler.Scope scope;
    private int[] tokenAt;
    private int[] languagesOf;
    /** Per token, whether it follows an apostrophe inside a word that is no English contraction. */
    private boolean[] apostropheBefore;

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
            if (token > tokenAt[start] && apostropheBefore[token]) {
                return false;
            }
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
     * {@code words}, leaving out {@code judged}, the words this decides about.
     * Counting different plainly written words of two letters or more: none
     * may be English only ("lol", "dirty" and "gg" are English), at least one
     * must be a word of the language that English does not use, and the
     * language's words must outnumber words of no list. "ce retard me tue"
     * reads as French; "you retard le" and "dirty kike de" do not.
     */
    static boolean writtenIn(TextNormalizer.Normalized text, Set<String> words, Set<String> judged) {
        if (words.isEmpty()) {
            return false;
        }
        Set<String> english = BundledWordPacks.everydayWords(BundledWordPacks.ENGLISH);
        Set<String> theirs = new HashSet<>();
        Set<String> theirsOnly = new HashSet<>();
        Set<String> unknown = new HashSet<>();
        for (int[] token : OfflineRuleCompiler.tokensOf(text)) {
            String plain = plain(text, token);
            if (plain == null || plain.length() < EVIDENCE_LETTERS || judged.contains(plain)) {
                continue;
            }
            boolean ours = words.contains(plain);
            boolean englishWord = english.contains(plain);
            if (englishWord && !ours) {
                return false;
            }
            if (ours) {
                theirs.add(plain);
                if (!englishWord) {
                    theirsOnly.add(plain);
                }
            } else {
                unknown.add(plain);
            }
        }
        return !theirsOnly.isEmpty() && theirs.size() > unknown.size();
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
        apostropheBefore = new boolean[tokens.size()];
        for (int t = 0; t < tokens.size(); t++) {
            int[] token = tokens.get(t);
            for (int i = token[0]; i < token[1]; i++) {
                tokenAt[i] = t;
            }
            String before = t > 0 ? plain(text, tokens.get(t - 1)) : null;
            apostropheBefore[t] = text.apostrophe()[token[0]] && !text.joinBlocked()[token[0]]
                    && !(before != null && ELISIONS.contains(before));
            if (text.joinBlocked()[token[0]] || text.prefix()[token[0]]) {
                languagesOf[t] = ENGLISH;  // the end of an English contraction ("who're"), or "re-"
                continue;
            }
            String plain = plain(text, token);
            if (plain != null && ELISIONS.contains(plain) && t + 1 < tokens.size()
                    && text.apostrophe()[tokens.get(t + 1)[0]]) {
                languagesOf[t] = scope.elides();  // "t'" in "pu, t'es": a French word
                continue;
            }
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
