package com.neomechanical.neomoderation.config;

/**
 * Controls NSFW scanning of filled maps (map art). Scanning requires a cloud API key;
 * without one these settings have no effect.
 */
public record MapArtSettings(
        boolean enabled,
        boolean scanOnHold,
        boolean scanOnFrameInteract,
        boolean confiscate,
        int cacheSize,
        int checksPerPlayerPerHour
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
                        DEFAULT_CHECKS_PER_PLAYER_PER_HOUR))
        );
    }

    /** Whether a player may have another new picture checked, given how many they had this hour. */
    public boolean allowsAnotherCheck(int checkedThisHour) {
        return checksPerPlayerPerHour == 0 || checkedThisHour < checksPerPlayerPerHour;
    }
}
