package com.neomechanical.neomoderation.moderation;

import java.util.Arrays;
import java.util.Set;

/**
 * Collects the characters {@link TextNormalizer} keeps, then derives token ends,
 * restores numbers and keeps contraction endings, into a
 * {@link TextNormalizer.Normalized}.
 */
final class NormalizedBuilder {

    /** What follows the apostrophe of an English contraction: let's, who're, don't, I'd, I'm, we've, it'll. */
    private static final Set<String> CONTRACTION_ENDINGS = Set.of("s", "re", "t", "d", "m", "ve", "ll");
    /** Letters that are words on their own ("a", "i", "u"), never part of a spelled-out word beside a digit. */
    private static final Set<Character> ONE_LETTER_WORDS = Set.of('a', 'e', 'i', 'o', 'u', 'y');

    private char[] chars;
    private int[] rawIndex;
    private boolean[] tokenStart;
    private boolean[] joinBlocked;
    /** Per char, whether it begins a piece joined to the one before by "_", "-" or the like ("put_a"). */
    private boolean[] joined;
    private boolean[] prefix;
    private boolean[] leet;
    private char[] digits;
    private int count;

    NormalizedBuilder(int rawLength) {
        int capacity = Math.max(16, rawLength + 8);
        chars = new char[capacity];
        rawIndex = new int[capacity];
        tokenStart = new boolean[capacity];
        joinBlocked = new boolean[capacity];
        joined = new boolean[capacity];
        prefix = new boolean[capacity];
        leet = new boolean[capacity];
        digits = new char[capacity];
    }

    void add(char c, int rawAt, boolean startsToken, boolean blocked, char digit, boolean typedAsSymbol) {
        if (count == chars.length) {
            int capacity = count * 2;
            chars = Arrays.copyOf(chars, capacity);
            rawIndex = Arrays.copyOf(rawIndex, capacity);
            tokenStart = Arrays.copyOf(tokenStart, capacity);
            joinBlocked = Arrays.copyOf(joinBlocked, capacity);
            joined = Arrays.copyOf(joined, capacity);
            prefix = Arrays.copyOf(prefix, capacity);
            leet = Arrays.copyOf(leet, capacity);
            digits = Arrays.copyOf(digits, capacity);
        }
        chars[count] = c;
        rawIndex[count] = rawAt;
        tokenStart[count] = startsToken;
        joinBlocked[count] = blocked;
        leet[count] = typedAsSymbol;
        digits[count] = digit;
        count++;
    }

    /** Marks the character just added as the start of a piece joined to the one before ("put_a"). */
    void markJoined() {
        joined[count - 1] = true;
    }

    /** Marks the token being built, {@code length} characters long, as a prefix. */
    void markPrefix(int length) {
        prefix[count - length] = true;
    }

    /** Whether the token being built so far is exactly {@code word}. */
    boolean endsWithWord(String word) {
        int begin = count - word.length();
        if (begin < 0 || !tokenStart[begin]) {
            return false;
        }
        for (int i = 0; i < word.length(); i++) {
            if (chars[begin + i] != word.charAt(i) || (i > 0 && tokenStart[begin + i])) {
                return false;
            }
        }
        return true;
    }

