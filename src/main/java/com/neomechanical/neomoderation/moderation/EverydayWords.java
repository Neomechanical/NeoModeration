package com.neomechanical.neomoderation.moderation;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Tells everyday words from one banned word split up.
 *
 * <p>A match that runs across a space is everyday words, not one disguised word,
 * when every piece of it is an everyday word of one language, written plainly,
 * and that language is English, the server's or a trusted speaker's: "put a
 * torch" is no "puta", and for a Spanish speaker "Mari con" is no "maricon". A
 * word on the server's own list can only be read as English or the server's
 * language, never as a language a player picks for their game, except that a
 * number after a trusted speaker's word stays a number ("pak 1", see
 * {@link #endsInCount}). Pieces from different languages
 * ("pa ki", "bean er"), pieces that are not words ("f uck"), pieces typed
 * with digits or symbols ("g0 ok") and pieces joined by an apostrophe inside a
 * word ("go'ok", which nobody writes for two words) are still read as one word.
 * A lone digit that repeats the letter before it only stretches that letter, so
 * it changes nothing as a piece ("as 5 hole" is still caught by a word written
 * with a space, such as the server's "ass hole"). An English contraction's ending ("who're") is an English
 * word, and so is a "re" before a hyphen and a word of three letters or more
 * ("who re-did", not "who re-x"); a French elision ("pu, t'es") separates words
 * as a space does.</p>
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
            "lol", "lmao", "lmfao", "rofl", "omg", "xd", "xdd", "haha", "idk", "brb", "afk", "irl", "ngl", "tbh",
            "wtf", "bruh", "kek");

    /** English words that frame an insult at someone ("you", "dirty", "get out"). */
    private static final Set<String> INSULT_FRAME_WORDS = Set.of(
            "you", "u", "ur", "yu", "ure", "your", "youre", "yours", "what", "dirty", "stupid", "ugly", "dumb",
            "filthy", "nasty", "smelly", "gross", "pathetic", "cheap", "trash", "loser", "die", "kill", "shut",
            "back", "home", "fat", "hate", "are", "is", "the", "this", "he", "she", "his", "her", "him", "they",
            "them", "get", "out", "away", "such", "big", "lil", "little", "imagine", "being", "nobody", "hell",
            "cry", "shes", "hes");
    /** {@link #INSULT_FRAME_WORDS} with letter runs collapsed: one of them, however stretched, frames an insult. */
    private static final Set<String> INSULT_FRAMES = INSULT_FRAME_WORDS.stream()
            .map(EverydayWords::collapse)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    /** Chat and game words every language's players use, which say nothing about a message's language. */
    private static final Set<String> SHARED_WORDS = Set.of(
            "lol", "lmao", "omg", "gg", "wp", "bruh", "idk", "btw", "pls", "plz", "thx", "ty", "np", "brb", "afk",
            "irl", "rn", "ngl", "tbh", "ok", "okay", "okey", "op", "lag", "spawn", "base", "farm", "shop", "loot",
            "pvp", "mob", "noob", "xp", "tp", "end", "nether", "server", "event", "build", "wifi", "crash",
            "redstone", "admin", "mod", "ban", "kick", "mute", "chat", "level", "diamond", "diamonds", "iron",
            "gold", "boss", "steak", "block", "chest", "sword", "skin", "map", "game", "team", "raid", "full",
            "armor", "netherite", "elytra", "elytras", "totem", "sharpness");

    private final TextNormalizer.Normalized text;
    private final OfflineRuleCompiler.Scope scope;
    /** The everyday words of the speaker's language when the server trusts it, else {@code null}. */
    private final Set<String> speakerWords;
    private int[] tokenAt;
    private int[] languagesOf;
    /** Per token, whether it follows an apostrophe inside a word that is no English contraction. */
    private boolean[] apostropheBefore;
    /** Per token, whether it is a French elision before an apostrophe ("t" in "t'es"). */
    private boolean[] elision;
    /** Per token, whether it is an English insult frame the speaker's language shares ("ure", "he"). */
    private boolean[] sharedFrame;
    /** Whether the text holds an English insult frame the speaker's language does not use ("dirty"). */
    private boolean foreignFrame;

    EverydayWords(TextNormalizer.Normalized text, OfflineRuleCompiler.Scope scope, Set<String> speakerWords) {
        this.text = text;
        this.scope = scope;
        this.speakerWords = speakerWords;
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
     * Whether the match from {@code start} ends at a digit read as the last letter
     * of the word before it while the digit is a number: every piece of the match
     * before the digit is an everyday word of one language in scope. "put 4
     * torches" holds no "puta", and from a trusted Dutch player "hoeveel wil je?
     * pak 1" holds no "paki". The speaker's language counts here only in a message
     * with no English insult frame their language does not use, and with none they
     * share ("ure", "he") among the two pieces before the match: "he pak 1" and
     * "ure a dyk 3" stay caught, "pak 1, het is gratis" is a number. After any
     * other piece the digit is a letter, whatever follows: "shi 7", "s hi 7",
     * "shi 7 lool", "go home pak 1".
     */
    boolean endsInCount(int start, int end) {
        if (!text.noStart()[end - 1]) {
            return false;
        }
        index();
        int first = tokenAt[start];
        int digit = tokenAt[end - 1];
        boolean speaker = speakerWords != null && !foreignFrame
                && !(first >= 1 && sharedFrame[first - 1]) && !(first >= 2 && sharedFrame[first - 2]);
        int shared = speaker ? -1 : ~scope.speakerOnly();
        boolean anyWord = false;
        for (int token = first; token < digit; token++) {
            if (token > first && apostropheBefore[token]) {
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
     * Whether the match runs through a digit read as a letter while every other
     * piece of it is an English everyday word, so the digit is a number between
     * them: "put 4 in the furnace" holds no "putain", and "gg 0 ok" no "gook".
     */
    boolean aroundCount(int start, int end) {
        index();
        boolean digit = false;
        for (int i = start; i < end; i++) {
            if (!text.tokenStart()[i]) {
                continue;
            }
            int token = tokenAt[i];
            if (text.noStart()[i]) {
                digit = true;
            } else if ((languagesOf[token] & ENGLISH) == 0 || (i > start && apostropheBefore[token])) {
                return false;
            }
        }
        return digit;
    }

    /**
     * Whether the text is written in the language whose everyday words are
     * {@code words}, leaving out {@code judged}, the words this decides about.
     * None of its words may be an English word an insult is framed with ("you",
     * "u", "dirty", "get out", also stretched or typed with digits: "youu", "y0u")
     * that the language does not use itself; one it uses too (Danish "ure",
     * clocks) shows neither language, while a word of its own that only
     * collapses to one (French "gros") counts as its own. Then its words of
     * that language, two letters or more, must include one English does not use
     * and outnumber its English-only words; chat and game words every language
     * borrows ("lol", "afk", "spawn") count for neither. "slut på järn" and
     * "Kike, ven al spawn" read as Swedish and Spanish; "dirty kike de" and "big
     * slut på" do not.
     */
    static boolean writtenIn(TextNormalizer.Normalized text, Set<String> words, Set<String> judged) {
        if (words.isEmpty() || framedInEnglish(text, words)) {
            return false;
        }
        Set<String> english = BundledWordPacks.everydayWords(BundledWordPacks.ENGLISH);
        Set<String> theirs = new HashSet<>();
        boolean theirsOnly = false;
        Set<String> englishOnly = new HashSet<>();
        for (int[] token : OfflineRuleCompiler.tokensOf(text)) {
            String letters = new String(text.chars(), token[0], token[1] - token[0]);
            if (judged.contains(letters)) {
                continue;
            }
            boolean ours = words.contains(letters);
            if (ours && INSULT_FRAME_WORDS.contains(letters)) {
                continue;  // their word is the English frame too ("ure", clocks): it shows neither language
            }
            String plain = plain(text, token);
            if (plain == null || plain.length() < EVIDENCE_LETTERS) {
                continue;
            }
            if (ours) {
                theirs.add(plain);
                theirsOnly |= !english.contains(plain);
            } else if (english.contains(plain) && !SHARED_WORDS.contains(plain)) {
                englishOnly.add(plain);
            }
        }
        return theirsOnly && theirs.size() > englishOnly.size();
    }

    /**
     * Whether a word of the text, however stretched or typed with digits, is an
     * English word an insult is framed with that the language whose everyday
     * words are {@code words} does not use itself: "y0u", "dirty", "get out".
     */
    static boolean framedInEnglish(TextNormalizer.Normalized text, Set<String> words) {
        for (int[] token : OfflineRuleCompiler.tokensOf(text)) {
            String letters = new String(text.chars(), token[0], token[1] - token[0]);
            if (!words.contains(letters) && INSULT_FRAMES.contains(collapse(letters))) {
                return true;
            }
        }
        return false;
    }

    /** A word with each run of a letter reduced to one: "youu" is "you", "dirtyy" is "dirty". */
    private static String collapse(String word) {
        StringBuilder collapsed = new StringBuilder(word.length());
        for (int i = 0; i < word.length(); i++) {
            if (i == 0 || word.charAt(i) != word.charAt(i - 1)) {
                collapsed.append(word.charAt(i));
            }
        }
        return collapsed.toString();
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
        sharedFrame = new boolean[tokens.size()];
        foreignFrame = speakerWords != null && framedInEnglish(text, speakerWords);
        for (int t = 0; t < tokens.size(); t++) {
            int[] token = tokens.get(t);
            for (int i = token[0]; i < token[1]; i++) {
                tokenAt[i] = t;
            }
            String letters = new String(text.chars(), token[0], token[1] - token[0]);
            sharedFrame[t] = speakerWords != null && speakerWords.contains(letters)
                    && INSULT_FRAME_WORDS.contains(letters);
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
