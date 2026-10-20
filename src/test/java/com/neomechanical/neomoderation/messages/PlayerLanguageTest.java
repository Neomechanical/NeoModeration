package com.neomechanical.neomoderation.messages;

import com.neomechanical.neomoderation.testing.TestPlugin;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class PlayerLanguageTest {
    private static final String EXPIRES = "2026-10-15T00:00:00Z";

    @TempDir
    Path folder;

    private String text(MessageService messages, CommandSender reader, String key) {
        return TestPlugin.plain(messages.text(reader, key));
    }

    @Test
    void eachPlayerReadsTheirOwnGameLanguageAndTheConsoleTheServers() {
        MessageService messages = TestPlugin.loaded(folder.toFile(), "en_US", true);

        assertEquals("[NeoMod] Die Moderation ist an.", text(messages, TestPlugin.player("Hans", "de_de"), "toggle.on"));
        assertEquals("[NeoMod] A moderação está ligada.", text(messages, TestPlugin.player("Ana", "pt_br"), "toggle.on"));
        assertEquals("[NeoMod] La moderación está activada.", text(messages, TestPlugin.player("Leo", "es_mx"), "toggle.on"));
        assertEquals("[NeoMod] Moderation is on.", text(messages, TestPlugin.player("Yuki", "ja_jp"), "toggle.on"),
                "a language with no bundled messages reads the server's");
        assertEquals("[NeoMod] Moderation is on.", text(messages, mock(CommandSender.class), "toggle.on"));
    }

    @Test
    void withPerPlayerLanguageOffEveryoneReadsTheServersLanguage() {
        MessageService messages = TestPlugin.loaded(folder.toFile(), "fr_FR", false);

        assertEquals("[NeoMod] La modération est activée.",
                text(messages, TestPlugin.player("Hans", "de_de"), "toggle.on"));
        assertEquals("fr_FR", messages.localeCodeOf(TestPlugin.player("Hans", "de_de")));
    }

    @Test
    void datesAreWrittenTheWayEachLanguageWritesThem() {
        MessageService messages = TestPlugin.loaded(folder.toFile(), "en_US", true);

        Map<String, String> expected = Map.of(
                "en_us", "15 October 2026",
                "de_de", "15. Oktober 2026",
                "es_es", "15 de octubre de 2026",
                "fr_fr", "15 octobre 2026",
                "pt_br", "15 de outubro de 2026",
                "tr_tr", "15 Ekim 2026");
        expected.forEach((game, date) ->
                assertEquals(date, DateText.of(messages, TestPlugin.player("P", game), EXPIRES), game));
    }

    @Test
    void aServersOwnLanguageFileFallsBackToEnglishForAnythingItLacks() throws Exception {
        Path locale = Files.createDirectories(folder.resolve("locale"));
        Files.writeString(locale.resolve("nl_NL.yml"), "toggle:\n  \"on\": \"{prefix} Moderatie staat aan.\"\n");
        MessageService messages = TestPlugin.loaded(folder.toFile(), "nl_NL", true);
        Player dutch = TestPlugin.player("Daan", "nl_nl");

        assertEquals("[NeoMod] Moderatie staat aan.", text(messages, dutch, "toggle.on"));
        assertEquals("[NeoMod] Moderation is off. Nothing is checked until /nmod on.",
                text(messages, dutch, "toggle.off"));
    }
}
