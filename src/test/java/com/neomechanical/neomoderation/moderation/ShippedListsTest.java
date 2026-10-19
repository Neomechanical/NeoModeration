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
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(Files.readString(Path.of("src/main/resources/config.yml")));
        config.set("moderation.offline.languagePacks", packs);
        return OfflineModerationSettings.from(new BukkitConfigView(config));
    }

    private static List<String> lines(String resource) throws IOException {
        List<String> lines = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of("src/test/resources/chat", resource), StandardCharsets.UTF_8)) {
            if (!line.isBlank() && !line.startsWith("#")) {
                lines.add(line.strip());
            }
        }
        assertFalse(lines.isEmpty(), resource + " is empty");
        return lines;
    }

    @Test
    void everydayChatInSixLanguagesPassesEveryShippedList() throws Exception {
        OfflineModerationSettings settings = shipped(List.of("all"));
        List<String> blocked = new ArrayList<>();
        for (String line : lines("everyday-clean.txt")) {
            OfflineModerationResult result = OfflineModerationEngine.evaluate(line, settings);
            if (result.flagged()) {
                blocked.add(line + "  ->  " + result.reason());
            }
        }
        assertTrue(blocked.isEmpty(), () -> "Ordinary chat was blocked:\n" + String.join("\n", blocked));
    }

    @Test
    void everydayChatSentAsQuickShortMessagesIsNotJoinedIntoAWord() throws Exception {
        OfflineModerationSettings settings = shipped(List.of("all"));
        SplitMessageWindow window = new SplitMessageWindow();
        List<String> blocked = new ArrayList<>();
        for (String line : lines("everyday-clean.txt")) {
            UUID player = UUID.randomUUID();
            long now = 1_000_000L;
            for (String word : line.split(" ")) {
                now += 500;
                window.recordAndCheck(player, word, settings, now).ifPresent(reason -> blocked.add(line + "  ->  " + reason));
            }
        }
        assertTrue(blocked.isEmpty(), () -> "Ordinary chat sent word by word was blocked:\n" + String.join("\n", blocked));
    }

    @Test
    void abuseSpelledAroundTheFilterIsStillCaught() throws Exception {
        OfflineModerationSettings settings = shipped(List.of("all"));
        List<String> missed = new ArrayList<>();
        for (String line : lines("must-catch.txt")) {
            if (!OfflineModerationEngine.evaluate(line, settings).flagged()) {
                missed.add(line);
            }
        }
        assertTrue(missed.isEmpty(), () -> "Abuse got through:\n" + String.join("\n", missed));
    }

    @Test
    void aSpanishNameIsOnlyAllowedWhereTheSpanishPackIsOn() throws Exception {
        assertFalse(OfflineModerationEngine.evaluate("hola kike que tal", shipped(List.of("es"))).flagged());
        assertTrue(OfflineModerationEngine.evaluate("hey kike", shipped(List.of())).flagged(),
                "an English server still blocks the slur");
    }
}
