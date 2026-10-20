package com.neomechanical.neomoderation.config;

/**
 * Where the settings that commands change live in config.yml, so a command
 * and the settings record that reads the value always name the same key.
 */
public final class ConfigPaths {
    public static final String ENABLED = "moderation.enabled";
    public static final String MODE = "moderation.mode";
    public static final String CLOUD_MODE = "moderation.cloudMode";
    public static final String API_KEY = "moderation.api.apiKey";
    public static final String ACTIONS = "moderation.actions";
    public static final String DISCORD_WEBHOOK = "moderation.alerts.discordWebhookUrl";
    /** Followed by a surface name: {@code moderation.surfaces.sign}. */
    public static final String SURFACES = "moderation.surfaces.";
    /** Followed by a category name: {@code moderation.categories.harassment}. */
    public static final String CATEGORIES = "moderation.categories.";

    private ConfigPaths() {
    }
}
