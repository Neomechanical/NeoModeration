package com.neomechanical.neomoderation.config;

import com.neomechanical.neomoderation.moderation.BundledWordPacks;
import org.bukkit.configuration.Configuration;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Brings an upgraded server's config.yml up to date without changing what it does.
 *
 * <p>A value that still holds exactly what an earlier release shipped follows the
 * new shipped value; a value the operator edited is never touched. A setting a
 * later release added is written into the file the first time, with its comment,
 * set to whatever keeps that server doing what it did: an upgrade never starts
 * blocking something by surprise. Each change is reported once, as a
 * {@link Change} the console shows in the server's language.</p>
 */
public final class ConfigMigrations {
    static final String WORDS_PATH = "moderation.offline.bannedWords";
    static final String SERVER_ADS_PATH = "moderation.offline.blockServerAds";
    static final String PACKS_PATH = "moderation.offline.languagePacks";
    static final String TELL_PLAYERS_PATH = "moderation.tellPlayers";
    static final String PER_PLAYER_PATH = "perPlayerLanguage";
    static final String MAP_ART_CAP_PATH = "moderation.mapArt.checksPerPlayerPerHour";
    static final String TRUST_LANGUAGE_PATH = "moderation.offline.trustPlayerLanguage";

    /**
     * The starter word list shipped from 1.5.0 to 1.6.6. It blocked "scam", so a
     * player warning others about a scam had the warning blocked.
     */
    static final List<String> STARTER_WORDS_1_5 = List.of(
            "badword", "scam", "fuck", "fucks", "fucked", "fucking", "fucker", "fuckers",
            "motherfucker", "motherfuckers", "shit", "shits", "shitty", "bullshit", "bitch", "bitches",
            "cunt", "cunts", "asshole", "assholes", "bastard", "bastards", "dickhead", "whore", "whores",
            "slut", "sluts", "nigger", "niggers", "nigga", "niggas", "faggot", "faggots", "retard",
            "retarded", "wanker", "twat", "kys");

    /** One change made to config.yml: a {@code migrated.*} message key and its values. */
    public record Change(String key, Map<String, String> values) {
    }

    private ConfigMigrations() {
    }

    /**
     * Applies every migration to {@code config}, whose defaults are the bundled
     * config.yml. {@code serverEditedItsLanguage} tells whether the locale file of
     * the server's language holds texts the server wrote. Empty when nothing changed.
     */
    public static List<Change> apply(Configuration config, boolean serverEditedItsLanguage) {
        List<Change> changes = new ArrayList<>();
        Configuration bundled = config.getDefaults();
        if (bundled == null) {
            return changes;
        }
        List<String> current = config.getStringList(WORDS_PATH);
        List<String> shipped = bundled.getStringList(WORDS_PATH);
        if (config.isSet(WORDS_PATH) && !shipped.isEmpty() && sameWords(current, STARTER_WORDS_1_5)) {
            config.set(WORDS_PATH, shipped);
            changes.add(new Change("migrated.words",
                    Map.of("count", String.valueOf(shipped.size() - current.size() + 1))));
        }

        // Settings 1.7.0 added. Missing means an earlier release wrote this file.
        if (!config.isSet(SERVER_ADS_PATH)) {
            add(config, SERVER_ADS_PATH, false);
            changes.add(new Change("migrated.server-ads", Map.of()));
        }
        if (!config.isSet(PACKS_PATH)) {
            boolean languageHasPack = BundledWordPacks.CODES.contains(
                    BundledWordPacks.languageOf(config.getString("locale", "en_US")));
            add(config, PACKS_PATH, languageHasPack ? List.of() : List.of(BundledWordPacks.AUTO));
            if (languageHasPack) {
                changes.add(new Change("migrated.language-packs", Map.of()));
            }
        }
        if (!config.isSet(TELL_PLAYERS_PATH)) {
            add(config, TELL_PLAYERS_PATH, true);
            changes.add(new Change("migrated.tell-players", Map.of()));
        }
        if (!config.isSet(MAP_ART_CAP_PATH) && bundled.isSet(MAP_ART_CAP_PATH)) {
            int cap = bundled.getInt(MAP_ART_CAP_PATH);
            add(config, MAP_ART_CAP_PATH, cap);
            // Only a server with a cloud key checks map art, so only there is it news.
            if (!config.getString("moderation.api.apiKey", "").isBlank()) {
                changes.add(new Change("migrated.map-art-cap", Map.of("count", String.valueOf(cap))));
            }
        }
        if (!config.isSet(TRUST_LANGUAGE_PATH)) {
            // 1.6.6 read no game language, so nothing changes; the console names the words this concerns,
            // leaving out those the server's own language already lets through for everyone.
            add(config, TRUST_LANGUAGE_PATH, false);
            Set<String> excused = BundledWordPacks.languageWords(config.getString("locale", "en_US")).stream()
                    .map(BundledWordPacks::matchedForm)
                    .collect(Collectors.toSet());
            Set<String> named = new HashSet<>();
            List<String> everyday = (config.isSet(WORDS_PATH) ? config.getStringList(WORDS_PATH) : List.<String>of())
                    .stream()
                    .map(String::trim)
                    .filter(word -> !BundledWordPacks.languagesUsing(word).isEmpty())
                    .filter(word -> !excused.contains(BundledWordPacks.matchedForm(word)))
                    .filter(word -> named.add(BundledWordPacks.matchedForm(word)))
                    .toList();
            if (!everyday.isEmpty()) {
                changes.add(new Change("migrated.trust-language", Map.of("words", String.join(", ", everyday))));
            }
        }
        if (!config.isSet(PER_PLAYER_PATH)) {
            // A server that rewrote its messages keeps showing them to everyone.
            add(config, PER_PLAYER_PATH, !serverEditedItsLanguage);
            changes.add(new Change(serverEditedItsLanguage ? "migrated.per-player-off" : "migrated.per-player-on",
                    Map.of("locale", config.getString("locale", "en_US"))));
        }
        return changes;
    }

    /** Writes a setting the file lacks, with the bundled config.yml's comment above it where the server keeps comments. */
    private static void add(Configuration config, String path, Object value) {
        config.set(path, value);
        try {
            // Comments in config files exist from 1.18.1; on older servers the value goes in alone.
            Method getComments = config.getDefaults().getClass().getMethod("getComments", String.class);
            Method setComments = config.getClass().getMethod("setComments", String.class, List.class);
            setComments.invoke(config, path, getComments.invoke(config.getDefaults(), path));
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // No comments on this server version.
        }
    }

    private static boolean sameWords(List<String> configured, List<String> shipped) {
        return configured.size() == shipped.size() && lowerSet(configured).equals(lowerSet(shipped));
    }

    private static Set<String> lowerSet(List<String> words) {
        return words.stream().map(word -> word.trim().toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
    }
}
