package com.neomechanical.neomoderation.messages;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class LocaleStoreTest {

    private static boolean shipped(Map<String, Set<String>> known, String key, String value) {
        return known.getOrDefault(key, Set.of()).contains(LocaleStore.fingerprint(value));
    }

    @Test
    void aTextNobodyEditedFollowsTheBundledTextAndMissingKeysAreAdded() {
        YamlConfiguration onDisk = new YamlConfiguration();
        onDisk.set("setup.done", "old shipped text");
        YamlConfiguration bundled = new YamlConfiguration();
        bundled.set("setup.done", "new shipped text");
        bundled.set("notice.chat", "Your message wasn't sent.");
        Map<String, Set<String>> known = Map.of("setup.done", Set.of(LocaleStore.fingerprint("old shipped text")));

        assertTrue(LocaleStore.merge(onDisk, bundled, (key, value) -> shipped(known, key, value)));
        assertEquals("new shipped text", onDisk.getString("setup.done"));
        assertEquals("Your message wasn't sent.", onDisk.getString("notice.chat"));
        assertFalse(LocaleStore.merge(onDisk, bundled, (key, value) -> shipped(known, key, value)),
                "a second pass has nothing left to do");
    }

    @Test
    void aTextTheServerEditedIsNeverTouched() {
        YamlConfiguration onDisk = new YamlConfiguration();
        onDisk.set("setup.done", "Our own words");
        YamlConfiguration bundled = new YamlConfiguration();
        bundled.set("setup.done", "new shipped text");

        assertFalse(LocaleStore.merge(onDisk, bundled, (key, value) -> false));
        assertEquals("Our own words", onDisk.getString("setup.done"));
    }

    @Test
    void anObsoleteKeyGoesOnlyWhileItHoldsAShippedText() {
        YamlConfiguration onDisk = new YamlConfiguration();
        onDisk.set("toggle.true", "{prefix} &7Moderation has been &aENABLED&7.");
        onDisk.set("custom.line", "kept, the server wrote it");
        YamlConfiguration bundled = new YamlConfiguration();
        bundled.set("toggle.on", "{prefix} Moderation is on.");
        Map<String, Set<String>> known = Map.of("toggle.true",
                Set.of(LocaleStore.fingerprint("{prefix} &7Moderation has been &aENABLED&7.")));

        assertTrue(LocaleStore.merge(onDisk, bundled, (key, value) -> shipped(known, key, value)));
        assertNull(onDisk.getString("toggle.true"));
        assertEquals("kept, the server wrote it", onDisk.getString("custom.line"));
        assertEquals("{prefix} Moderation is on.", onDisk.getString("toggle.on"));
    }

    @Test
    void theHistoryCoversEveryTextTheLastReleaseShipped() throws Exception {
        // 1.6.6's en_US.yml must be recognised as unedited, so it upgrades in full.
        Path history = Path.of("src/main/resources/locale/history/en_US.txt");
        Map<String, Set<String>> known = new HashMap<>();
        for (String line : Files.readAllLines(history)) {
            int tab = line.indexOf('\t');
            if (!line.startsWith("#") && tab > 0) {
                known.put(line.substring(0, tab), Set.of(line.substring(tab + 1).split(",")));
            }
        }
        Process git;
        try {
            git = new ProcessBuilder("git", "show", "v1.6.6:src/main/resources/locale/en_US.yml").start();
        } catch (java.io.IOException noGit) {
            assumeTrue(false, "needs git");
            return;
        }
        String text = new String(git.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assumeTrue(git.waitFor() == 0, "needs the repository's v1.6.6 tag");
        YamlConfiguration released = new YamlConfiguration();
        released.loadFromString(text);
        for (String key : released.getKeys(true)) {
            if (!released.isConfigurationSection(key)) {
                assertTrue(shipped(known, key, released.getString(key)), "history misses 1.6.6's text at " + key);
            }
        }
    }

    @Test
    void everyBundledLocaleHasAHistoryFile() {
        for (String code : MessageService.BUNDLED_LOCALES) {
            assertTrue(new File("src/main/resources/locale/history/" + code + ".txt").isFile(), code);
        }
    }

    @Test
    void resolvesLowercaseShortAndHyphenatedLocaleCodes() {
        assertEquals("en_US", MessageService.resolveLocaleCode(null));
        assertEquals("en_US", MessageService.resolveLocaleCode("en"));
        assertEquals("en_US", MessageService.resolveLocaleCode("en_gb"));
        assertEquals("es_ES", MessageService.resolveLocaleCode("es-MX"));
        assertEquals("fr_FR", MessageService.resolveLocaleCode("fr_fr"));
        assertEquals("de_DE", MessageService.resolveLocaleCode("de"));
        assertEquals("pt_BR", MessageService.resolveLocaleCode("pt-br"));
        assertEquals("tr_TR", MessageService.resolveLocaleCode("tr_TR"));
        assertEquals("custom_locale", MessageService.resolveLocaleCode("custom_locale"));
    }
}
