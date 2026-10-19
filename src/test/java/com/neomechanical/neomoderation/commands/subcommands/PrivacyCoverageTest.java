package com.neomechanical.neomoderation.commands.subcommands;

import com.neomechanical.neomoderation.config.ModerationCategorySettings;
import com.neomechanical.neomoderation.testing.TestPlugin;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class PrivacyCoverageTest {
    @Test
    void savedKeyWithoutArmedChecksDoesNotClaimContentLeavesTheServer() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("moderation.enabled", true);
        config.set("moderation.offline.enabled", false);
        config.set("moderation.spam.enabled", false);
        config.set("moderation.api.apiKey", "test-key");
        config.set("moderation.mapArt.enabled", false);
        for (String category : ModerationCategorySettings.categoryKeys()) {
            config.set("moderation.categories." + category, false);
        }

        String transcript = privacy(config);

        assertTrue(transcript.contains("A key is saved but no cloud check is on, so nothing is sent."), transcript);
        assertFalse(transcript.contains("Cloud checks send"), transcript);
    }

    @Test
    void cloudChecksNameWhatLeavesTheServerAndWhere() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("moderation.enabled", true);
        config.set("moderation.api.apiKey", "test-key");
        config.set("moderation.alerts.discordWebhookUrl", "https://discord.com/api/webhooks/1/abc");

        String transcript = privacy(config);

        assertTrue(transcript.contains("Cloud checks send each chat message and map picture to "
                + "https://api.neomechanical.com/v1/events, with the player's name and UUID."), transcript);
        assertTrue(transcript.contains("Discord alerts send the player and the rule to your webhook, "
                + "with a preview of the message."), transcript);
        assertFalse(transcript.contains("privacy."), transcript);
    }

    private static String privacy(YamlConfiguration config) {
        CommandSender sender = mock(CommandSender.class);
        new PrivacyCmd(TestPlugin.with(config)).execute(sender, "nmod", new String[0]);
        return TestPlugin.transcript(sender);
    }
}
