package com.neomechanical.neomoderation.moderation;

import com.neomechanical.neomoderation.config.BukkitConfigView;
import com.neomechanical.neomoderation.config.ModerationCategorySettings;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatModerationResponseParserTest {
    @Test
    void detectsBlockedPlatformDecision() {
        assertTrue(parse("{\"decision\":{\"status\":\"blocked\",\"severity\":\"high\"}}", true));
        assertFalse(parse("{\"decision\":{\"status\":\"review\",\"severity\":\"medium\"}}", true));
        assertFalse(parse("{\"decision\":{\"status\":\"clean\",\"severity\":\"none\"}}", true));
    }

    @Test
    void detectsEnabledCategorySignalsOnly() {
        assertTrue(parse("{\"sexual\":true}", true));
        assertFalse(parse("{\"sexual\":true}", false));
        assertFalse(parse("{\"flagged\":false}", true));
        assertFalse(parse(null, true));
    }

    @Test
    void numericScoreAtOrAboveThresholdFlags() {
        String body = "{\"decision\":{\"status\":\"review\",\"severity\":\"medium\",\"confidence\":0.866,"
                + "\"categories\":{\"harassment\":0.8657,\"hate\":0.1}},"
                + "\"categories\":{\"harassment\":0.8657}}";
        YamlConfiguration config = categoriesConfig(true);
        assertTrue(flagged(body,
                ModerationCategorySettings.from(new BukkitConfigView(config))));
    }

    @Test
    void numericScoreBelowThresholdStaysClear() {
        String body = "{\"decision\":{\"status\":\"review\",\"severity\":\"low\"},"
                + "\"categories\":{\"harassment\":0.2}}";
        YamlConfiguration config = categoriesConfig(true);
        assertFalse(flagged(body,
                ModerationCategorySettings.from(new BukkitConfigView(config))));
    }

    @Test
    void numericScoreOnDisabledCategoryStaysClear() {
        String body = "{\"decision\":{\"status\":\"review\",\"severity\":\"medium\"},"
                + "\"categories\":{\"hate\":0.95}}";
        YamlConfiguration config = categoriesConfig(true);
        config.set("moderation.categories.hate", false);
        assertFalse(flagged(body,
                ModerationCategorySettings.from(new BukkitConfigView(config))));
    }

    @Test
    void sparseRootCategoriesDoNotHideNestedDecisionSignals() {
        var settings = ModerationCategorySettings.from(new BukkitConfigView(categoriesConfig(true)));
        assertTrue(ChatModerationResponseParser.parseResult(
                "{\"categories\":{\"sexual\":0.1},\"decision\":{\"categories\":{\"harassment\":0.99}}}",
                settings).isFlagged());
    }

    @Test
    void invalidSignalsAreUnavailableAndDisabledSignalsAreExplicitlyClear() {
        var enabled = ModerationCategorySettings.from(new BukkitConfigView(categoriesConfig(true)));
        for (String value : new String[]{"null", "{}", "\"true\"", "1.01", "-0.01"}) {
            org.junit.jupiter.api.Assertions.assertEquals(ModerationApiResult.Kind.TRANSIENT_TRANSPORT,
                    ChatModerationResponseParser.parseResult("{\"categories\":{\"sexual\":" + value + "}}", enabled).kind());
        }
        var disabled = ModerationCategorySettings.from(new BukkitConfigView(categoriesConfig(false)));
        org.junit.jupiter.api.Assertions.assertEquals(ModerationApiResult.Kind.CLEAR,
                ChatModerationResponseParser.parseResult("{\"categories\":{\"sexual\":0.99}}", disabled).kind());
        org.junit.jupiter.api.Assertions.assertEquals(ModerationApiResult.Kind.CLEAR,
                ChatModerationResponseParser.parseResult("{\"categories\":{\"sexual\":1.2e-8}}", enabled).kind());
    }

    @Test
    void scientificNotationUsesTheWholeScore() {
        assertFalse(parse("{\"categories\":{\"sexual\":1.2e-8}}", true));
        assertTrue(parse("{\"categories\":{\"sexual\":8e-1}}", true));
    }

    @Test
    void disabledCategoriesCannotBeReenabledByTheAggregateVerdict() {
        assertFalse(parse("{\"flagged\":true,\"decision\":{\"status\":\"blocked\"},"
                + "\"categories\":{\"sexual\":0.99}}", false));
    }

    @Test
    void nestedMetadataIsNotAModerationSignal() {
        assertFalse(parse("{\"decision\":{\"status\":\"clean\"},"
                + "\"metadata\":{\"sexual\":true,\"status\":\"blocked\"}}", true));
    }

    @Test
    void booleanCategoryFlagsDoNotOverrideACustomScoreThreshold() {
        YamlConfiguration config = categoriesConfig(true);
        config.set("moderation.categories.harassment", 0.95);
        assertFalse(flagged(
                "{\"categories\":{\"harassment\":true},\"category_scores\":{\"harassment\":0.8}}",
                ModerationCategorySettings.from(new BukkitConfigView(config))));
    }

    @Test
    void matchedCategoryReturnsSpecificTriggeredCategoryOrGenericPlatformFallback() {
        String body = "{\"decision\":{\"status\":\"blocked\",\"severity\":\"high\"},"
                + "\"categories\":{\"harassment\":0.91,\"hate\":0.1}}";
        ModerationCategorySettings settings = ModerationCategorySettings.from(new BukkitConfigView(categoriesConfig(true)));
        org.junit.jupiter.api.Assertions.assertEquals(
                "harassment",
                ChatModerationResponseParser.parseResult(body, settings).category()
        );
        org.junit.jupiter.api.Assertions.assertEquals(
                "platform:harassment",
                ModerationApiResult.flagged("harassment").reason()
        );
        org.junit.jupiter.api.Assertions.assertEquals(
                "platform",
                ModerationApiResult.flagged("").reason()
        );
    }

    private static boolean parse(String body, boolean sexual) {
        YamlConfiguration config = new YamlConfiguration();
        config.set("moderation.categories.sexual", sexual);
        config.set("moderation.categories.hate", false);
        config.set("moderation.categories.harassment", false);
        config.set("moderation.categories.violence", false);
        config.set("moderation.categories.scam", false);
        config.set("moderation.categories.spam", false);
        config.set("moderation.categories.illicit", false);
        config.set("moderation.categories.selfHarm", false);
        return flagged(body, ModerationCategorySettings.from(new BukkitConfigView(config)));
    }

    private static YamlConfiguration categoriesConfig(boolean harassment) {
        YamlConfiguration config = new YamlConfiguration();
        config.set("moderation.categories.sexual", false);
        config.set("moderation.categories.hate", true);
        config.set("moderation.categories.harassment", harassment);
        config.set("moderation.categories.violence", false);
        config.set("moderation.categories.scam", false);
        config.set("moderation.categories.spam", false);
        config.set("moderation.categories.illicit", false);
        config.set("moderation.categories.selfHarm", false);
        return config;
    }

    private static boolean flagged(String body, ModerationCategorySettings settings) {
        return ChatModerationResponseParser.parseResult(body, settings).isFlagged();
    }
}
