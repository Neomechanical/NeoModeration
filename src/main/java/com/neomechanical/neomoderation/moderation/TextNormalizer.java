package com.neomechanical.neomoderation.moderation;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Folds a chat message down to the letters a player actually meant.
 *
 * <p>A word list is only as good as the spelling it is compared against, and
 * the ways round one are well known: {@code f uck}, {@code fuuuck},
 * {@code f.u.c.k}, {@code fúck}, {@code ｆｕｃｋ}, {@code fυck} with a Greek
 * upsilon, or a zero-width space wedged in the middle. Measured against 1.5.0,
 * <b>18 of 51</b> such forms were caught.</p>
 *
 * <p>Normalisation happens per code point rather than over the whole string, so
 * every surviving character still knows which raw index it came from. That is
 * what lets {@link OfflineModerationEngine#censor} star the right characters
 * back in the original message.</p>
 *
 * <p>Separators are dropped rather than preserved, and each character records
 * whether it began or ended a token in the raw text. A banned word may then be
 * matched across separators, catching {@code f uck}, while still requiring
 * the match to start and end on token boundaries, which is what keeps
 * {@code scampi}, {@code assignment} and {@code Scunthorpe} clean.</p>
 *
 * <p>An apostrophe inside a word splits it but hides nothing: "f'uck" is still
 * read as one word. Only an English contraction's ending ('s, 're, 't, 'd, 'm,
 * 've, 'll) never begins a banned word, so "let's hit" is not "s hit". A hyphen
 * after "re" joins it to the word ("re-did"). A token made only of digits is a
 * number, so "5 hits" and "455 diamonds" keep their digits; a lone digit among
 * spelled-out letters such as {@code 5 h 1 t} still reads as a letter, but not
 * one beside a one-letter word ("put a 5 tick delay").</p>
 */
final class TextNormalizer {

    /**
     * Latin lookalikes from Cyrillic, Greek and Armenian. Restricted to
     * characters that are visually identical in the fonts Minecraft renders
     * chat with, so folding them cannot change the reading of genuine
     * non-Latin chat: a real Cyrillic word is not spelled entirely from Latin
     * lookalikes, and a mixed-script token is the signature of the evasion.
     * Turkish dotless {@code ı} folds to {@code i} so the Turkish starter list,
     * written without it, matches what Turkish players type.
     */
    private static final Map<Character, Character> CONFUSABLES = new HashMap<>();

    static {
        String cyrillic = "авсеhкморѕтхуіјdνq";
        String cyrillicLatin = "abcehkmopstxyijdvq";
        String greek = "αβεζηικνορτυχγ";
        String greekLatin = "abeznikvoptuxy";
        String other = "օոսցԁԝѡıƒ";
        String otherLatin = "onugdwwif";
        // Small capitals, as in "ɴɪɢɢᴇʀ".
        String smallCapitals = "ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘʀꜱᴛᴜᴠᴡʏᴢ";
        String smallCapitalsLatin = "abcdefghijklmnoprstuvwyz";
        put(cyrillic, cyrillicLatin);
        put(greek, greekLatin);
        put(other, otherLatin);
        put(smallCapitals, smallCapitalsLatin);
    }

    private static void put(String from, String to) {
        for (int i = 0; i < from.length() && i < to.length(); i++) {
            CONFUSABLES.put(from.charAt(i), to.charAt(i));
        }
    }

    /**
     * A message reduced to letters and digits, with the provenance needed to
     * map any match back onto the raw text.
     *
     * @param chars        normalised letters/digits, separators removed
     * @param rawIndex     for each char, the index it came from in the raw message
     * @param tokenStart   whether each char began a whitespace/punctuation-delimited token
     * @param tokenEnd     whether each char ended one
     * @param joinBlocked  whether each char began an English contraction's ending
     *                     after an apostrophe inside a word ('s, 're, 't, 'd, 'm, 've,
     *                     'll); a match never starts there
     * @param leet         whether each char was typed as a digit or symbol ("g0" for "go")
     * @param collapsed    {@code chars} with runs of the same character reduced to
     *                     one, used only as a cheap necessary-condition prefilter
     */
    record Normalized(
            char[] chars,
            int[] rawIndex,
            boolean[] tokenStart,
            boolean[] tokenEnd,
            boolean[] joinBlocked,
            boolean[] leet,
            String collapsed
    ) {
        int length() {
            return chars.length;
        }
    }

    private TextNormalizer() {
    }

