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
import java.util.List;
import java.util.Set;
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

    @Test
    void twoEverydayWordsThatSpellABannedWordAreNeverReadAsIt() throws Exception {
        OfflineModerationSettings settings = shipped(List.of("all"));
        Set<String> common = BundledWordPacks.commonWords();
        List<String> blocked = new ArrayList<>();
        int pairs = 0;
        for (String word : shippedWords()) {
            String letters = new String(TextNormalizer.normalize(word, false).chars());
            for (int split = 1; split < letters.length(); split++) {
                String left = letters.substring(0, split);
                String right = letters.substring(split);
                if (!common.contains(left) || !common.contains(right)) {
                    continue;
                }
                pairs++;
                String line = "well " + left + " " + right + " then";
                OfflineModerationResult result = OfflineModerationEngine.evaluate(line, settings);
                if (result.flagged()) {
                    blocked.add(line + "  ->  " + result.reason());
                }
            }
        }
        assertTrue(pairs > 10, "found only " + pairs + " word pairs; is the everyday list loaded?");
        assertTrue(blocked.isEmpty(), () -> "Two everyday words were read as one:\n" + String.join("\n", blocked));
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
        OfflineModerationSettings portuguese = shipped(List.of("pt"));
        assertFalse(OfflineModerationEngine.evaluate("os putos estão a jogar", portuguese, "pt_pt").flagged());
        assertTrue(OfflineModerationEngine.evaluate("seu puto", portuguese, "pt_br").flagged(),
                "Brazilian Portuguese keeps the word banned");
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
