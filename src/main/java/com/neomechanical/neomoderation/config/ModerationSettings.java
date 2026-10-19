package com.neomechanical.neomoderation.config;



import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

public record ModerationSettings(
        boolean enabled,
        ModerationMode mode,
        ModerationMode cloudMode,
        ModerationApiSettings api,
        OfflineModerationSettings offline,
        ModerationCategorySettings categories,
        MapArtSettings mapArt,
        List<ModerationAction> actions,
        boolean scanAsyncChat,
        boolean failOpen,
        AlertSettings alerts,
        SpamSettings spam,
        StrikeSettings strikes,
        SurfaceSettings surfaces,
        CaseSettings cases,
        boolean chatCensorLocal
) {
    public record AlertSettings(boolean enabled, boolean includeMessage, String discordWebhookUrl) {
        public AlertSettings(boolean enabled, boolean includeMessage) {
            this(enabled, includeMessage, "");
        }

        public static AlertSettings from(ConfigView config) {
            String rawWebhook = config.getString("moderation.alerts.discordWebhookUrl", "");
            return new AlertSettings(
                    config.getBoolean("moderation.alerts.enabled", true),
                    config.getBoolean("moderation.alerts.includeMessage", true),
                    rawWebhook == null ? "" : rawWebhook.trim()
            );
        }
    }

    public static ModerationSettings from(ConfigView config) {
        return from(config, null);
    }

    public static ModerationSettings from(ConfigView config, Logger logger) {
        return new ModerationSettings(
                config.getBoolean("moderation.enabled", false),
                ModerationMode.parse(config.getString("moderation.mode", "enforce")),
                parseCloudMode(config),
                ModerationApiSettings.from(config),
                OfflineModerationSettings.from(config),
                ModerationCategorySettings.from(config),
                MapArtSettings.from(config),
                loadActions(config, logger),
                config.getBoolean("moderation.chat.scanAsyncChat", true),
                config.getBoolean("moderation.chat.failOpen", true),
                AlertSettings.from(config),
                SpamSettings.from(config),
                StrikeSettings.from(config),
                SurfaceSettings.from(config),
                CaseSettings.from(config),
                config.getBoolean("moderation.chat.censorLocalDetections", false)
        );
    }

    /**
     * Cloud decisions can run at a different confidence to local ones.
     *
     * <p>Local rules can block immediately without automatic punishment; cloud
     * category scores are model judgements the operator has not reviewed yet.
     * A fresh install only alerts on cloud decisions until the operator opts
     * into enforcement. That is the reason for a separate key.
     *
     * <p>When {@code moderation.cloudMode} is absent -- every config written
     * before 1.6.0 -- it follows {@code moderation.mode}, so upgrading changes
     * nothing.
     */
    private static ModerationMode parseCloudMode(ConfigView config) {
        Object raw = config.get("moderation.cloudMode");
        if (raw == null) {
            return ModerationMode.parse(config.getString("moderation.mode", "enforce"));
        }
        return ModerationMode.parse(String.valueOf(raw));
    }

    private static List<ModerationAction> loadActions(ConfigView config, Logger logger) {
        List<ModerationAction> loaded = new ArrayList<>();
        List<Map<?, ?>> rawActions = config.getMapList("moderation.actions");
        for (Map<?, ?> rawAction : rawActions) {
            ModerationAction.tryFrom(rawAction).ifPresentOrElse(
                    loaded::add,
                    () -> {
                        if (logger != null) {
                            logger.warning("Ignoring invalid moderation action in config.yml: " + rawAction);
                        }
                    }
            );
        }
        return List.copyOf(loaded);
    }
}
