package com.neomechanical.neomoderation.commands.subcommands;

import com.neomechanical.neomoderation.Permissions;
import com.neomechanical.neomoderation.config.ModerationCategorySettings;
import com.neomechanical.neomoderation.testing.TestPlugin;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

    @Test
    void aWordAnotherLanguageUsesEveryDaySaysWhereItPasses() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("moderation.enabled", true);
        config.set("moderation.offline.bannedWords", List.of("fag"));

        String transcript = test(config, "hvilket fag har du");

        assertTrue(transcript.contains("Local rules: caught (banned word \"fag\")"), transcript);
        assertTrue(transcript.contains("\"fag\" is an everyday word in Danish and Norwegian. Set "
                + "moderation.offline.trustPlayerLanguage to true"), transcript);
    }

    @Test
    void aMessageThatRepeatsAWordIsTestedToTheEnd() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("moderation.enabled", true);
        config.set("moderation.mode", "enforce");
        config.set("moderation.offline.bannedWords", List.of("fuck", "fag"));

        assertTrue(test(config, "fuck you fuck you").contains("Result: blocked"));
        assertTrue(test(config, "fag fag fag").contains("Result: blocked"));
    }

    @Test
    void adminTestShowsAllowCommandForFalseAlarmsButNotForLanguageWordsOrNonAdmins() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("moderation.enabled", true);
        config.set("moderation.mode", "enforce");
        config.set("moderation.offline.bannedWords", List.of("badword", "fag"));
        config.set("moderation.offline.bannedUrls", List.of("grabify.link"));

        String adminWord = testAs(config, "this is a badword", true);
        assertTrue(adminWord.contains("/nmod allow word add badword"), adminWord);

        String adminLink = testAs(config, "click grabify.link now", true);
        assertTrue(adminLink.contains("/nmod allow url add grabify.link"), adminLink);

        String moderatorWord = testAs(config, "this is a badword", false);
        assertFalse(moderatorWord.contains("/nmod allow word add"), moderatorWord);

        String languageWord = testAs(config, "hvilket fag har du", true);
        assertFalse(languageWord.contains("/nmod allow word add fag"), languageWord);
    }

    private static String test(YamlConfiguration config, String message) {
        return testAs(config, message, false);
    }

    private static String testAs(YamlConfiguration config, String message, boolean admin) {
        CommandSender sender = mock(CommandSender.class);
        when(sender.hasPermission(Permissions.ADMIN)).thenReturn(admin);
        new TestCmd(TestPlugin.with(config)).execute(sender, "nmod", ("test " + message).split(" "));
        return TestPlugin.transcript(sender);
    }
}
