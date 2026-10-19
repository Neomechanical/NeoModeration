package com.neomechanical.neomoderation.config;

import com.neomechanical.neomoderation.messages.MessageService;
import com.neomechanical.neomoderation.moderation.BundledWordPacks;

import java.util.List;

public record OfflineModerationSettings(
        boolean enabled,
        boolean blockAnyUrl,
        boolean normalizeLeetspeak,
        List<String> bannedWords,
        List<String> bannedUrls,
        List<String> allowedWords,
        List<String> allowedUrls
) {
    public static OfflineModerationSettings from(ConfigView config) {
        List<String> configuredWords = normalizeList(config.getStringList("moderation.offline.bannedWords"));
        String resolvedLocale = MessageService.resolveLocaleCode(config.getString("locale", "en_US"));
        boolean includeLocalePack = config.getBoolean("moderation.offline.multilingualStarterWords", true)
                && !"en_US".equals(resolvedLocale);
        List<String> effectiveWords = BundledWordPacks.mergeWithLocalePack(
                configuredWords, resolvedLocale, includeLocalePack);
        return new OfflineModerationSettings(
                config.getBoolean("moderation.offline.enabled", true),
                config.getBoolean("moderation.offline.blockAnyUrl", false),
                config.getBoolean("moderation.offline.normalizeLeetspeak", true),
                effectiveWords,
                normalizeList(config.getStringList("moderation.offline.bannedUrls")),
                normalizeList(config.getStringList("moderation.offline.allowedWords")),
                normalizeList(config.getStringList("moderation.offline.allowedUrls"))
        );
    }

    private static List<String> normalizeList(List<String> rawValues) {
        return rawValues.stream()
                .map(value -> value == null ? "" : value.trim())
                .filter(value -> !value.isEmpty())
                .distinct()
                .toList();
    }
}
