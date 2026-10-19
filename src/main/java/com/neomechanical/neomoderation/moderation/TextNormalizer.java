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
 * <p>Two kinds of token are never treated as fragments of a disguised word. A
 * token after an apostrophe inside a word ({@code let's}, {@code who're}) is the
 * end of a contraction, so "let's hit" is not "s hit". A token made only of
 * digits is a number, so "5 hits" and "455 diamonds" keep their digits; a lone
 * digit inside a spaced-out run such as {@code 5 h 1 t} still reads as a letter.</p>
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
        String other = "օոսցԁԝѡı";
        String otherLatin = "onugdwwi";
        put(cyrillic, cyrillicLatin);
        put(greek, greekLatin);
        put(other, otherLatin);
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
     * @param joinBlocked  whether each char began a token that follows an apostrophe
     *                     inside a word; a match may neither start there nor run into it
     * @param collapsed    {@code chars} with runs of the same character reduced to
     *                     one, used only as a cheap necessary-condition prefilter
     */
    record Normalized(
            char[] chars,
            int[] rawIndex,
            boolean[] tokenStart,
            boolean[] tokenEnd,
            boolean[] joinBlocked,
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
                    out.add('s', rawAt, atTokenStart, atTokenStart && afterInWordApostrophe, (char) 0);
                    out.add('s', rawAt, false, false, (char) 0);
                    atTokenStart = false;
                    afterInWordApostrophe = false;
                } else if (Character.isLetterOrDigit(c)) {
                    out.add(c, rawAt, atTokenStart, atTokenStart && afterInWordApostrophe, digit);
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

    /** The separator-free, run-collapsed spelling of a configured rule. */
    static String collapsedForm(String value, boolean normalizeLeetspeak) {
        return normalize(value, normalizeLeetspeak).collapsed();
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

    /** Collects normalised characters, then derives token ends and restores numbers. */
    private static final class Builder {
        private char[] chars;
        private int[] rawIndex;
        private boolean[] tokenStart;
        private boolean[] joinBlocked;
        private char[] digits;
        private int count;

        Builder(int rawLength) {
            int capacity = Math.max(16, rawLength + 8);
            chars = new char[capacity];
            rawIndex = new int[capacity];
            tokenStart = new boolean[capacity];
            joinBlocked = new boolean[capacity];
            digits = new char[capacity];
        }

        void add(char c, int rawAt, boolean startsToken, boolean blocked, char digit) {
            if (count == chars.length) {
                int capacity = count * 2;
                chars = Arrays.copyOf(chars, capacity);
                rawIndex = Arrays.copyOf(rawIndex, capacity);
                tokenStart = Arrays.copyOf(tokenStart, capacity);
                joinBlocked = Arrays.copyOf(joinBlocked, capacity);
                digits = Arrays.copyOf(digits, capacity);
            }
            chars[count] = c;
            rawIndex[count] = rawAt;
            tokenStart[count] = startsToken;
            joinBlocked[count] = blocked;
            digits[count] = digit;
            count++;
        }

        Normalized build() {
            char[] finalChars = Arrays.copyOf(chars, count);
            boolean[] finalStart = Arrays.copyOf(tokenStart, count);
            boolean[] finalEnd = new boolean[count];
            for (int i = 0; i < count; i++) {
                finalEnd[i] = (i == count - 1) || finalStart[i + 1];
            }
            restoreNumbers(finalChars, finalStart, finalEnd);

            StringBuilder collapsed = new StringBuilder(count);
            for (int i = 0; i < count; i++) {
                if (i == 0 || finalChars[i] != finalChars[i - 1]) {
                    collapsed.append(finalChars[i]);
                }
            }
            return new Normalized(finalChars, Arrays.copyOf(rawIndex, count), finalStart, finalEnd,
                    Arrays.copyOf(joinBlocked, count), collapsed.toString());
        }

        /**
         * Puts the digits back in every all-digit token, except a single digit
         * standing between single characters, which is how "5 h 1 t" is typed.
         */
        private void restoreNumbers(char[] out, boolean[] starts, boolean[] ends) {
            int begin = 0;
            while (begin < count) {
                int end = begin;
                while (!ends[end]) {
                    end++;
                }
                if (allDigits(begin, end) && !inSpacedRun(begin, end, starts, ends)) {
                    for (int i = begin; i <= end; i++) {
                        out[i] = digits[i];
                    }
                }
                begin = end + 1;
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

        private boolean inSpacedRun(int begin, int end, boolean[] starts, boolean[] ends) {
            if (begin != end) {
                return false;
            }
            boolean singleBefore = begin > 0 && starts[begin - 1];
            boolean singleAfter = end + 1 < count && ends[end + 1];
            return singleBefore || singleAfter;
        }
    }
}
