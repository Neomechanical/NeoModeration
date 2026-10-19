package com.neomechanical.neomoderation.commands.subcommands;

import com.neomechanical.neomoderation.config.ModerationCategorySettings;
import com.neomechanical.neomoderation.testing.TestPlugin;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class TestCmdCoverageTest {
    @Test
    void disabledChatScannerDoesNotPreviewRulesThatCannotRun() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("moderation.enabled", true);
        config.set("moderation.chat.scanAsyncChat", false);
        config.set("moderation.offline.bannedWords", List.of("badword"));

        String transcript = test(config, "badword");

        assertTrue(transcript.contains("Chat checks are off (moderation.chat.scanAsyncChat), so nothing would be checked."),
                transcript);
        assertTrue(transcript.contains("Result: allowed"), transcript);
        assertFalse(transcript.contains("Local rules: caught"), transcript);
    }

    @Test
    void allCloudTextCategoriesOffIsReportedWithoutACloudRequest() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("moderation.enabled", true);
        config.set("moderation.offline.enabled", false);
        config.set("moderation.api.apiKey", "test-key");
        for (String category : ModerationCategorySettings.categoryKeys()) {
            config.set("moderation.categories." + category, false);
        }

        String transcript = test(config, "hello");

        assertTrue(transcript.contains("Cloud: every category is off"), transcript);
        assertTrue(transcript.contains("Result: allowed"), transcript);
    }

    @Test
    void aLocalCatchNamesTheWordAndWhatWouldHappen() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("moderation.enabled", true);
        config.set("moderation.mode", "enforce");
        config.set("moderation.offline.bannedWords", List.of("badword"));

        String transcript = test(config, "this is a badword");

        assertTrue(transcript.contains("Local rules: caught (banned word \"badword\")"), transcript);
        assertTrue(transcript.contains("Cloud: not asked, local rules already caught it"), transcript);
        assertTrue(transcript.contains("Result: blocked"), transcript);
        assertFalse(transcript.contains("test."), transcript);
    }

    private static String test(YamlConfiguration config, String message) {
        CommandSender sender = mock(CommandSender.class);
        new TestCmd(TestPlugin.with(config)).execute(sender, "nmod", ("test " + message).split(" "));
        return TestPlugin.transcript(sender);
    }
}
