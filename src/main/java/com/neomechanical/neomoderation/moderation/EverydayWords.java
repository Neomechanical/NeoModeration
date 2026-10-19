package com.neomechanical.neomoderation.moderation;

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
    /** Chat noises, which follow "re-" in nothing anyone writes as two words. */
    private static final Set<String> INTERJECTIONS = Set.of(
            "lol", "lmao", "omg", "xd", "idk", "brb", "afk", "irl", "ngl", "tbh", "wtf", "bruh", "kek");

    /** English words that frame an insult at someone: one of them means the message is English. */
    private static final Set<String> INSULT_FRAMES = Set.of(
            "you", "ur", "your", "youre", "yours", "what", "dirty", "stupid", "ugly", "dumb", "filthy", "nasty",
            "trash", "loser", "die", "kill", "shut", "back", "home", "fat", "hate", "are", "is", "the", "this",
            "he", "she", "his", "her", "him", "they", "them");

    private final TextNormalizer.Normalized text;
    private final OfflineRuleCompiler.Scope scope;
    private int[] tokenAt;
    private int[] languagesOf;
    /** Per token, whether it follows an apostrophe inside a word that is no English contraction. */
    private boolean[] apostropheBefore;
    /** Per token, whether it is a French elision before an apostrophe ("t" in "t'es"). */
    private boolean[] elision;

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
     * {@code words}, leaving out {@code judged}, the words this decides about:
     * it has at least one plainly written word of the language that English
     * does not use, two letters or longer, and none of the English words an
     * insult is framed with ("you", "dirty", "shut") unless the language uses
     * them too. Other words, a game's "spawn" and "lag" or a number, prove
     * nothing either way. "slut på järn" and "Kike, ven al spawn" read as
     * Swedish and Spanish; "you retard le" and "dirty kike de" do not.
     */
    static boolean writtenIn(TextNormalizer.Normalized text, Set<String> words, Set<String> judged) {
        if (words.isEmpty()) {
            return false;
        }
        Set<String> english = BundledWordPacks.everydayWords(BundledWordPacks.ENGLISH);
        boolean theirs = false;
        for (int[] token : OfflineRuleCompiler.tokensOf(text)) {
            String plain = plain(text, token);
            if (plain == null || plain.length() < EVIDENCE_LETTERS || judged.contains(plain)) {
                continue;
            }
            boolean ours = words.contains(plain);
            if (!ours && INSULT_FRAMES.contains(plain)) {
                return false;
            }
            theirs |= ours && !english.contains(plain);
        }
        return theirs;
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
        elision = new boolean[tokens.size()];
        for (int t = 0; t < tokens.size(); t++) {
            int[] token = tokens.get(t);
            for (int i = token[0]; i < token[1]; i++) {
                tokenAt[i] = t;
            }
            String before = t > 0 ? plain(text, tokens.get(t - 1)) : null;
            apostropheBefore[t] = text.apostrophe()[token[0]] && !text.joinBlocked()[token[0]]
                    && !(before != null && ELISIONS.contains(before));
            if (text.joinBlocked()[token[0]] || (text.prefix()[token[0]] && prefixesAWord(tokens, t))) {
                languagesOf[t] = ENGLISH;  // the end of an English contraction ("who're"), or "re-did"
                continue;
            }
            String plain = plain(text, token);
            if (plain != null && ELISIONS.contains(plain) && t + 1 < tokens.size()
                    && text.apostrophe()[tokens.get(t + 1)[0]]) {
                elision[t] = scope.elides() != 0;
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

    /** Whether the match from {@code start} to {@code end} runs through a French elision ("t'es"). */
    boolean throughElision(int start, int end) {
        index();
        for (int token = tokenAt[start]; token < tokenAt[end - 1]; token++) {
            if (elision[token]) {
                return true;
            }
        }
        return false;
    }

    /** Whether the token after a "re-" is a word of its own ("re-did", "re-joined"), not "re-x" or "re-lol". */
    private boolean prefixesAWord(List<int[]> tokens, int t) {
        if (t + 1 >= tokens.size()) {
            return false;
        }
        String next = plain(text, tokens.get(t + 1));
        return next != null && next.length() >= 3 && !INTERJECTIONS.contains(next);
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
