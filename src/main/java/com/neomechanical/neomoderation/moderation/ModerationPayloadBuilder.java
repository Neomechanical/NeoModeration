package com.neomechanical.neomoderation.moderation;

import com.neomechanical.neomoderation.config.ModerationCategorySettings;

import java.util.Map;

public final class ModerationPayloadBuilder {
    private static final String ADAPTER = "neomoderation";
    private static final String SOURCE = "minecraft";
    /**
     * The platform contract accepts only general and nsfw. Ordinary chat must
     * remain general; only the explicitly NSFW map-art scanner may use nsfw.
     */
    public static final String GENERAL_BILLING_CONTENT_CLASS = "general";
    public static final String NSFW_BILLING_CONTENT_CLASS = "nsfw";

    private ModerationPayloadBuilder() {
    }

    public static String buildText(
            String playerName,
            String playerUuid,
            String message,
            ModerationCategorySettings categorySettings
    ) {
        return buildPayload(
                playerName,
                playerUuid,
                message,
                null,
                categorySettings,
                GENERAL_BILLING_CONTENT_CLASS
        );
    }

    public static String buildImage(
            String playerName,
            String playerUuid,
            String base64Image,
            ModerationCategorySettings categorySettings
    ) {
        return buildPayload(
                playerName,
                playerUuid,
                "",
                base64Image,
                categorySettings,
                NSFW_BILLING_CONTENT_CLASS
        );
    }

    private static String buildPayload(
            String playerName,
            String playerUuid,
            String text,
            String base64Image,
            ModerationCategorySettings categorySettings,
            String billingContentClass
    ) {
        StringBuilder thresholds = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, Double> entry : categorySettings.thresholds().entrySet()) {
            if (!first) {
                thresholds.append(',');
            }
            first = false;
            thresholds.append('"')
                    .append(JsonStrings.escape(ModerationCategorySettings.platformName(entry.getKey())))
                    .append("\":")
                    .append((double) entry.getValue());
        }

        String escapedPlayerName = JsonStrings.escape(playerName);
        String eventType = base64Image != null ? "map_art" : "chat_message";
        String attachmentsStr = base64Image != null
                ? "[{\"kind\":\"image\",\"inlineDataBase64\":\"" + base64Image + "\"}]"
                : "[]";

        return "{"
                + "\"mode\":\"sync\","
                + "\"event\":{"
                + "\"source\":\"" + SOURCE + "\","
                + "\"adapter\":\"" + ADAPTER + "\","
                + "\"eventType\":\"" + eventType + "\","
                + "\"actor\":{"
                + "\"externalId\":\"" + JsonStrings.escape(playerUuid) + "\","
                + "\"username\":\"" + escapedPlayerName + "\","
                + "\"displayName\":\"" + escapedPlayerName + "\""
                + "},"
                + "\"context\":{"
                + "\"scopeType\":\"minecraft_server\","
                + "\"tags\":[\"chat\"]"
                + "},"
                + "\"content\":{"
                + "\"text\":\"" + JsonStrings.escape(text) + "\","
                + "\"attachments\":" + attachmentsStr
                + "},"
                + "\"metadata\":{"
                + "\"platformPolicy\":{"
                + "\"thresholds\":{" + thresholds + "}"
                + "},"
                + "\"customInfo\":{"
                + "\"plugin\":\"NeoModeration\""
                + "}"
                + "}"
                + "},"
                + "\"options\":{"
                + "\"billing\":{\"contentClass\":\"" + billingContentClass + "\"},"
                + "\"persistence\":\"no_store\","
                + "\"includeAnalysisDetails\":false,"
                + "\"learning\":{\"enabled\":false,\"mode\":\"off\"}"
                + "}"
                + "}";
    }
}
