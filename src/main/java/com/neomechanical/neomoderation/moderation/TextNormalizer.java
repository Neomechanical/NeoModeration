package com.neomechanical.neomoderation.moderation;

import java.text.Normalizer;

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
 * read on its own; "_", "-", "*", "~", "+" and "/" between letters join pieces
 * the way an apostrophe does ("put_a"), without making a contraction. A token
 * made only of digits is a number, so "5 hits" and "455 diamonds" keep their
 * digits; a lone digit among spelled-out letters such as {@code 5 h 1 t} or
 * {@code sl u 7} still reads as a letter, but not a quantity after a one-letter
 * word that follows a word ("put a 5 tick delay"). A lone digit after a piece
 * of letters reads as that piece's last letter ("shi 7") and never starts a
 * match; {@link EverydayWords#endsInCount} drops a match it ends when the digit
 * is a number after all ("put 4 torches", "pak 1 diamant").</p>
 */
final class TextNormalizer {

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
     *                     a word, contraction or not ("f'uck", "bad'word"), or after
     *                     "_", "-", "*", "~", "+" or "/" between letters ("put_a")
     * @param prefix       whether each char began a "re" written before a hyphen
     *                     ("re-did"), which is an English prefix rather than a word piece
     * @param noStart      whether each char is a lone digit read as the last letter of
     *                     the piece before it ("shi 7"); a match may end there, never start
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
            boolean[] noStart,
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
        NormalizedBuilder out = new NormalizedBuilder(value.length());
        boolean atTokenStart = true;
        boolean afterInWordApostrophe = false;
        boolean afterInWordJoiner = false;

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
            if (Glyphs.isBlankFiller(codePoint)) {
                // Drawn as nothing, so it reads as the space it stands for: "putㅤa" is "put a".
                atTokenStart = true;
                afterInWordApostrophe = false;
                afterInWordJoiner = false;
                continue;
            }
            if (Glyphs.isHyphen(codePoint) && i < value.length() && Character.isLetter(value.codePointAt(i))
                    && out.endsWithWord("re")) {
                out.markPrefix(2);  // "re-did": a prefix, and "did" is read on its own
            }
            if (Glyphs.isApostrophe(codePoint) || Glyphs.isJoiner(codePoint)) {
                // The first mark of a run ("--", "'_") decides, by the next character a reader sees.
                if (!atTokenStart) {
                    boolean inWord = Glyphs.nextVisibleIsLetter(value, i);
                    // Only an apostrophe makes a contraction: "who-re" is no "who're".
                    afterInWordApostrophe = inWord && Glyphs.isApostrophe(codePoint);
                    afterInWordJoiner = inWord && !afterInWordApostrophe;
                }
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
                char c = Glyphs.fold(Character.toLowerCase(raw));
                char digit = c >= '0' && c <= '9' ? c : 0;
                boolean typedAsLetter = Character.isLetter(c);
                if (normalizeLeetspeak) {
                    // '!' is a leetspeak 'i' inside a word ("b!tch") but ordinary
                    // punctuation at a boundary; converting it unconditionally
                    // turned "scam!" into "scami", which no whole-word rule matched.
                    if (c != '!' || Glyphs.isBetweenWordCharacters(value, rawAt)) {
                        c = Glyphs.leetLetter(c);
                    }
                }
                if (c == 'ß') {
                    // German sharp s is spelled "ss" in every word list.
                    out.add('s', rawAt, atTokenStart, atTokenStart && afterInWordApostrophe, (char) 0, false);
                    if (atTokenStart && afterInWordJoiner) {
                        out.markJoined();
                    }
                    out.add('s', rawAt, false, false, (char) 0, false);
                    atTokenStart = false;
                    afterInWordApostrophe = false;
                    afterInWordJoiner = false;
                } else if (Character.isLetterOrDigit(c)) {
                    out.add(c, rawAt, atTokenStart, atTokenStart && afterInWordApostrophe, digit,
                            !typedAsLetter && Character.isLetter(c));
                    if (atTokenStart && afterInWordJoiner) {
                        out.markJoined();
                    }
                    atTokenStart = false;
                    afterInWordApostrophe = false;
                    afterInWordJoiner = false;
                } else {
                    atTokenStart = true;
                    afterInWordApostrophe = false;
                    afterInWordJoiner = false;
                }
            }
        }
        return out.build();
    }
}
