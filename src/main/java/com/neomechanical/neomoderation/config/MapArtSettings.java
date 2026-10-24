package com.neomechanical.neomoderation.config;

/**
 * Controls NSFW scanning of filled maps (map art). Scanning requires a cloud API key;
 * without one these settings have no effect. {@code failClosed} (off by default) treats a new map the cloud
 * cannot check (the free plan's pictures used up, the cloud paused, or the player's hourly allowance spent)
 * as a flagged one.
 */
public record MapArtSettings(
        boolean enabled,
        boolean scanOnHold,
        boolean scanOnFrameInteract,
        boolean confiscate,
        int cacheSize,
        int checksPerPlayerPerHour,
        boolean failClosed
) {
    private static final int DEFAULT_CACHE_SIZE = 1000;
    private static final int DEFAULT_CHECKS_PER_PLAYER_PER_HOUR = 30;

    public static MapArtSettings from(ConfigView config) {
        int cacheSize = config.getInt("moderation.mapArt.cacheSize", DEFAULT_CACHE_SIZE);
        return new MapArtSettings(
                config.getBoolean("moderation.mapArt.enabled", true),
                config.getBoolean("moderation.mapArt.scanOnHold", true),
                config.getBoolean("moderation.mapArt.scanOnFrameInteract", true),
                config.getBoolean("moderation.mapArt.confiscate", true),
                Math.max(16, cacheSize),
                Math.max(0, config.getInt("moderation.mapArt.checksPerPlayerPerHour",
                        DEFAULT_CHECKS_PER_PLAYER_PER_HOUR)),
                config.getBoolean("moderation.mapArt.failClosed", false)
        );
    }

    /** The settings before {@code failClosed} (an opt-in, off by default): a map that cannot be checked is shown. */
    public MapArtSettings(boolean enabled, boolean scanOnHold, boolean scanOnFrameInteract, boolean confiscate,
                          int cacheSize, int checksPerPlayerPerHour) {
        this(enabled, scanOnHold, scanOnFrameInteract, confiscate, cacheSize, checksPerPlayerPerHour, false);
    }

    /** Whether a player may have another new picture checked, given how many they had this hour. */
    public boolean allowsAnotherCheck(int checkedThisHour) {
        return checksPerPlayerPerHour == 0 || checkedThisHour < checksPerPlayerPerHour;
    }
}
