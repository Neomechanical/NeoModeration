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
 * 've, 'll) never begins a banned word, so "let's hit" is not "s hit". A "re"
 * before a hyphen is marked as a prefix ("who re-did"), and the word after it is
 * read on its own. A token made only of digits is a number, so "5 hits" and
 * "455 diamonds" keep their digits; a lone digit among spelled-out letters such
 * as {@code 5 h 1 t} or {@code sl u 7} still reads as a letter, but not a
 * quantity after a one-letter word that follows a word ("put a 5 tick delay").</p>
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
     * @param apostrophe   whether each char began a token after any apostrophe inside
     *                     a word, contraction or not ("f'uck", "bad'word")
     * @param prefix       whether each char began a "re" written before a hyphen
     *                     ("re-did"), which is an English prefix rather than a word piece
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
            boolean[] apostrophe,
            boolean[] prefix,
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
            if (isBlankFiller(codePoint)) {
                // Drawn as nothing, so it reads as the space it stands for: "putㅤa" is "put a".
                atTokenStart = true;
                afterInWordApostrophe = false;
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
                out.markPrefix(2);  // "re-did": a prefix, and "did" is read on its own
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
                || codePoint == 'ʼ' || codePoint == '`'
                // Marks typed or drawn as an apostrophe: ´ ′ ‵ ＇ ׳ ՚ ՙ ⸯ ॱ.
                || codePoint == 0x00B4 || codePoint == 0x2032 || codePoint == 0x2035 || codePoint == 0xFF07
                || codePoint == 0x05F3 || codePoint == 0x055A || codePoint == 0x0559 || codePoint == 0x2E2F
                || codePoint == 0x0971
                // Modifier letters and tone marks drawn as ticks, accents or dots (ʹ ʻ ˈ ˌ ː ˮ ꞌ ꜀),
                // but never one that folds to a letter: ˡ ˢ ˣ are the l, s and x of "tiny text".
                || ((codePoint >= 0x02B9 && codePoint <= 0x02FF || codePoint >= 0xA700 && codePoint <= 0xA71F
                        || codePoint == 0xA788 || codePoint == 0xA78B || codePoint == 0xA78C
                        || codePoint == 0x07F4 || codePoint == 0x07F5) && !foldsToLetter(codePoint));
    }

    /** Whether a character has a compatibility form that is a letter, as ˢ is "s" (ʻ has none). */
    private static boolean foldsToLetter(int codePoint) {
        String original = new String(Character.toChars(codePoint));
        String folded = Normalizer.normalize(original, Normalizer.Form.NFKD);
        return !folded.equals(original) && folded.codePoints().anyMatch(Character::isLetter);
    }

    /** Hangul fillers, which draw nothing and so can hide inside a word. */
    private static boolean isBlankFiller(int codePoint) {
        return codePoint == 0x115F || codePoint == 0x1160 || codePoint == 0x3164 || codePoint == 0xFFA0;
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
        private boolean[] prefix;
        private boolean[] leet;
        private char[] digits;
        private int count;

        Builder(int rawLength) {
            int capacity = Math.max(16, rawLength + 8);
            chars = new char[capacity];
            rawIndex = new int[capacity];
            tokenStart = new boolean[capacity];
            joinBlocked = new boolean[capacity];
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

        Normalized build() {
            char[] finalChars = Arrays.copyOf(chars, count);
            boolean[] finalStart = Arrays.copyOf(tokenStart, count);
            boolean[] finalEnd = new boolean[count];
            for (int i = 0; i < count; i++) {
                finalEnd[i] = (i == count - 1) || finalStart[i + 1];
            }
            joinTrailingDigits(finalStart, finalEnd);
            restoreNumbers(finalChars, finalStart, finalEnd);
            boolean[] apostrophe = Arrays.copyOf(joinBlocked, count);
            keepContractionEndingsOnly(finalChars, finalEnd);

            StringBuilder collapsed = new StringBuilder(count);
            for (int i = 0; i < count; i++) {
                if (i == 0 || finalChars[i] != finalChars[i - 1]) {
                    collapsed.append(finalChars[i]);
                }
            }
            return new Normalized(finalChars, Arrays.copyOf(rawIndex, count), finalStart, finalEnd,
                    Arrays.copyOf(joinBlocked, count), apostrophe, Arrays.copyOf(prefix, count),
                    Arrays.copyOf(leet, count), collapsed.toString());
        }

        /**
         * Puts the digits back in every all-digit token, except a single digit
         * among spelled-out letters, which is how "5 h 1 t", "r e 7 ard" and
         * "sl u 7" are typed: one beside a one-character token. A digit right
         * after a one-letter word is a quantity, and stays one, when a word of
         * three letters or more comes before that letter or the message starts
         * with it: "put a 5 tick delay", "a 5 hit combo".
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
            // unless spelled letters follow it ("is a 5 h i t").
            boolean afterLetterWord = before == 1 && digits[begin - 1] == 0
                    && ONE_LETTER_WORDS.contains(chars[begin - 1]);
            boolean quantity = afterLetterWord && after < 2 && (begin - 1 == 0 || wordEndsAt(begin - 2, starts));
            return !quantity;
        }

        /**
         * Makes a lone digit after a piece that is no English word part of that
         * piece: "shi 7" reads "shit", while "took 5 hits" keeps a number, and in
         * "lvl 5 hit" the digit joins "lvl" and never starts a word. A joined
         * piece is never an everyday word, so it only matters inside a banned one.
         */
        private void joinTrailingDigits(boolean[] starts, boolean[] ends) {
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
                if (letters && !BundledWordPacks.everydayWords(BundledWordPacks.ENGLISH)
                        .contains(new String(chars, begin, d - begin))) {
                    starts[d] = false;
                    tokenStart[d] = false;
                    ends[d - 1] = false;
                }
            }
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
}
