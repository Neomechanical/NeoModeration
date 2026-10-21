package com.neomechanical.neomoderation.moderation;

import java.text.Normalizer;
import java.util.HashMap;
import java.util.Map;

/**
 * How the filter reads one character of chat: a look-alike from another script
 * as the Latin letter it is drawn as, a digit or symbol typed for a letter as
 * that letter, and the marks typed inside a word (apostrophes, dashes,
 * underscores, invisible fillers) as joining or splitting it.
 * {@link TextNormalizer} reads a message with these.
 */
final class Glyphs {

    /**
     * Latin lookalikes from Cyrillic, Greek and Armenian. Restricted to
     * characters that are visually identical in the fonts Minecraft renders
     * chat with, so folding them cannot change the reading of genuine
     * non-Latin chat: a real Cyrillic word is not spelled entirely from Latin
     * lookalikes, and a mixed-script token is the signature of the evasion.
     * Turkish dotless {@code ı} folds to {@code i} so the Turkish starter list,
     * written without it, matches what Turkish players type.
     */
    private static final Map<Character, Character> CONFUSABLES;

    static {
        Map<Character, Character> confusables = new HashMap<>();
        put(confusables, "авсеһкморѕтхуүіјӏԛ", "abcehkmopstxyyijlq");  // Cyrillic
        put(confusables, "αβεζηικνορτυχγ", "abeznikvoptuxy");  // Greek
        put(confusables, "օոսցհԁԝѡıƒɡɑ", "onughdwwifga");  // Armenian and others
        // Small capitals, as in "ɴɪɢɢᴇʀ".
        put(confusables, "ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘʀꜱᴛᴜᴠᴡʏᴢ", "abcdefghijklmnoprstuvwyz");
        CONFUSABLES = Map.copyOf(confusables);
    }

    /** Dashes, dots and quotation marks typed inside a word: – — ― − ‒ · ‧ • ‚ „ “ ” ‟ ‹ › « » ‴ ‶ ״ ¨ ῭ ΅ ῀ ῁ `. */
    private static final String JOINER_MARKS = "\u2013\u2014\u2015\u2212\u2012\u00B7\u2027\u2022\u201A\u201E"
            + "\u201C\u201D\u201F\u2039\u203A\u00AB\u00BB\u2034\u2036\u05F4\u00A8\u1FED\u0385\u1FC0\u1FC1"
            + "\u1FEF";

    private Glyphs() {
    }

    private static void put(Map<Character, Character> confusables, String from, String to) {
        for (int i = 0; i < from.length() && i < to.length(); i++) {
            confusables.put(from.charAt(i), to.charAt(i));
        }
    }

    /** The Latin letter {@code c} is drawn as, or {@code c} itself. */
    static char fold(char c) {
        return CONFUSABLES.getOrDefault(c, c);
    }

    /** The letter a digit or symbol stands for in leetspeak ("4" for "a", "$" for "s"), or {@code c} itself. */
    static char leetLetter(char c) {
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

    static boolean isHyphen(int codePoint) {
        return codePoint == '-' || codePoint == '\u2010' || codePoint == '\u2011';
    }

    /**
     * Punctuation that joins two pieces of one word when typed between letters:
     * "put_a", "go-ok" and "go–ok" are read like "put'a", never as everyday words.
     * Fullwidth forms count as the marks they are drawn as ("put＿a").
     */
    static boolean isJoiner(int codePoint) {
        if (Character.isLetterOrDigit(codePoint) || Character.isWhitespace(codePoint)) {
            return false;
        }
        int mark = codePoint;
        if (codePoint >= 0x80) {
            String folded = Normalizer.normalize(new String(Character.toChars(codePoint)), Normalizer.Form.NFKC);
            if (folded.codePointCount(0, folded.length()) == 1) {
                mark = folded.codePointAt(0);
            }
        }
        return JOINER_MARKS.indexOf(codePoint) >= 0 || isHyphen(mark) || "_*~+/".indexOf(mark) >= 0
                || JOINER_MARKS.indexOf(mark) >= 0;
    }

    /**
     * Whether the next character a reader sees after {@code index} is a letter,
     * past invisible characters, accents and more joining marks: "go--ok", and
     * "go-ok" with a zero-width space after the hyphen, join "go" and "ok".
     */
    static boolean nextVisibleIsLetter(String value, int index) {
        for (int i = index; i < value.length(); ) {
            int codePoint = value.codePointAt(i);
            int type = Character.getType(codePoint);
            if (type == Character.FORMAT || Character.isIdentifierIgnorable(codePoint)
                    || type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK
                    || isApostrophe(codePoint) || isJoiner(codePoint)) {
                i += Character.charCount(codePoint);
                continue;
            }
            return Character.isLetter(codePoint);
        }
        return false;
    }

    static boolean isApostrophe(int codePoint) {
        return codePoint == '\'' || codePoint == '’' || codePoint == '‘'
                || codePoint == 'ʼ' || codePoint == '`'
                // Marks typed or drawn as an apostrophe: ´ ′ ‵ ＇ ׳ ՚ ՙ ⸯ ॱ, the Greek ´ ΄ ʹ ᾿ ῾,
                // ｀ ‛ ″ ❛ ❜ and ՛.
                || codePoint == 0x00B4 || codePoint == 0x2032 || codePoint == 0x2035 || codePoint == 0xFF07
                || codePoint == 0x05F3 || codePoint == 0x055A || codePoint == 0x0559 || codePoint == 0x2E2F
                || codePoint == 0x0971 || codePoint == 0x1FFD || codePoint == 0x0384 || codePoint == 0x0374
                || codePoint == 0x1FBF || codePoint == 0x1FFE || codePoint == 0xFF40 || codePoint == 0x201B
                || codePoint == 0x2033 || codePoint == 0x275B || codePoint == 0x275C || codePoint == 0x055B
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
    static boolean isBlankFiller(int codePoint) {
        return codePoint == 0x115F || codePoint == 0x1160 || codePoint == 0x3164 || codePoint == 0xFFA0;
    }

    static boolean isBetweenWordCharacters(String value, int index) {
        if (index <= 0 || index >= value.length() - 1) {
            return false;
        }
        return Character.isLetterOrDigit(value.charAt(index - 1))
                && Character.isLetterOrDigit(value.charAt(index + 1));
    }
}
