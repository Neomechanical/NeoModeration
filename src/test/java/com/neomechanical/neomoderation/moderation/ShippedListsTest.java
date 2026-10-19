package com.neomechanical.neomoderation.moderation;

import com.neomechanical.neomoderation.config.BukkitConfigView;
import com.neomechanical.neomoderation.config.OfflineModerationSettings;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The lists a server actually ships with, on everyday chat in six languages: the
 * English list in config.yml, every language pack and server-ad blocking at once.
 * A filter that blocks "put a torch here" gets uninstalled, so every ordinary
 * line must pass, while abuse spelled around the filter is still caught.
 */
class ShippedListsTest {

    private static OfflineModerationSettings shipped(List<String> packs) throws Exception {
        return shipped(packs, "en_US");
    }

    private static OfflineModerationSettings shipped(List<String> packs, String locale) throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(Files.readString(Path.of("src/main/resources/config.yml")));
        config.set("moderation.offline.languagePacks", packs);
        config.set("locale", locale);
        return OfflineModerationSettings.from(new BukkitConfigView(config));
    }

    /** A chat line and the game language of the player who wrote it, from a "# language: xx_yy" section. */
    private record Line(String text, String speaker) {
    }

    private static List<Line> lines(String resource) throws IOException {
        List<Line> lines = new ArrayList<>();
        String speaker = null;
        for (String line : Files.readAllLines(Path.of("src/test/resources/chat", resource), StandardCharsets.UTF_8)) {
            if (line.startsWith("# language:")) {
                speaker = line.substring("# language:".length()).strip();
            } else if (!line.isBlank() && !line.startsWith("#")) {
                lines.add(new Line(line.strip(), speaker));
            }
        }
        assertFalse(lines.isEmpty(), resource + " is empty");
        return lines;
    }

    /** Every banned word that ships: the English list and every language pack. */
    private static List<String> shippedWords() throws Exception {
        return shipped(List.of("all")).effectiveWords();
    }

    @Test
    void everydayChatInSixLanguagesPassesEveryShippedList() throws Exception {
        OfflineModerationSettings settings = shipped(List.of("all"));
        List<String> blocked = new ArrayList<>();
        for (Line line : lines("everyday-clean.txt")) {
            OfflineModerationResult result = OfflineModerationEngine.evaluate(line.text(), settings, line.speaker());
            if (result.flagged()) {
                blocked.add(line.text() + "  ->  " + result.reason());
            }
        }
        assertTrue(blocked.isEmpty(), () -> "Ordinary chat was blocked:\n" + String.join("\n", blocked));
    }

    @Test
    void everydayChatSentAsQuickShortMessagesIsNotJoinedIntoAWord() throws Exception {
        OfflineModerationSettings settings = shipped(List.of("all"));
        SplitMessageWindow window = new SplitMessageWindow();
        List<String> blocked = new ArrayList<>();
        for (Line line : lines("everyday-clean.txt")) {
            UUID player = UUID.randomUUID();
            long now = 1_000_000L;
            for (String word : line.text().split(" ")) {
                now += 500;
                if (OfflineModerationEngine.evaluate(word, settings, line.speaker()).flagged()) {
                    continue;
                }
                window.recordAndCheck(player, word, settings, line.speaker(), now)
                        .ifPresent(reason -> blocked.add(line.text() + "  ->  " + reason));
            }
        }
        assertTrue(blocked.isEmpty(), () -> "Ordinary chat sent word by word was blocked:\n" + String.join("\n", blocked));
    }

    @Test
    void abuseSpelledAroundTheFilterIsStillCaught() throws Exception {
        OfflineModerationSettings settings = shipped(List.of("all"));
        List<String> missed = new ArrayList<>();
        for (Line line : lines("must-catch.txt")) {
            if (!OfflineModerationEngine.evaluate(line.text(), settings, line.speaker()).flagged()) {
                missed.add(line.text());
            }
        }
        assertTrue(missed.isEmpty(), () -> "Abuse got through:\n" + String.join("\n", missed));
    }

    /**
     * Every banned word that everyday words of one language spell, written
     * "language: pieces = word", including spellings that stretch a letter
     * across the gap ("put to" for "puto"). These pass for players who write
     * that language (English everywhere), so a split that is not ordinary chat
     * in that language is an evasion: adding an everyday word that makes a new
     * one fails here until someone decides whether players of that language say
     * it. A word that is never innocent is listed with its space instead
     * ("pu tain", "mari cones").
     */
    private static final Set<String> KNOWN_EVERYDAY_SPELLINGS = Set.of(
            "en: bad word = badword",
            "en: go ok = gook",
            "en: gg go ok = gook",
            "en: put a = puta",
            "en: put as = putas",
            "en: put a as = putas",
            "en: put to = puto",
            "es: mari con = maricon",
            "es: ni que = nique",
            "es: vi a dos = viados",
            "fr: mer de = merde",
            "fr: mer des = merdes",
            "fr: mer de es = merdes",
            "fr: pu te = pute",
            "fr: en foire = enfoire",
            "de: jo der = joder",
            "de: jo oder = joder",
            "da: jo der = joder",
            "da: jo de er = joder",
            "da: jo dette = jodete",
            "pt: via do = viado",
            "pt: via dos = viados",
            "pt: via do os = viados",
            "pt: via a do = viado",
            "pt: via a dos = viados",
            "pt: vi a do = viado",
            "pt: vi a dos = viados",
            "sv: hur en = huren"
    );

    /**
     * Every way to cut {@code letters} into two or three pieces, also with one
     * run of a letter stretched by one or two ("putto", "kikkke"), which the
     * matcher reads as the same word.
     */
    private static List<String[]> splits(String letters) {
        Set<String> spellings = new java.util.LinkedHashSet<>();
        spellings.add(letters);
        for (int at = 0; at < letters.length(); at++) {
            if (at > 0 && letters.charAt(at) == letters.charAt(at - 1)) {
                continue;
            }
            char letter = letters.charAt(at);
            spellings.add(letters.substring(0, at) + letter + letters.substring(at));
            spellings.add(letters.substring(0, at) + letter + letter + letters.substring(at));
        }
        List<String[]> splits = new ArrayList<>();
        for (String spelling : spellings) {
            for (int first = 1; first < spelling.length(); first++) {
                addUnlessTrailingStretch(splits, spelling.substring(0, first), spelling.substring(first));
                for (int second = first + 1; second < spelling.length(); second++) {
                    addUnlessTrailingStretch(splits, spelling.substring(0, first), spelling.substring(first, second),
                            spelling.substring(second));
                }
            }
        }
        return splits;
    }

    /**
     * Adds a split, unless its last piece only repeats the letter before it
     * ("mer de e"): the matcher ends a word at the first place it can, so that
     * reads as "mer de" and a stray "e".
     */
    private static void addUnlessTrailingStretch(List<String[]> splits, String... pieces) {
        String last = pieces[pieces.length - 1];
        char before = pieces[pieces.length - 2].charAt(pieces[pieces.length - 2].length() - 1);
        if (last.chars().allMatch(letter -> letter == before)) {
            return;
        }
        splits.add(pieces);
    }

    /** The shipped words a player cannot write without a space: those written with one are read across every space. */
    private static List<String> unspacedShippedWords() throws Exception {
        return shippedWords().stream().filter(word -> !word.contains(" ")).toList();
    }

    @Test
    void aBannedWordSplitIntoEverydayWordsOfDifferentLanguagesIsCaught() throws Exception {
        OfflineModerationSettings settings = shipped(List.of("all"));
        Map<String, Set<String>> everyday = BundledWordPacks.everydayWords();
        List<String> missed = new ArrayList<>();
        int lines = 0;
        for (String word : unspacedShippedWords()) {
            String letters = new String(TextNormalizer.normalize(word, false).chars());
            for (String[] pieces : splits(letters)) {
                boolean allWords = Arrays.stream(pieces).allMatch(piece ->
                        everyday.values().stream().anyMatch(words -> words.contains(piece)));
                boolean oneLanguage = everyday.values().stream().anyMatch(words ->
                        Arrays.stream(pieces).allMatch(words::contains));
                if (!allWords || oneLanguage) {
                    continue;
                }
                String line = "you " + String.join(" ", pieces);
                for (String language : everyday.keySet()) {
                    String speaker = language + "_" + language;
                    if (BundledWordPacks.languageWords(speaker).contains(word)) {
                        continue;
                    }
                    lines++;
                    if (!OfflineModerationEngine.evaluate(line, settings, speaker).flagged()) {
                        missed.add(line + "  (" + speaker + ")");
                    }
                }
            }
        }
        assertTrue(lines > 100, "checked only " + lines + " lines; are the everyday words loaded?");
        assertTrue(missed.isEmpty(), () -> "Split words got through:\n" + String.join("\n", missed));
    }

    @Test
    void everydayWordsOfOneLanguageSpellOnlyTheBannedWordsWeKnowAbout() throws Exception {
        OfflineModerationSettings settings = shipped(List.of("all"));
        Set<String> ownList = new java.util.HashSet<>(settings.bannedWords());
        Set<String> found = new TreeSet<>();
        List<String> wrong = new ArrayList<>();
        for (String word : unspacedShippedWords()) {
            String letters = new String(TextNormalizer.normalize(word, false).chars());
            for (String[] pieces : splits(letters)) {
                BundledWordPacks.everydayWords().forEach((language, words) -> {
                    if (!Arrays.stream(pieces).allMatch(words::contains)) {
                        return;
                    }
                    String spelling = language + ": " + String.join(" ", pieces) + " = " + word;
                    if (!found.add(spelling)) {
                        return;
                    }
                    String line = "well " + String.join(" ", pieces) + " then";
                    String speaker = language + "_" + language;
                    boolean english = Arrays.stream(pieces).allMatch(BundledWordPacks.everydayWords("en")::contains);
                    if (!english && ownList.contains(word)) {
                        // A word on the server's own list takes no excuse from a player's game language.
                        if (!OfflineModerationEngine.evaluate(line, settings, speaker).flagged()) {
                            wrong.add(spelling + ": passed on an English server for a player of that language");
                        }
                        return;
                    }
                    if (OfflineModerationEngine.evaluate(line, settings, speaker).flagged()) {
                        wrong.add(spelling + ": blocked for a player of that language");
                    }
                    if (!english && !OfflineModerationEngine.evaluate(line, settings, "en_us").flagged()) {
                        wrong.add(spelling + ": passed for an English player on an English server");
                    }
                });
            }
        }
        Set<String> unknown = new TreeSet<>(found);
        unknown.removeAll(KNOWN_EVERYDAY_SPELLINGS);
        Set<String> gone = new TreeSet<>(KNOWN_EVERYDAY_SPELLINGS);
        gone.removeAll(found);
        // Own-list words with non-English pieces are caught everywhere but on that language's servers.
        unknown.removeIf(spelling -> !spelling.startsWith("en: ")
                && ownList.contains(spelling.substring(spelling.indexOf(" = ") + 3)));
        assertTrue(unknown.isEmpty(), () -> "Everyday words now spell more banned words. For each, decide whether "
                + "players of that language say it and list it here, or drop the word from common-words.txt:\n"
                + String.join("\n", unknown));
        assertTrue(gone.isEmpty(), () -> "Listed here but no longer spelled by everyday words:\n" + String.join("\n", gone));
        assertTrue(wrong.isEmpty(), () -> String.join("\n", wrong));
    }

    @Test
    void everyShippedWordSpelledOutLetterByLetterIsCaught() throws Exception {
        OfflineModerationSettings settings = shipped(List.of("all"));
        List<String> missed = new ArrayList<>();
        for (String word : shippedWords()) {
            String letters = new String(TextNormalizer.normalize(word, false).chars());
            String spelled = String.join(" ", letters.split(""));
            if (!OfflineModerationEngine.evaluate("you " + spelled, settings).flagged()) {
                missed.add(spelled);
            }
        }
        assertTrue(missed.isEmpty(), () -> "Spelled-out words got through:\n" + String.join("\n", missed));
    }

    @Test
    void aWordOneLanguageUsesEveryDayPassesForItsSpeakersAndOnItsServers() throws Exception {
        OfflineModerationSettings english = shipped(List.of());
        assertFalse(OfflineModerationEngine.evaluate("hola kike que tal", english, "es_mx").flagged());
        assertTrue(OfflineModerationEngine.evaluate("hey kike", english, "en_us").flagged(),
                "an English speaker on an English server is still blocked");
        assertFalse(OfflineModerationEngine.evaluate("Kike es mi hermano", shipped(List.of(), "es_ES"), "en_us").flagged(),
                "a Spanish server's own language counts for everyone on it");
        assertFalse(OfflineModerationEngine.evaluate("quel retard ce train", english, "fr_ca").flagged());
        assertTrue(OfflineModerationEngine.evaluate("you are a retard", english, "en_gb").flagged());
        assertFalse(OfflineModerationEngine.evaluate("det är slut nu", english, "sv_se").flagged());
        assertFalse(OfflineModerationEngine.evaluate("hvilket fag har du", english, "da_dk").flagged());
        OfflineModerationSettings portuguese = shipped(List.of("pt"));
        assertFalse(OfflineModerationEngine.evaluate("os putos estão a jogar", portuguese, "pt_pt").flagged());
        assertTrue(OfflineModerationEngine.evaluate("seu puto", portuguese, "pt_br").flagged(),
                "Brazilian Portuguese keeps the word banned");
    }

    @Test
    void aGameLanguageAloneDoesNotLetTheWordThroughInAnotherLanguage() throws Exception {
        OfflineModerationSettings english = shipped(List.of());
        assertTrue(OfflineModerationEngine.evaluate("you are a kike", english, "es_mx").flagged());
        assertTrue(OfflineModerationEngine.evaluate("shut up retard", english, "fr_fr").flagged());
        assertTrue(OfflineModerationEngine.evaluate("what a slut", english, "sv_se").flagged());
        assertTrue(OfflineModerationEngine.evaluate("you fag", english, "da_dk").flagged());
        assertTrue(OfflineModerationEngine.evaluate("kike", english, "es_es").flagged(),
                "one word on its own reads as no language");
        // One cheap word of the language is not a message written in it.
        for (String[] line : new String[][]{
                {"you kike e", "es_mx"}, {"dirty kike xd", "es_mx"}, {"shut up kike que", "es_es"},
                {"you are a kike de", "es_es"}, {"you retard y", "fr_fr"}, {"shut up retard le", "fr_fr"},
                {"you slut pa", "sv_se"}, {"what a slut och", "sv_se"}, {"you fag er", "da_dk"},
                {"shut up fag og", "nb_no"}, {"you verga e", "it_it"}}) {
            assertTrue(OfflineModerationEngine.evaluate(line[0], shipped(List.of("all")), line[1]).flagged(),
                    line[0] + " (" + line[1] + ")");
        }
    }

    @Test
    void aWordOnTheServersOwnListTakesNoExcuseFromAPlayersGameLanguage() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.set("moderation.offline.bannedWords", List.of("joder"));
        config.set("moderation.offline.languagePacks", List.of());
        OfflineModerationSettings ownList = OfflineModerationSettings.from(new BukkitConfigView(config));
        assertTrue(OfflineModerationEngine.evaluate("jo der ist gut", ownList, "de_de").flagged(),
                "a German game does not make the server's own word German chat");
        config.set("locale", "de_DE");
        assertFalse(OfflineModerationEngine.evaluate("jo der ist gut", OfflineModerationSettings.from(
                new BukkitConfigView(config)), "en_us").flagged(), "on a German server it is German chat");

        OfflineModerationSettings pack = shipped(List.of("es"));
        assertFalse(OfflineModerationEngine.evaluate("jo der ist gut", pack, "de_de").flagged(),
                "the Spanish pack's word passes in a German player's German");
    }

    @Test
    void aWordWrittenWithASpaceIsCaughtHoweverItIsSpaced() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.set("moderation.offline.bannedWords", List.of("go ok"));
        OfflineModerationSettings settings = OfflineModerationSettings.from(new BukkitConfigView(config));

        assertTrue(OfflineModerationEngine.evaluate("go ok", settings).flagged());
        assertTrue(OfflineModerationEngine.evaluate("gook", settings).flagged());
        assertTrue(OfflineModerationEngine.evaluate("g o o k", settings).flagged());
    }
}
