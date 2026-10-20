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

    private static YamlConfiguration bundled() throws IOException, InvalidConfigurationException {
        YamlConfiguration bundled = new YamlConfiguration();
        bundled.loadFromString(Files.readString(Path.of("src/main/resources/config.yml")));
        return bundled;
    }

    /** A config written by 1.7.0 or later, holding {@code words} as its list. */
    private static YamlConfiguration installWith(List<String> words) throws IOException, InvalidConfigurationException {
        YamlConfiguration config = upgradedFrom166("en_US");
        config.set(ConfigMigrations.SERVER_ADS_PATH, true);
        config.set(ConfigMigrations.PACKS_PATH, List.of("auto"));
        config.set(ConfigMigrations.TELL_PLAYERS_PATH, true);
        config.set(ConfigMigrations.PER_PLAYER_PATH, true);
        config.set(ConfigMigrations.TRUST_LANGUAGE_PATH, false);
        if (words != null) {
            config.set(ConfigMigrations.WORDS_PATH, words);
        }
        return config;
    }

    /** A config written by 1.6.6: none of the settings 1.7.0 added. */
    private static YamlConfiguration upgradedFrom166(String locale) throws IOException, InvalidConfigurationException {
        YamlConfiguration config = new YamlConfiguration();
        config.setDefaults(bundled());
        config.set("locale", locale);
        config.set("moderation.enabled", true);
        return config;
    }

    private static List<String> keys(List<ConfigMigrations.Change> changes) {
        return changes.stream().map(ConfigMigrations.Change::key).toList();
    }

    @Test
    void anUneditedOldStarterListBecomesTheCurrentOne() throws Exception {
        YamlConfiguration config = installWith(ConfigMigrations.STARTER_WORDS_1_5);

        List<ConfigMigrations.Change> changes = ConfigMigrations.apply(config, false);

        assertEquals(List.of("migrated.words"), keys(changes));
        List<String> words = config.getStringList(ConfigMigrations.WORDS_PATH);
        assertFalse(words.contains("scam"), "players must be able to warn each other about a scam");
        assertTrue(words.containsAll(List.of("badword", "fuck", "kys", "tranny")));
        assertEquals(String.valueOf(words.size() - ConfigMigrations.STARTER_WORDS_1_5.size() + 1),
                changes.get(0).values().get("count"));
        assertTrue(ConfigMigrations.apply(config, false).isEmpty(), "a migrated list is not migrated again");
    }

    @Test
    void anUpgradedServerGetsTheGameLanguageSettingAndTheWordsItConcerns() throws Exception {
        YamlConfiguration config = upgradedFrom166("en_US");
        config.set(ConfigMigrations.WORDS_PATH, ConfigMigrations.STARTER_WORDS_1_5);

        List<ConfigMigrations.Change> changes = ConfigMigrations.apply(config, false);

        assertTrue(config.isSet(ConfigMigrations.TRUST_LANGUAGE_PATH));
        assertFalse(config.getBoolean(ConfigMigrations.TRUST_LANGUAGE_PATH), "1.6.6 read no game language");
        ConfigMigrations.Change trust = changes.stream()
                .filter(change -> change.key().equals("migrated.trust-language")).findFirst().orElseThrow();
        for (String word : List.of("kike", "fag", "retard", "slut")) {
            assertTrue(trust.values().get("words").contains(word), trust.values().get("words"));
        }
        assertFalse(trust.values().get("words").contains("fuck"), trust.values().get("words"));

        YamlConfiguration spanish = upgradedFrom166("es_ES");
        spanish.set(ConfigMigrations.WORDS_PATH, ConfigMigrations.STARTER_WORDS_1_5);
        String named = ConfigMigrations.apply(spanish, false).stream()
                .filter(change -> change.key().equals("migrated.trust-language")).findFirst().orElseThrow()
                .values().get("words");
        assertFalse(named.contains("kike"), "a Spanish server already lets everyone write Kike: " + named);
        assertTrue(named.contains("fag"), named);

        YamlConfiguration mixed = upgradedFrom166("en_US");
        mixed.set(ConfigMigrations.WORDS_PATH, List.of("pute", "putte", "fodder", "slut", "Slut", " SLUT"));
        assertEquals("putte, slut", ConfigMigrations.apply(mixed, false).stream()
                .filter(change -> change.key().equals("migrated.trust-language")).findFirst().orElseThrow()
                .values().get("words"), "each word once, as the filter reads it, and only words a language uses");

        YamlConfiguration plain = upgradedFrom166("en_US");
        plain.set(ConfigMigrations.WORDS_PATH, List.of("badword", "fuck"));
        assertTrue(ConfigMigrations.apply(plain, false).stream()
                        .noneMatch(change -> change.key().equals("migrated.trust-language")),
                "a list with no word another language uses says nothing");
        assertFalse(plain.getBoolean(ConfigMigrations.TRUST_LANGUAGE_PATH));
    }

    @Test
    void theOldListIsRecognisedInAnyOrderAndCase() throws Exception {
        List<String> shuffled = new ArrayList<>(ConfigMigrations.STARTER_WORDS_1_5);
        Collections.reverse(shuffled);
        shuffled.set(0, shuffled.get(0).toUpperCase());

        assertEquals(List.of("migrated.words"), keys(ConfigMigrations.apply(installWith(shuffled), false)));
    }

    @Test
    void anEditedListIsNeverTouched() throws Exception {
        List<String> edited = new ArrayList<>(ConfigMigrations.STARTER_WORDS_1_5);
        edited.add("myserverword");
        YamlConfiguration config = installWith(edited);

        assertTrue(ConfigMigrations.apply(config, false).isEmpty());
        assertEquals(edited, config.getStringList(ConfigMigrations.WORDS_PATH));

        List<String> trimmed = new ArrayList<>(ConfigMigrations.STARTER_WORDS_1_5);
        trimmed.remove("scam");
        assertTrue(ConfigMigrations.apply(installWith(trimmed), false).isEmpty(),
                "an operator who already removed a word made the list their own");
    }

    @Test
    void aConfigWithoutTheListIsLeftToTheBundledDefault() throws Exception {
        YamlConfiguration config = installWith(null);

        assertTrue(ConfigMigrations.apply(config, false).isEmpty());
        assertFalse(config.isSet(ConfigMigrations.WORDS_PATH));
    }

    @Test
    void anUpgradedServerKeepsDoingWhatItDidAndIsToldWhatItCanTurnOn() throws Exception {
        YamlConfiguration config = upgradedFrom166("es_ES");

        List<ConfigMigrations.Change> changes = ConfigMigrations.apply(config, false);

        assertFalse(config.getBoolean(ConfigMigrations.SERVER_ADS_PATH), "ad blocking never starts by surprise");
        assertEquals(List.of(), config.getStringList(ConfigMigrations.PACKS_PATH),
                "a Spanish server does not gain the Spanish list without choosing it");
        assertTrue(config.getBoolean(ConfigMigrations.TELL_PLAYERS_PATH));
        assertTrue(config.getBoolean(ConfigMigrations.PER_PLAYER_PATH));
        assertEquals(List.of("migrated.server-ads", "migrated.language-packs", "migrated.tell-players",
                "migrated.per-player-on"), keys(changes));
        assertTrue(ConfigMigrations.apply(config, false).isEmpty(), "each setting is added once");

        OfflineModerationSettings offline = OfflineModerationSettings.from(new BukkitConfigView(config));
        assertFalse(offline.blockServerAds());
        assertTrue(offline.packWords().isEmpty());
    }

    @Test
    void anUpgradedServerSeesTheMapArtLimitAndIsToldWhenItHasACloudKey() throws Exception {
        YamlConfiguration keyless = upgradedFrom166("en_US");
        assertFalse(keys(ConfigMigrations.apply(keyless, false)).contains("migrated.map-art-cap"),
                "without a key no map is checked, so the limit is no news");
        assertEquals(30, keyless.getInt(ConfigMigrations.MAP_ART_CAP_PATH));

        YamlConfiguration keyed = upgradedFrom166("en_US");
        keyed.set("moderation.api.apiKey", "nmk_test");
        List<ConfigMigrations.Change> changes = ConfigMigrations.apply(keyed, false);

        assertTrue(changes.contains(new ConfigMigrations.Change("migrated.map-art-cap", java.util.Map.of("count", "30"))));
        assertFalse(keys(ConfigMigrations.apply(keyed, false)).contains("migrated.map-art-cap"), "said once");
    }

    @Test
    void anEnglishServerHasNoPackToKeepOffSoItIsNotToldAboutOne() throws Exception {
        YamlConfiguration config = upgradedFrom166("en_US");

        List<String> changes = keys(ConfigMigrations.apply(config, false));

        assertEquals(List.of("auto"), config.getStringList(ConfigMigrations.PACKS_PATH));
        assertFalse(changes.contains("migrated.language-packs"));
    }

    @Test
    void aServerThatRewroteItsMessagesKeepsShowingThemToEveryone() throws Exception {
        YamlConfiguration config = upgradedFrom166("de_DE");

        List<ConfigMigrations.Change> changes = ConfigMigrations.apply(config, true);

        assertFalse(config.getBoolean(ConfigMigrations.PER_PLAYER_PATH));
        assertTrue(keys(changes).contains("migrated.per-player-off"));
    }

    @Test
    void aNewInstallIsLeftAlone() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(Files.readString(Path.of("src/main/resources/config.yml")));
        config.setDefaults(bundled());

        assertTrue(ConfigMigrations.apply(config, false).isEmpty());
        assertTrue(config.getBoolean(ConfigMigrations.SERVER_ADS_PATH));
    }

    @Test
    void aSettingWrittenIntoAnUpgradedFileTakesTheBundledCommentWithIt() throws Exception {
        CommentedConfiguration bundled = new CommentedConfiguration();
        bundled.loadFromString(Files.readString(Path.of("src/main/resources/config.yml")));
        bundled.setComments(ConfigMigrations.SERVER_ADS_PATH, List.of("Block ads for other Minecraft servers."));
        CommentedConfiguration config = new CommentedConfiguration();
        config.setDefaults(bundled);
        config.set("locale", "en_US");

        ConfigMigrations.apply(config, false);

        assertEquals(List.of("Block ads for other Minecraft servers."),
                config.getComments(ConfigMigrations.SERVER_ADS_PATH));
    }

    /** Comments as Bukkit 1.18.1 and later keep them; the 1.8 API these tests compile against has none. */
    public static final class CommentedConfiguration extends YamlConfiguration {
        private final java.util.Map<String, List<String>> comments = new java.util.HashMap<>();

        public List<String> getComments(String path) {
            return comments.getOrDefault(path, List.of());
        }

        public void setComments(String path, List<String> lines) {
            comments.put(path, lines == null ? List.of() : List.copyOf(lines));
        }
    }
}
