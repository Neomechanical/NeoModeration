package com.neomechanical.neomoderation.moderation;

import com.neomechanical.neomoderation.config.MapConfigView;
import com.neomechanical.neomoderation.config.OfflineModerationSettings;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BundledWordPacksTest {

    private static OfflineModerationSettings withPacks(String... packs) {
        List<String> codes = List.of(packs);
        return new OfflineModerationSettings(true, false, true, List.of("badword"), List.of(), List.of(), List.of(),
                codes, BundledWordPacks.wordsOf(codes), false);
    }

    private static boolean flags(OfflineModerationSettings settings, String message) {
        return OfflineModerationEngine.evaluate(message, settings).flagged();
    }

    @Test
    void everyPackLoadsAsLowercaseAsciiWithoutDuplicates() {
        for (String code : BundledWordPacks.CODES) {
            List<String> words = BundledWordPacks.words(code);
            assertTrue(words.size() >= 20, code + " pack has only " + words.size() + " words");
            Set<String> seen = new HashSet<>();
            for (String word : words) {
                // A phrase keeps its single spaces ("va te faire foutre"), which the filter reads across.
                assertTrue(word.matches("[a-z]+( [a-z]+)*") && word.replace(" ", "").length() >= 3,
                        code + " pack entry '" + word + "' must be 3+ lowercase ASCII letters");
                assertTrue(seen.add(word), code + " pack repeats '" + word + "'");
            }
        }
        assertTrue(BundledWordPacks.words("en").isEmpty(), "English lives in config.yml's own list");
        assertTrue(BundledWordPacks.words("ja").isEmpty());
    }

    @Test
    void autoPicksTheLanguageOfTheLocale() {
        assertEquals(List.of("es"), BundledWordPacks.select(List.of("auto"), "es_ES", true));
        assertEquals(List.of("pt"), BundledWordPacks.select(List.of("auto"), "pt-br", true));
        assertEquals(List.of("de"), BundledWordPacks.select(List.of("auto"), "de_AT", true));
        assertEquals(List.of(), BundledWordPacks.select(List.of("auto"), "en_US", true));
        assertEquals(List.of(), BundledWordPacks.select(List.of("auto"), "es_ES", false),
                "an operator who emptied the word list has switched word filtering off");
    }

    @Test
    void namedPacksAndAllAddTheirListsWhateverTheLocale() {
        assertEquals(List.of("es", "tr"), BundledWordPacks.select(List.of("ES", "tr", "es", "xx"), "en_US", false));
        assertEquals(BundledWordPacks.CODES, BundledWordPacks.select(List.of("all"), "en_US", true));
        assertEquals(List.of("fr", "de"), BundledWordPacks.select(List.of("auto", "de"), "fr_FR", true));
    }

    @Test
    void settingsReadLanguagePacksFromConfig() {
        OfflineModerationSettings spanish = OfflineModerationSettings.from(new MapConfigView(Map.of(
                "locale", "es_ES",
                "moderation", Map.of("offline", Map.of("bannedWords", List.of("badword"))))));
        assertEquals(List.of("es"), spanish.languagePacks());
        assertTrue(spanish.effectiveWords().contains("mierda"));
        assertEquals(List.of("badword"), spanish.bannedWords(), "the configured list stays the operator's own");

        OfflineModerationSettings none = OfflineModerationSettings.from(new MapConfigView(Map.of(
                "locale", "es_ES",
                "moderation", Map.of("offline", Map.of("bannedWords", List.of("badword"), "languagePacks", List.of())))));
        assertTrue(none.languagePacks().isEmpty());
        assertEquals(List.of("badword"), none.effectiveWords());
    }

    @Test
    void packsCatchDisguisedWordsAndFoldLocalLetters() {
        OfflineModerationSettings es = withPacks("es");
        assertTrue(flags(es, "m i e r d a"));
        assertTrue(flags(es, "eres un c.a.b.r.o.n"));
        assertTrue(flags(es, "que cabrón eres"));
        assertTrue(flags(es, "hijo de puta"));
        assertTrue(flags(es, "joder tio"));

        OfflineModerationSettings fr = withPacks("fr");
        assertTrue(flags(fr, "c'est de la m e r d e"));
        assertTrue(flags(fr, "va te faire foutre"));
        assertTrue(flags(fr, "sale enculé"));

        OfflineModerationSettings de = withPacks("de");
        assertTrue(flags(de, "Scheiße, der Creeper"));
        assertTrue(flags(de, "du A r s c h l o c h"));
        assertTrue(flags(de, "ihr Arschlöcher"));

        OfflineModerationSettings pt = withPacks("pt");
        assertTrue(flags(pt, "que c a r a l h o"));
        assertTrue(flags(pt, "seu desgraçado"));
        assertTrue(flags(pt, "porra mano"));

        OfflineModerationSettings tr = withPacks("tr");
        assertTrue(flags(tr, "amına koyayım"));
        assertTrue(flags(tr, "amk ya"));
        assertTrue(flags(tr, "y a r r a k kafali"));
        assertTrue(flags(tr, "orospu çocuğu"));
    }

    @Test
    void packsLeaveOrdinaryChatInTheirLanguageAlone() {
        OfflineModerationSettings all = withPacks(BundledWordPacks.CODES.toArray(String[]::new));
        for (String clean : List.of(
                "vamos a la mina a buscar diamantes",
                "un cono de helado en la tienda",
                "quel bordel dans ma base",
                "c'est unique comme build",
                "je m'en fous de la pluie",
                "hallo zusammen wie gehts",
                "die kacke ist am dampfen ist kein Spiel",
                "ola galera vamos jogar",
                "merhaba arkadaslar nasilsiniz",
                "sık sık buraya geliyorum",
                "Amina is my friend",
                "send a pic of your base",
                "hi amina, want to trade?")) {
            assertFalse(flags(all, clean), "a language pack flagged ordinary chat: " + clean);
        }
    }

    @Test
    void aWordsSpeakersAndTheirSpellingAreFoundTheSameWayEveryRun() {
        // languages.txt lists Swedish and Danish "slut" before Norwegian "slutt", and Spanish "kike" before
        // Norwegian "kikke". With no text kept, the first listed spelling answers: before the lists kept the
        // file's order, which one came first changed from one start of the server to the next.
        assertEquals("slut", BundledWordPacks.nativeSpelling("sluut", null));
        assertEquals("kike", BundledWordPacks.nativeSpelling("kiike", null));
        // The text kept decides when there is one: "putte" for the banned "pute" written by a Danish player.
        assertEquals("slutt", BundledWordPacks.nativeSpelling("sluut", "det er slutt nå"));
        assertEquals(List.of("da", "nb", "nn", "no", "sv"), BundledWordPacks.languagesUsing("slut", null));
        assertEquals(List.of("da", "sv"), BundledWordPacks.languagesUsing("slut"));
        assertEquals(List.of(), BundledWordPacks.languagesUsing("sluut", "sluut"));
    }
}