    static Normalized normalize(String value, boolean normalizeLeetspeak) {
        Builder out = new Builder(value.length());
        boolean atTokenStart = true;
        boolean afterInWordApostrophe = false;

        for (int i = 0; i < value.length(); ) {
            int codePoint = value.codePointAt(i);
            int rawAt = i;
            i += Character.charCount(codePoint);

            // Zero-width joiners, soft hyphens, byte-order marks: invisible to a
            // reader, so they must be invisible to the filter too. Dropping them
            // outright (rather than treating them as separators) means they do
            // not even split a token.
            if (Character.getType(codePoint) == Character.FORMAT
                    || Character.isIdentifierIgnorable(codePoint)) {
                continue;
            }
            if (isApostrophe(codePoint)) {
                afterInWordApostrophe = !atTokenStart
                        && i < value.length()
                        && Character.isLetter(value.codePointAt(i));
                atTokenStart = true;
                continue;
            }
            if (isHyphen(codePoint) && i < value.length() && Character.isLetter(value.codePointAt(i))
                    && out.endsWithWord("re")) {
                continue;  // "re-did" is one word, not "re" and "did"
            }

            String decomposed = Normalizer.normalize(
                    new String(Character.toChars(codePoint)), Normalizer.Form.NFKD);
            for (int k = 0; k < decomposed.length(); k++) {
                char raw = decomposed.charAt(k);
                if (Character.getType(raw) == Character.NON_SPACING_MARK) {
                    continue;  // the accent on an accented letter
                }
                char c = Character.toLowerCase(raw);
                Character folded = CONFUSABLES.get(c);
                if (folded != null) {
                    c = folded;
                }
                char digit = c >= '0' && c <= '9' ? c : 0;
                boolean typedAsLetter = Character.isLetter(c);
                if (normalizeLeetspeak) {
                    // '!' is a leetspeak 'i' inside a word ("b!tch") but ordinary
                    // punctuation at a boundary; converting it unconditionally
                    // turned "scam!" into "scami", which no whole-word rule matched.
                    if (c != '!' || isBetweenWordCharacters(value, rawAt)) {
                        c = normalizeLeet(c);
                    }
                }
                if (c == 'ß') {
                    // German sharp s is spelled "ss" in every word list.
                    out.add('s', rawAt, atTokenStart, atTokenStart && afterInWordApostrophe, (char) 0, false);
                    out.add('s', rawAt, false, false, (char) 0, false);
                    atTokenStart = false;
                    afterInWordApostrophe = false;
                } else if (Character.isLetterOrDigit(c)) {
                    out.add(c, rawAt, atTokenStart, atTokenStart && afterInWordApostrophe, digit,
                            !typedAsLetter && Character.isLetter(c));
                    atTokenStart = false;
                    afterInWordApostrophe = false;
                } else {
                    atTokenStart = true;
                    afterInWordApostrophe = false;
                }
            }
        }
        return out.build();
    }

    private static boolean isHyphen(int codePoint) {
        return codePoint == '-' || codePoint == '\u2010' || codePoint == '\u2011';
    }

    private static boolean isApostrophe(int codePoint) {
        return codePoint == '\'' || codePoint == '’' || codePoint == '‘'
                || codePoint == 'ʼ' || codePoint == '`';
    }

    private static boolean isBetweenWordCharacters(String value, int index) {
        if (index <= 0 || index >= value.length() - 1) {
            return false;
        }
        return Character.isLetterOrDigit(value.charAt(index - 1))
                && Character.isLetterOrDigit(value.charAt(index + 1));
    }

    private static char normalizeLeet(char c) {
        return switch (c) {
            case '@', '4' -> 'a';
            case '3' -> 'e';
            case '1', '!', '|' -> 'i';
            case '0' -> 'o';
            case '$', '5' -> 's';
            case '7' -> 't';
            default -> c;
        };
    }

    /** What follows the apostrophe of an English contraction: let's, who're, don't, I'd, I'm, we've, it'll. */
    private static final java.util.Set<String> CONTRACTION_ENDINGS = java.util.Set.of("s", "re", "t", "d", "m", "ve", "ll");
    /** Letters that are words on their own ("a", "i", "u"), never part of a spelled-out word beside a digit. */
    private static final java.util.Set<Character> ONE_LETTER_WORDS = java.util.Set.of('a', 'e', 'i', 'o', 'u', 'y');

    /** Collects normalised characters, then derives token ends and restores numbers. */
    private static final class Builder {
        private char[] chars;
        private int[] rawIndex;
        private boolean[] tokenStart;
        private boolean[] joinBlocked;
        private boolean[] leet;
        private char[] digits;
        private int count;

        Builder(int rawLength) {
            int capacity = Math.max(16, rawLength + 8);
            chars = new char[capacity];
            rawIndex = new int[capacity];
            tokenStart = new boolean[capacity];
            joinBlocked = new boolean[capacity];
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

        Normalized build() {
            char[] finalChars = Arrays.copyOf(chars, count);
            boolean[] finalStart = Arrays.copyOf(tokenStart, count);
            boolean[] finalEnd = new boolean[count];
            for (int i = 0; i < count; i++) {
                finalEnd[i] = (i == count - 1) || finalStart[i + 1];
            }
            restoreNumbers(finalChars, finalStart, finalEnd);
            keepContractionEndingsOnly(finalChars, finalEnd);

            StringBuilder collapsed = new StringBuilder(count);
            for (int i = 0; i < count; i++) {
                if (i == 0 || finalChars[i] != finalChars[i - 1]) {
                    collapsed.append(finalChars[i]);
                }
            }
            return new Normalized(finalChars, Arrays.copyOf(rawIndex, count), finalStart, finalEnd,
                    Arrays.copyOf(joinBlocked, count), Arrays.copyOf(leet, count), collapsed.toString());
        }

        /**
         * Puts the digits back in every all-digit token, except a single digit
         * among spelled-out letters, which is how "5 h 1 t" is typed: in a run of
         * three or more one-character tokens, or beside a one-character token
         * that is no word ("sh 1 t"). Beside a one-letter word it is a number:
         * "put a 5 tick delay".
         */
        private void restoreNumbers(char[] out, boolean[] starts, boolean[] ends) {
            int begin = 0;
            while (begin < count) {
                int end = begin;
                while (!ends[end]) {
                    end++;
                }
                if (allDigits(begin, end) && !spelledOut(begin, end, starts, ends)) {
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
            int run = 1;
            for (int i = begin - 1; i >= 0 && starts[i] && ends[i]; i--) {
                run++;
            }
            for (int i = end + 1; i < count && starts[i] && ends[i]; i++) {
                run++;
            }
            boolean letterBefore = begin > 0 && starts[begin - 1] && !ONE_LETTER_WORDS.contains(chars[begin - 1]);
            boolean letterAfter = end + 1 < count && ends[end + 1] && !ONE_LETTER_WORDS.contains(chars[end + 1]);
            return run >= 3 || letterBefore || letterAfter;
        }
    }
}
