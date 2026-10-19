package com.neomechanical.neomoderation.config;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigMigrationsTest {

    private static YamlConfiguration installWith(List<String> words) throws IOException, InvalidConfigurationException {
        YamlConfiguration bundled = new YamlConfiguration();
        bundled.loadFromString(Files.readString(Path.of("src/main/resources/config.yml")));
        YamlConfiguration config = new YamlConfiguration();
        config.setDefaults(bundled);
        if (words != null) {
            config.set(ConfigMigrations.WORDS_PATH, words);
        }
        return config;
    }

    @Test
    void anUneditedOldStarterListBecomesTheCurrentOne() throws Exception {
        YamlConfiguration config = installWith(ConfigMigrations.STARTER_WORDS_1_5);

        List<String> changes = ConfigMigrations.apply(config);

        assertEquals(1, changes.size());
        List<String> words = config.getStringList(ConfigMigrations.WORDS_PATH);
        assertFalse(words.contains("scam"), "players must be able to warn each other about a scam");
        assertTrue(words.containsAll(List.of("badword", "fuck", "kys", "tranny")));
        assertTrue(ConfigMigrations.apply(config).isEmpty(), "a migrated list is not migrated again");
    }

    @Test
    void theOldListIsRecognisedInAnyOrderAndCase() throws Exception {
        List<String> shuffled = new ArrayList<>(ConfigMigrations.STARTER_WORDS_1_5);
        Collections.reverse(shuffled);
        shuffled.set(0, shuffled.get(0).toUpperCase());

        assertEquals(1, ConfigMigrations.apply(installWith(shuffled)).size());
    }

    @Test
    void anEditedListIsNeverTouched() throws Exception {
        List<String> edited = new ArrayList<>(ConfigMigrations.STARTER_WORDS_1_5);
        edited.add("myserverword");
        YamlConfiguration config = installWith(edited);

        assertTrue(ConfigMigrations.apply(config).isEmpty());
        assertEquals(edited, config.getStringList(ConfigMigrations.WORDS_PATH));

        List<String> trimmed = new ArrayList<>(ConfigMigrations.STARTER_WORDS_1_5);
        trimmed.remove("scam");
        assertTrue(ConfigMigrations.apply(installWith(trimmed)).isEmpty(),
                "an operator who already removed a word made the list their own");
    }

    @Test
    void aConfigWithoutTheListIsLeftToTheBundledDefault() throws Exception {
        YamlConfiguration config = installWith(null);

        assertTrue(ConfigMigrations.apply(config).isEmpty());
        assertFalse(config.isSet(ConfigMigrations.WORDS_PATH));
    }
}
