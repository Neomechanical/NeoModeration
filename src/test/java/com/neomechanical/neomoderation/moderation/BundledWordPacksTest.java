package com.neomechanical.neomoderation.moderation;

import com.neomechanical.neomoderation.config.OfflineModerationSettings;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BundledWordPacksTest {

    private static OfflineModerationSettings settingsFor(List<String> words) {
        return new OfflineModerationSettings(
                true, false, true, words, List.of(), List.of(), List.of());
    }

    @Test
    void starterWordsForBundledLocalesAreCuratedAndMeetLengthRequirements() {
        List<String> locales = List.of("en_US", "es_ES", "fr_FR", "de_DE", "pt_BR", "tr_TR");

        for (String locale : locales) {
            List<String> words = BundledWordPacks.starterWordsForLocale(locale);
            assertFalse(words.isEmpty(), "Starter words must not be empty for " + locale);
            assertTrue(words.size() >= 20 && words.size() <= 35,
                    "Expected 20-35 curated words for " + locale + ", but found " + words.size());

            for (String word : words) {
                assertTrue(word.length() >= 4,
                        "Starter word '" + word + "' in " + locale + " must be at least 4 characters long to avoid false positives");
                assertEquals(word.toLowerCase(Locale.ROOT), word,
                        "Starter word '" + word + "' in " + locale + " must be lowercase");
            }

            assertThrows(UnsupportedOperationException.class, () -> words.add("extra"),
                    "Starter words list must be unmodifiable for " + locale);
        }
    }

    @Test
    void starterWordsResolvesShortAndVariantCodes() {
        List<String> esExpected = BundledWordPacks.starterWordsForLocale("es_ES");
        assertEquals(esExpected, BundledWordPacks.starterWordsForLocale("es"));
        assertEquals(esExpected, BundledWordPacks.starterWordsForLocale("es-ES"));
        assertEquals(esExpected, BundledWordPacks.starterWordsForLocale("es_mx"));

        List<String> ptExpected = BundledWordPacks.starterWordsForLocale("pt_BR");
        assertEquals(ptExpected, BundledWordPacks.starterWordsForLocale("pt-br"));
        assertEquals(ptExpected, BundledWordPacks.starterWordsForLocale("pt"));
        assertEquals(ptExpected, BundledWordPacks.starterWordsForLocale("pt_pt"));

        List<String> frExpected = BundledWordPacks.starterWordsForLocale("fr_FR");
        assertEquals(frExpected, BundledWordPacks.starterWordsForLocale("fr"));
        assertEquals(frExpected, BundledWordPacks.starterWordsForLocale("fr-ca"));

        List<String> deExpected = BundledWordPacks.starterWordsForLocale("de_DE");
        assertEquals(deExpected, BundledWordPacks.starterWordsForLocale("de"));
        assertEquals(deExpected, BundledWordPacks.starterWordsForLocale("de-AT"));

        List<String> trExpected = BundledWordPacks.starterWordsForLocale("tr_TR");
        assertEquals(trExpected, BundledWordPacks.starterWordsForLocale("tr"));
        assertEquals(trExpected, BundledWordPacks.starterWordsForLocale("tr-TR"));

        List<String> enExpected = BundledWordPacks.starterWordsForLocale("en_US");
        assertEquals(enExpected, BundledWordPacks.starterWordsForLocale("en"));
        assertEquals(enExpected, BundledWordPacks.starterWordsForLocale(null));
        assertEquals(enExpected, BundledWordPacks.starterWordsForLocale(""));

        assertTrue(BundledWordPacks.starterWordsForLocale("ja_JP").isEmpty(),
                "Unknown locale should return an empty list");
    }

    @Test
    void mergeWithLocalePackRespectsOperatorEmptyingAndDisabledFlag() {
        List<String> initial = List.of("customword", "scam");

        // Disabled includeLocalePack flag
        List<String> flagDisabled = BundledWordPacks.mergeWithLocalePack(initial, "es_ES", false);
        assertEquals(initial, flagDisabled);

        // Null configured words
        assertEquals(List.of(), BundledWordPacks.mergeWithLocalePack(null, "es_ES", true));

        // Operator deliberately emptied the list -> must return empty list without adding defaults
        List<String> operatorEmpty = BundledWordPacks.mergeWithLocalePack(List.of(), "es_ES", true);
        assertTrue(operatorEmpty.isEmpty(),
                "Operator intentionally emptied word list; mergeWithLocalePack must not force starter words");

        // Successful merge with custom word
        List<String> merged = BundledWordPacks.mergeWithLocalePack(List.of("badword"), "es_ES", true);
        assertEquals("badword", merged.get(0));
        assertTrue(merged.contains("mierda"));
        assertTrue(merged.contains("cabron"));
        assertEquals(merged.size(), List.copyOf(merged).size());

        // Deduplication
        List<String> duplicated = BundledWordPacks.mergeWithLocalePack(List.of("mierda", "custom"), "es_ES", true);
        assertEquals(1, duplicated.stream().filter(w -> w.equals("mierda")).count());

        // Unmodifiable
        assertThrows(UnsupportedOperationException.class, () -> merged.add("another"));
    }

    @Test
    void offlineModerationEngineCatchesObfuscatedNonEnglishWordsWhenMerged() {
        // Spanish
        List<String> esWords = BundledWordPacks.mergeWithLocalePack(List.of("badword"), "es_ES", true);
        OfflineModerationSettings esSettings = settingsFor(esWords);
        assertTrue(OfflineModerationEngine.evaluate("m i e r d a", esSettings).flagged());
        assertTrue(OfflineModerationEngine.evaluate("eres un c.a.b.r.o.n", esSettings).flagged());
        assertTrue(OfflineModerationEngine.evaluate("que pendejo eres", esSettings).flagged());
        assertFalse(OfflineModerationEngine.evaluate("vamos a la mina a buscar diamantes", esSettings).flagged());

        // French
        List<String> frWords = BundledWordPacks.mergeWithLocalePack(List.of("badword"), "fr_FR", true);
        OfflineModerationSettings frSettings = settingsFor(frWords);
        assertTrue(OfflineModerationEngine.evaluate("c'est de la m e r d e", frSettings).flagged());
        assertTrue(OfflineModerationEngine.evaluate("quel p u t a i n de jeu", frSettings).flagged());
        assertTrue(OfflineModerationEngine.evaluate("sale c o n n a r d", frSettings).flagged());
        assertFalse(OfflineModerationEngine.evaluate("bonjour tout le monde", frSettings).flagged());

        // German
        List<String> deWords = BundledWordPacks.mergeWithLocalePack(List.of("badword"), "de_DE", true);
        OfflineModerationSettings deSettings = settingsFor(deWords);
        assertTrue(OfflineModerationEngine.evaluate("du a r s c h l o c h", deSettings).flagged());
        assertTrue(OfflineModerationEngine.evaluate("du s c h l a m p e", deSettings).flagged());
        assertFalse(OfflineModerationEngine.evaluate("hallo zusammen wie gehts", deSettings).flagged());

        // Portuguese
        List<String> ptWords = BundledWordPacks.mergeWithLocalePack(List.of("badword"), "pt_BR", true);
        OfflineModerationSettings ptSettings = settingsFor(ptWords);
        assertTrue(OfflineModerationEngine.evaluate("que c a r a l h o", ptSettings).flagged());
        assertTrue(OfflineModerationEngine.evaluate("seu v i a d o", ptSettings).flagged());
        assertFalse(OfflineModerationEngine.evaluate("ola galera vamos jogar", ptSettings).flagged());

        // Turkish
        List<String> trWords = BundledWordPacks.mergeWithLocalePack(List.of("badword"), "tr_TR", true);
        OfflineModerationSettings trSettings = settingsFor(trWords);
        assertTrue(OfflineModerationEngine.evaluate("sen o r o s p u", trSettings).flagged());
        assertTrue(OfflineModerationEngine.evaluate("y a r r a k kafali", trSettings).flagged());
        assertFalse(OfflineModerationEngine.evaluate("merhaba arkadaslar nasilsiniz", trSettings).flagged());
    }
}
