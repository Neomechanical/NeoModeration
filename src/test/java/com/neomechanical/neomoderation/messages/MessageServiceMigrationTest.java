package com.neomechanical.neomoderation.messages;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MessageServiceMigrationTest {

    @TempDir
    Path tempDir;

    @Test
    void upgradesExactEnglishLegacyActivationCopyAndAddsNewKeys() {
        YamlConfiguration onDisk = new YamlConfiguration();
        onDisk.set("setup.done",
                "{prefix} &a&lSuccess! &7Cloud moderation is now &aactive&7. Chat is being scanned.");
        onDisk.set("key.saved", "{prefix} &aAPI key saved successfully.");
        onDisk.set("help.usage.test", "/nmod test <msg>");

        YamlConfiguration bundled = new YamlConfiguration();
        bundled.set("setup.done", "API key saved; run /nmod doctor.");
        bundled.set("key.saved", "API key saved; verify it.");
        bundled.set("help.usage.test", "/nmod test badword");
        bundled.set("status.cloud-credits", "Insufficient credits.");

        assertTrue(MessageService.mergeBundledLocale("en_US", onDisk, bundled));
        assertEquals("API key saved; run /nmod doctor.", onDisk.getString("setup.done"));
        assertEquals("API key saved; verify it.", onDisk.getString("key.saved"));
        assertEquals("/nmod test badword", onDisk.getString("help.usage.test"));
        assertEquals("Insufficient credits.", onDisk.getString("status.cloud-credits"));
    }

    @Test
    void upgradesExactSpanishLegacyActivationCopy() {
        YamlConfiguration onDisk = new YamlConfiguration();
        onDisk.set("setup.done",
                "{prefix} &a&lListo! &7Moderación en la nube &aactiva&7.");
        onDisk.set("help.desc.test", "probar la regla incluida; nunca actúa");

        YamlConfiguration bundled = new YamlConfiguration();
        bundled.set("setup.done", "Clave guardada; ejecuta /nmod doctor.");
        bundled.set("help.desc.test", "vista previa segura; nunca actúa");

        assertTrue(MessageService.mergeBundledLocale("es_ES", onDisk, bundled));
        assertEquals("Clave guardada; ejecuta /nmod doctor.", onDisk.getString("setup.done"));
        assertEquals("vista previa segura; nunca actúa",
                onDisk.getString("help.desc.test"));
    }

    @Test
    void preservesCustomizedValuesEvenWhenOtherDefaultsAreMerged() {
        YamlConfiguration onDisk = new YamlConfiguration();
        onDisk.set("setup.done", "Our custom setup message");
        onDisk.set("help.usage.test", "/safety preview <message>");

        YamlConfiguration bundled = new YamlConfiguration();
        bundled.set("setup.done", "API key saved; run /nmod doctor.");
        bundled.set("help.usage.test", "/nmod test badword");
        bundled.set("status.cloud-ready", "Cloud ready.");

        assertTrue(MessageService.mergeBundledLocale("en_US", onDisk, bundled));
        assertEquals("Our custom setup message", onDisk.getString("setup.done"));
        assertEquals("/safety preview <message>", onDisk.getString("help.usage.test"));
        assertEquals("Cloud ready.", onDisk.getString("status.cloud-ready"));

        assertFalse(MessageService.mergeBundledLocale("en_US", onDisk, bundled));
    }

    @Test
    void failedLocalePersistenceIsLoggedAndMergedValuesRemainActive() throws Exception {
        Path localeDir = Files.createDirectories(tempDir.resolve("locale"));
        Path english = localeDir.resolve("en_US.yml");
        Files.writeString(english, """
                prefix: ''
                setup:
                  done: '{prefix} &a&lSuccess! &7Cloud moderation is now &aactive&7. Chat is being scanned.'
                """);
        Files.setPosixFilePermissions(english, EnumSet.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.OTHERS_READ
        ));

        List<String> warnings = new ArrayList<>();
        Logger logger = Logger.getLogger("MessageServiceMigrationTest." + System.nanoTime());
        logger.setUseParentHandlers(false);
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                warnings.add(record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(handler);

        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());
        when(plugin.getLogger()).thenReturn(logger);
        when(plugin.getResource(anyString())).thenAnswer(invocation -> {
            String locale = invocation.getArgument(0);
            String bundled = locale.endsWith("en_US.yml")
                    ? "prefix: ''\nsetup:\n  done: 'API key saved; run /nmod doctor.'\n"
                    : "prefix: ''\n";
            return new ByteArrayInputStream(bundled.getBytes(StandardCharsets.UTF_8));
        });

        MessageService service = MessageService.load(plugin, "en_US");

        assertEquals("API key saved; run /nmod doctor.", service.format("setup.done", Map.of()));
        assertTrue(warnings.stream().anyMatch(message -> message.contains("Could not persist locale en_US")));
    }

    @Test
    void resolvesLowercaseShortAndHyphenatedLocaleCodes() {
        assertEquals("en_US", MessageService.resolveLocaleCode(null));
        assertEquals("en_US", MessageService.resolveLocaleCode("en"));
        assertEquals("es_ES", MessageService.resolveLocaleCode("es-MX"));
        assertEquals("fr_FR", MessageService.resolveLocaleCode("fr_fr"));
        assertEquals("de_DE", MessageService.resolveLocaleCode("de"));
        assertEquals("pt_BR", MessageService.resolveLocaleCode("pt-br"));
        assertEquals("tr_TR", MessageService.resolveLocaleCode("tr_TR"));
        assertEquals("custom_locale", MessageService.resolveLocaleCode("custom_locale"));
    }

    @Test
    void upgradesRetiredHexAndJoinTokensToDesignSystemBrandTokens() {
        YamlConfiguration onDisk = new YamlConfiguration();
        onDisk.set("prefix", "&8[&x&0&0&F&F&A&ANeoMod&8]");
        onDisk.set("help.dashboard",
                "&x&0&0&F&F&A&A&lNeoModeration &8v{version} &7● &f{mode} &8| &7Cloud: &f{cloud} &8| &7Detections: &b{total}");
        onDisk.set("help.page", "&8Page {page}/2 · &7try &b/nmod help {next}");
        onDisk.set("usage.fetching", "{prefix} &7Fetching usage from NeoMechanical...");

        YamlConfiguration bundled = new YamlConfiguration();
        bundled.set("prefix", "&8[&x&9&c&a&8&f&fNeoMod&8]");
        bundled.set("help.dashboard",
                "&x&9&c&a&8&f&f&lNeoModeration &8v{version} | &f{mode} &8| &7Cloud: &f{cloud} &8| &7Detections: &b{total}");
        bundled.set("help.page", "&8Page {page}/2 | &7try &b/nmod help {next}");
        bundled.set("usage.fetching", "{prefix} &7Fetching usage from Neomechanical...");

        assertTrue(MessageService.mergeBundledLocale("en_US", onDisk, bundled));
        assertEquals("&8[&x&9&c&a&8&f&fNeoMod&8]", onDisk.getString("prefix"));
        assertEquals(
                "&x&9&c&a&8&f&f&lNeoModeration &8v{version} | &f{mode} &8| &7Cloud: &f{cloud} &8| &7Detections: &b{total}",
                onDisk.getString("help.dashboard"));
        assertEquals("&8Page {page}/2 | &7try &b/nmod help {next}", onDisk.getString("help.page"));
        assertEquals("{prefix} &7Fetching usage from Neomechanical...", onDisk.getString("usage.fetching"));
    }
}
