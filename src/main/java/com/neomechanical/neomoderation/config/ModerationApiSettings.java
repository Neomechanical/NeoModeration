package com.neomechanical.neomoderation.config;



public record ModerationApiSettings(
        String endpoint,
        String apiKey,
        int connectTimeoutMs,
        int readTimeoutMs
) {
    public static final String DEFAULT_ENDPOINT = "https://api.neomechanical.com/v1/events";
    private static final String LEGACY_DEFAULT_ENDPOINT = "https://api.neomechanical.com/v1/moderation/chat";

    public static ModerationApiSettings from(ConfigView config) {
        return new ModerationApiSettings(
                normalizeEndpoint(config.getString("moderation.api.endpoint", DEFAULT_ENDPOINT)),
                config.getString(ConfigPaths.API_KEY, ""),
                config.getInt("moderation.api.connectTimeoutMs", 2000),
                config.getInt("moderation.api.readTimeoutMs", 2000)
        );
    }

    /**
     * Derives a sibling platform endpoint (such as {@code /trial} or
     * {@code /workspace/usage}) by replacing the final path segment of the
     * configured moderation endpoint.
     */
    public static String resolveSiblingEndpoint(String endpoint, String siblingPath) {
        String normalized = normalizeEndpoint(endpoint);
        int slash = normalized.lastIndexOf('/');
        String base = slash > "https://".length() ? normalized.substring(0, slash) : normalized;
        String suffix = siblingPath.startsWith("/") ? siblingPath : "/" + siblingPath;
        return base + suffix;
    }

    private static String normalizeEndpoint(String configuredEndpoint) {
        if (configuredEndpoint == null || configuredEndpoint.isBlank()) {
            return DEFAULT_ENDPOINT;
        }
        String trimmed = configuredEndpoint.trim();
        while (trimmed.endsWith("/") && trimmed.length() > "https://".length()) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (LEGACY_DEFAULT_ENDPOINT.equals(trimmed)) {
            return DEFAULT_ENDPOINT;
        }
        return trimmed;
    }
}