    TextNormalizer.Normalized build() {
        char[] finalChars = Arrays.copyOf(chars, count);
        boolean[] finalStart = Arrays.copyOf(tokenStart, count);
        boolean[] finalEnd = new boolean[count];
        for (int i = 0; i < count; i++) {
            finalEnd[i] = (i == count - 1) || finalStart[i + 1];
        }
        boolean[] trailing = trailingDigits(finalStart, finalEnd);
        restoreNumbers(finalChars, finalStart, finalEnd, trailing);
        boolean[] apostrophe = Arrays.copyOf(joinBlocked, count);
        for (int i = 0; i < count; i++) {
            apostrophe[i] |= joined[i];
        }
        keepContractionEndingsOnly(finalChars, finalEnd);

        StringBuilder collapsed = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            if (i == 0 || finalChars[i] != finalChars[i - 1]) {
                collapsed.append(finalChars[i]);
            }
        }
        return new TextNormalizer.Normalized(finalChars, Arrays.copyOf(rawIndex, count), finalStart, finalEnd,
                Arrays.copyOf(joinBlocked, count), apostrophe, Arrays.copyOf(prefix, count), trailing,
                Arrays.copyOf(leet, count), collapsed.toString());
    }

    /**
     * Puts the digits back in every all-digit token, except a single digit
     * among spelled-out letters, which is how "5 h 1 t", "r e 7 ard" and
     * "sl u 7" are typed (one beside a one-character token), and a digit read
     * as the last letter of the piece before it ({@code trailing}). A digit
     * right after a one-letter word is a quantity, and stays one, when no
     * spelled letter follows it and a word comes before that letter (three
     * letters or more, an English word or a contraction's ending) or the
     * message starts with it: "put a 5 tick delay", "it's a 5 hit combo".
     */
    private void restoreNumbers(char[] out, boolean[] starts, boolean[] ends, boolean[] trailing) {
        int begin = 0;
        while (begin < count) {
            int end = begin;
            while (!ends[end]) {
                end++;
            }
            if (allDigits(begin, end) && !spelledOut(begin, end, starts, ends) && !trailing[begin]) {
                for (int i = begin; i <= end; i++) {
                    out[i] = digits[i];
                }
            }
            begin = end + 1;
        }
    }

    /**
     * Keeps {@code joinBlocked} only on an English contraction's ending: after
     * any other apostrophe ("f'uck", French "l'enculé") a word may begin or run on.
     */
    private void keepContractionEndingsOnly(char[] out, boolean[] ends) {
        for (int begin = 0; begin < count; begin++) {
            if (!joinBlocked[begin]) {
                continue;
            }
            int end = begin;
            while (!ends[end]) {
                end++;
            }
            joinBlocked[begin] = CONTRACTION_ENDINGS.contains(new String(out, begin, end + 1 - begin));
        }
    }

    private boolean allDigits(int begin, int end) {
        for (int i = begin; i <= end; i++) {
            if (digits[i] == 0) {
                return false;
            }
        }
        return true;
    }

    private boolean spelledOut(int begin, int end, boolean[] starts, boolean[] ends) {
        if (begin != end) {
            return false;
        }
        int before = 0;  // one-character tokens right before the digit
        for (int i = begin - 1; i >= 0 && single(i, starts, ends); i--) {
            before++;
        }
        int after = 0;
        for (int i = end + 1; i < count && single(i, starts, ends); i++) {
            after++;
        }
        if (before == 0 && after == 0) {
            return false;
        }
        // "put a 5 tick", "it's a 5 hit combo": a quantity after a one-letter word that follows a word,
        // unless a spelled letter follows it ("is a 5 h i t", "dickh e 4 d").
        boolean afterLetterWord = before == 1 && digits[begin - 1] == 0
                && ONE_LETTER_WORDS.contains(chars[begin - 1]);
        boolean quantity = afterLetterWord && after == 0 && (begin - 1 == 0 || wordEndsAt(begin - 2, starts));
        return !quantity;
    }

    /**
     * Marks each lone digit after a piece of letters as that piece's last
     * letter: "shi 7" reads "shi" and "t", so "shit" matches across them, and
     * so does "s hi 7". A match may end at such a digit but never start there,
     * so "lvl 5 hit" stays clean, and the digit stays its own token, so
     * "badword 1" still holds "badword". A match it ends counts only when the
     * digit is no number ({@link EverydayWords#endsInCount}): "put 4 torches"
     * holds no "puta".
     */
    private boolean[] trailingDigits(boolean[] starts, boolean[] ends) {
        boolean[] trailing = new boolean[count];
        for (int d = 1; d < count; d++) {
            if (!starts[d] || !ends[d] || digits[d] == 0 || starts[d - 1]) {
                continue;  // a lone digit after a token of two characters or more
            }
            if (d + 1 < count && starts[d + 1] && ends[d + 1]) {
                continue;  // a spelled-out run continues: "sh 1 t"
            }
            int begin = d - 1;
            while (begin > 0 && !starts[begin]) {
                begin--;
            }
            boolean letters = true;
            for (int i = begin; i < d; i++) {
                letters &= digits[i] == 0 && Character.isLetter(chars[i]);
            }
            trailing[d] = letters && Character.isLetter(chars[d]);
        }
        return trailing;
    }

    /** A one-character token that is no contraction's ending (the "s" of "it's"). */
    private boolean single(int at, boolean[] starts, boolean[] ends) {
        return starts[at] && ends[at] && !contractionEnding(at, at);
    }

    private boolean contractionEnding(int begin, int end) {
        return joinBlocked[begin] && CONTRACTION_ENDINGS.contains(new String(chars, begin, end + 1 - begin));
    }

    /** Whether the token that ends at {@code end} is a word: English, three letters or more, or "'s". */
    private boolean wordEndsAt(int end, boolean[] starts) {
        int begin = end;
        while (begin > 0 && !starts[begin]) {
            begin--;
        }
        String token = new String(chars, begin, end + 1 - begin);
        return contractionEnding(begin, end) || token.length() >= 3
                || BundledWordPacks.everydayWords(BundledWordPacks.ENGLISH).contains(token);
    }
}
