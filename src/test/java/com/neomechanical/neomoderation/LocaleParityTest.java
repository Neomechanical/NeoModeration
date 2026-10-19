package com.neomechanical.neomoderation;

import com.neomechanical.neomoderation.messages.MessageService;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocaleParityTest {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-zA-Z0-9_-]+)}");

    @Test
    void bundledLocalesContainTheSameKeysAndPlaceholdersAsEnglish() {
        File root = new File("src/main/resources/locale");
        YamlConfiguration english = YamlConfiguration.loadConfiguration(new File(root, "en_US.yml"));
        Set<String> expected = english.getKeys(true);

        assertFalse(expected.isEmpty(), "en_US locale must not be empty");
        for (String code : MessageService.BUNDLED_LOCALES) {
            File file = new File(root, code + ".yml");
            assertTrue(file.isFile(), "Bundled locale file missing: " + file.getName());
        }

        for (File locale : root.listFiles((dir, name) -> name.endsWith(".yml") && !name.equals("en_US.yml"))) {
            YamlConfiguration translated = YamlConfiguration.loadConfiguration(locale);
            assertEquals(expected, translated.getKeys(true), locale.getName() + " must match en_US keys");
            for (String key : expected) {
                if (english.isConfigurationSection(key)) {
                    continue;
                }
                assertEquals(
                        placeholders(english.getString(key, "")),
                        placeholders(translated.getString(key, "")),
                        locale.getName() + " placeholder mismatch at " + key
                );
            }
        }
    }

    @Test
    void bundledLocalesFollowBrandTokensAndTypographyRules() throws IOException {
        File root = new File("src/main/resources/locale");
        for (File locale : root.listFiles((dir, name) -> name.endsWith(".yml"))) {
            String raw = Files.readString(locale.toPath());
            assertFalse(raw.toLowerCase().contains("&x&0&0&f&f&a&a"),
                    locale.getName() + " still uses retired #00FFAA hex token instead of #9ca8ff");
            assertFalse(raw.contains("NeoMechanical"),
                    locale.getName() + " contains camel-case NeoMechanical");
            assertFalse(raw.contains(" · "),
                    locale.getName() + " contains middle-dot join character");
            assertFalse(raw.contains("●"),
                    locale.getName() + " contains circle join character");
        }
    }

    private static Set<String> placeholders(String text) {
        Set<String> found = new TreeSet<>();
        Matcher matcher = PLACEHOLDER.matcher(text);
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return found;
    }
}
