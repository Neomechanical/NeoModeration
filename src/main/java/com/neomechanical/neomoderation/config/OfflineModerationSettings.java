package com.neomechanical.neomoderation.config;

import com.neomechanical.neomoderation.moderation.BundledWordPacks;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The local rules. {@code bannedWords} is the server's own list as written in
 * config.yml; {@code packWords} are the words of the language packs it selected.
 * Both are enforced: {@link #effectiveWords()}.
 */
public record OfflineModerationSettings(
        boolean enabled,
        boolean blockAnyUrl,
        boolean normalizeLeetspeak,
        List<String> bannedWords,
        List<String> bannedUrls,
        List<String> allowedWords,
        List<String> allowedUrls,
        List<String> languagePacks,
        List<String> packWords,
        boolean blockServerAds
) {
    public static final List<String> DEFAULT_LANGUAGE_PACKS = List.of(BundledWordPacks.AUTO);

    /** Local rules with no language packs and no server-advertising check. */
    public OfflineModerationSettings(
            boolean enabled,
            boolean blockAnyUrl,
            boolean normalizeLeetspeak,
            List<String> bannedWords,
            List<String> bannedUrls,
            List<String> allowedWords,
            List<String> allowedUrls
    ) {
        this(enabled, blockAnyUrl, normalizeLeetspeak, bannedWords, bannedUrls, allowedWords, allowedUrls,
                List.of(), List.of(), false);
    }

    public static OfflineModerationSettings from(ConfigView config) {
        List<String> configuredWords = normalizeList(config.getStringList("moderation.offline.bannedWords"));
        List<String> requestedPacks = config.get("moderation.offline.languagePacks") == null
                ? DEFAULT_LANGUAGE_PACKS
                : config.getStringList("moderation.offline.languagePacks");
        List<String> packs = BundledWordPacks.select(
                requestedPacks, config.getString("locale", "en_US"), !configuredWords.isEmpty());
        return new OfflineModerationSettings(
                config.getBoolean("moderation.offline.enabled", true),
                config.getBoolean("moderation.offline.blockAnyUrl", false),
                config.getBoolean("moderation.offline.normalizeLeetspeak", true),
                configuredWords,
                normalizeList(config.getStringList("moderation.offline.bannedUrls")),
                normalizeList(config.getStringList("moderation.offline.allowedWords")),
                normalizeList(config.getStringList("moderation.offline.allowedUrls")),
                packs,
                BundledWordPacks.wordsOf(packs),
                config.getBoolean("moderation.offline.blockServerAds", true)
        );
    }

    /** Every banned word in force: the server's list, then its language packs. */
    public List<String> effectiveWords() {
        if (packWords.isEmpty()) {
            return bannedWords;
        }
        Set<String> merged = new LinkedHashSet<>(bannedWords);
        merged.addAll(packWords);
        return List.copyOf(merged);
    }

    private static List<String> normalizeList(List<String> rawValues) {
        return rawValues.stream()
                .map(value -> value == null ? "" : value.trim())
                .filter(value -> !value.isEmpty())
                .distinct()
                .toList();
    }
}
