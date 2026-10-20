package com.neomechanical.neomoderation.config;

import com.neomechanical.neomoderation.moderation.BundledWordPacks;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The local rules. {@code bannedWords} and {@code allowedWords} are the server's
 * own lists as written in config.yml; {@code packWords} are the words of the
 * language packs it selected; {@code locale} is the server's language. What is
 * enforced is {@link #effectiveWords()} less {@link #effectiveAllowedWords()}.
 * {@code trustPlayerLanguage} lets a player's game language excuse words too
 * (see {@link #wordLanguage(String)}); players choose it, so it is off unless
 * the server turns it on.
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
        boolean blockServerAds,
        String locale,
        boolean trustPlayerLanguage
) {
    public static final List<String> DEFAULT_LANGUAGE_PACKS = List.of(BundledWordPacks.AUTO);
    private static final String DEFAULT_LOCALE = "en_US";

    /** Local rules on an English server. */
    public OfflineModerationSettings(
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
        this(enabled, blockAnyUrl, normalizeLeetspeak, bannedWords, bannedUrls, allowedWords, allowedUrls,
                languagePacks, packWords, blockServerAds, DEFAULT_LOCALE, false);
    }

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
        String locale = config.getString("locale", DEFAULT_LOCALE);
        List<String> packs = BundledWordPacks.select(requestedPacks, locale, !configuredWords.isEmpty());
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
                config.getBoolean("moderation.offline.blockServerAds", true),
                locale,
                config.getBoolean("moderation.offline.trustPlayerLanguage", false)
        );
    }

    /**
     * The game language the word rules may read a message from {@code speaker} in:
     * theirs when the server trusts players' game languages, else none, so only
     * English and the server's own language can excuse a word. Players pick their
     * game language, so trusting it lets any player use the words it excuses.
     */
    public String wordLanguage(String speaker) {
        return trustPlayerLanguage ? speaker : null;
    }

    /**
     * Every allowed phrase in force: the server's own, then the everyday phrases
     * every server allows, those its language packs allow, and the words its own
     * language uses every day that another language's list bans.
     */
    public List<String> effectiveAllowedWords() {
        Set<String> merged = new LinkedHashSet<>(allowedWords);
        merged.addAll(BundledWordPacks.allowedOf(languagePacks));
        merged.addAll(BundledWordPacks.languageWords(locale));
        return List.copyOf(merged);
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
