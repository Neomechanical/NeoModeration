package com.neomechanical.neomoderation.config;

import com.neomechanical.neomoderation.config.BukkitConfigView;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModerationApiSettingsTest {
    @Test
    void normalizesLegacyEndpointToEventsRoute() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("moderation.api.endpoint", "https://api.neomechanical.com/v1/moderation/chat");

        ModerationApiSettings settings = ModerationApiSettings.from(new BukkitConfigView(config));

        assertEquals("https://api.neomechanical.com/v1/events", settings.endpoint());
    }

    @Test
    void keepsCustomEndpoint() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("moderation.api.endpoint", "https://moderation.example.test/v1/events");

        ModerationApiSettings settings = ModerationApiSettings.from(new BukkitConfigView(config));

        assertEquals("https://moderation.example.test/v1/events", settings.endpoint());
    }

    @Test
    void resolvesSiblingRoutesFromDefaultLegacyAndCustomEndpoints() {
        assertEquals("https://api.neomechanical.com/v1/workspace/usage",
                ModerationApiSettings.resolveSiblingEndpoint(null, "/workspace/usage"));
        assertEquals("https://api.neomechanical.com/v1/trial/neomoderation",
                ModerationApiSettings.resolveSiblingEndpoint(
                        "https://api.neomechanical.com/v1/moderation/chat/", "/trial/neomoderation"));
        assertEquals("https://moderation.example.test/v1/workspace/usage",
                ModerationApiSettings.resolveSiblingEndpoint(
                        "https://moderation.example.test/v1/events", "/workspace/usage"));
    }
}
