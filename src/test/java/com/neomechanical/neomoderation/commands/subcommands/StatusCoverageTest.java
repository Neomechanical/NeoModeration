package com.neomechanical.neomoderation.commands.subcommands;

import com.neomechanical.neomoderation.Permissions;
import com.neomechanical.neomoderation.testing.TestPlugin;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StatusCoverageTest {
    @Test
    void statusNamesCloudAndLocalModesWithoutInventingActiveLocalChecks() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("moderation.enabled", true);
        config.set("moderation.mode", "monitor");
        config.set("moderation.cloudMode", "enforce");
        config.set("moderation.offline.enabled", false);
        config.set("moderation.spam.enabled", false);
        config.set("moderation.api.apiKey", "test-key");

        List<String> lines = status(config);

        assertTrue(lines.contains("Local rules: nothing to check"), String.join("\n", lines));
        assertTrue(lines.stream().anyMatch(line -> line.startsWith("Cloud: blocking, categories on: ")),
                String.join("\n", lines));
    }

    @Test
    void keylessStatusDistinguishesLocalProtectionFromNoChecks() {
        YamlConfiguration local = new YamlConfiguration();
        local.set("moderation.enabled", true);
        local.set("moderation.offline.bannedWords", List.of("badword"));
        List<String> protectedLines = status(local);
        assertTrue(protectedLines.contains("Local rules: blocking"), String.join("\n", protectedLines));
        assertTrue(protectedLines.contains("Cloud: no key. Run /nmod trial for a free 14-day trial."),
                String.join("\n", protectedLines));

        YamlConfiguration inert = new YamlConfiguration();
        inert.set("moderation.enabled", true);
        inert.set("moderation.offline.enabled", false);
        inert.set("moderation.spam.enabled", false);
        List<String> inertLines = status(inert);
        assertTrue(inertLines.contains("Local rules: nothing to check"), String.join("\n", inertLines));
    }

    @Test
    void statusSaysWhetherPlayersGameLanguagesAreTrusted() {
        assertTrue(status(new YamlConfiguration()).contains(
                "Players' game languages: not trusted (moderation.offline.trustPlayerLanguage)"));
        YamlConfiguration trusting = new YamlConfiguration();
        trusting.set("moderation.offline.trustPlayerLanguage", true);
        assertTrue(status(trusting).contains(
                "Players' game languages: trusted (moderation.offline.trustPlayerLanguage)"));
    }

    @Test
    void theHeaderAndEveryLineReadAsWords() {
        String transcript = String.join("\n", status(new YamlConfiguration()));

        assertTrue(transcript.startsWith("NeoModeration " + TestPlugin.VERSION + ": local rules "), transcript);
        assertFalse(transcript.contains("status."), transcript);
        assertFalse(transcript.contains("label."), transcript);
    }

    @Test
    void aModeratorIsNotPointedAtTheTrialOnlyAdminsCanStart() {
        YamlConfiguration local = new YamlConfiguration();
        local.set("moderation.enabled", true);
        CommandSender moderator = mock(CommandSender.class);
        when(moderator.hasPermission(Permissions.MODERATE)).thenReturn(true);

        new StatusCmd(TestPlugin.with(local)).execute(moderator, "nmod", new String[0]);

        List<String> lines = TestPlugin.lines(moderator);
        assertTrue(lines.contains("Cloud: no key."), String.join("\n", lines));
        assertFalse(String.join("\n", lines).contains("/nmod trial"), String.join("\n", lines));
    }

    private static List<String> status(YamlConfiguration config) {
        CommandSender sender = mock(CommandSender.class);
        when(sender.hasPermission(Permissions.ADMIN)).thenReturn(true);
        new StatusCmd(TestPlugin.with(config)).execute(sender, "nmod", new String[0]);
        return TestPlugin.lines(sender);
    }
}
