package com.neomechanical.neomoderation.commands.subcommands;

import com.neomechanical.neomoderation.testing.TestPlugin;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

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
        assertTrue(lines.stream().anyMatch(line -> line.startsWith("Cloud: ") && line.endsWith(", blocking")),
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
    void theHeaderAndEveryLineReadAsWords() {
        String transcript = String.join("\n", status(new YamlConfiguration()));

        assertTrue(transcript.startsWith("NeoModeration " + TestPlugin.VERSION + ": local rules "), transcript);
        assertFalse(transcript.contains("status."), transcript);
        assertFalse(transcript.contains("label."), transcript);
    }

    private static List<String> status(YamlConfiguration config) {
        CommandSender sender = mock(CommandSender.class);
        new StatusCmd(TestPlugin.with(config)).execute(sender, "nmod", new String[0]);
        return TestPlugin.lines(sender);
    }
}
