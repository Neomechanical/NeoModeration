package com.neomechanical.neomoderation.messages;

import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

    @Test
    void aLocaleFileWithATypoIsLeftAsItIsAndItsLanguageStillReads(@TempDir Path folder)
            throws Exception {
        Path locale = Files.createDirectories(folder.resolve("locale"));
        String broken = "toggle:\n  \"on\": \"{prefix} Moderación: activa\n  sin cerrar: [\n";
        Files.writeString(locale.resolve("es_ES.yml"), broken);
        JavaPlugin plugin = plugin(folder);

        Set<String> edited = LocaleStore.prepare(plugin);

        assertEquals(broken, Files.readString(locale.resolve("es_ES.yml")), "a broken file is never overwritten");
        assertTrue(edited.contains("es_ES"), "a file that cannot be read counts as the server's own");
        MessageService messages = MessageService.load(plugin, "es_ES", false);
        assertEquals("[NeoMod] La moderación está activada.",
                MessageService.plain(messages.text(mock(CommandSender.class),
                        "toggle.on")), "the built-in Spanish texts stand in until the file is fixed");
    }

    @Test
    void aFileHoldingTheServersOwnTextIsReportedAndKept(@TempDir Path folder)
            throws Exception {
        Path locale = Files.createDirectories(folder.resolve("locale"));
        Files.writeString(locale.resolve("en_US.yml"), "toggle:\n  \"on\": \"{prefix} Our filter is on.\"\n");
        JavaPlugin plugin = plugin(folder);

        Set<String> edited = LocaleStore.prepare(plugin);

        assertTrue(edited.contains("en_US"));
        YamlConfiguration onDisk = YamlConfiguration.loadConfiguration(locale.resolve("en_US.yml").toFile());
        assertEquals("{prefix} Our filter is on.", onDisk.getString("toggle.on"));
        assertTrue(onDisk.isSet("toggle.off"), "missing keys are still added around the server's own text");
    }

    private static JavaPlugin plugin(Path folder) {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(folder.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("test"));
        when(plugin.getResource(anyString())).thenAnswer(call ->
                LocaleStoreTest.class.getClassLoader().getResourceAsStream(call.<String>getArgument(0)));
        return plugin;
    }
}
