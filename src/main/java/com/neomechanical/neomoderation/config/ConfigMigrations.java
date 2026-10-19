package com.neomechanical.neomoderation.config;

import org.bukkit.configuration.Configuration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Brings config.yml up to date where it still holds exactly what an earlier
 * release shipped and the shipped value has changed since. A value the operator
 * edited is never touched, and a migrated value no longer matches, so nothing
 * runs twice.
 */
public final class ConfigMigrations {
    static final String WORDS_PATH = "moderation.offline.bannedWords";

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

    private ConfigMigrations() {
    }

    /**
     * Applies every migration to {@code config}, whose defaults are the bundled
     * config.yml. Returns one console line per change; empty when nothing changed.
     */
    public static List<String> apply(Configuration config) {
        List<String> changes = new ArrayList<>();
        Configuration bundled = config.getDefaults();
        if (bundled == null) {
            return changes;
        }
        List<String> current = config.getStringList(WORDS_PATH);
        List<String> shipped = bundled.getStringList(WORDS_PATH);
        if (config.isSet(WORDS_PATH) && !shipped.isEmpty() && sameWords(current, STARTER_WORDS_1_5)) {
            config.set(WORDS_PATH, shipped);
            changes.add("Updated the unedited starter word list in config.yml: \"scam\" is no longer blocked, "
                    + "so players can warn each other about scams, and " + (shipped.size() - current.size() + 1)
                    + " slurs were added.");
        }
        return changes;
    }

    private static boolean sameWords(List<String> configured, List<String> shipped) {
        return configured.size() == shipped.size() && lowerSet(configured).equals(lowerSet(shipped));
    }

    private static Set<String> lowerSet(List<String> words) {
        return words.stream().map(word -> word.trim().toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
    }
}
