package com.neomechanical.neomoderation.config;

/**
 * Local case-history settings. {@code storeContent} keeps a short redacted
 * preview of the offending content; when false (default) only metadata is
 * stored, keeping the log privacy-clean. {@code retentionDays} and
 * {@code maxRows} bound SQLite growth on busy servers (0 disables either bound).
 */
public record CaseSettings(boolean enabled, boolean storeContent, int retentionDays, int maxRows) {
    public static final int DEFAULT_RETENTION_DAYS = 90;
    public static final int DEFAULT_MAX_ROWS = 50_000;

    public CaseSettings(boolean enabled, boolean storeContent) {
        this(enabled, storeContent, DEFAULT_RETENTION_DAYS, DEFAULT_MAX_ROWS);
    }

    public static CaseSettings from(ConfigView config) {
        return new CaseSettings(
                config.getBoolean("moderation.cases.enabled", true),
                config.getBoolean("moderation.cases.storeContent", false),
                Math.max(0, config.getInt("moderation.cases.retentionDays", DEFAULT_RETENTION_DAYS)),
                Math.max(0, config.getInt("moderation.cases.maxRows", DEFAULT_MAX_ROWS))
        );
    }
}
