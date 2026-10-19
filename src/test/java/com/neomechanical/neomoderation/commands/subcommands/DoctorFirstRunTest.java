package com.neomechanical.neomoderation.commands.subcommands;

import com.neomechanical.neomoderation.testing.TestPlugin;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class DoctorFirstRunTest {
    @Test
    void blockOnlyFreshInstallIsHealthyRatherThanWarnedAsMissingActions() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("moderation.enabled", true);
        config.set("moderation.offline.bannedWords", List.of("badword"));

        List<String> lines = doctor(config);

        assertTrue(lines.contains("  OK Moderation: on"), String.join("\n", lines));
        assertTrue(lines.contains("  OK After a block: block only, nobody is muted, kicked or banned"),
                String.join("\n", lines));
        assertFalse(lines.stream().anyMatch(line -> line.startsWith("  Check After a block")));
    }

    @Test
    void doctorDistinguishesLocalMonitorFromCloudEnforce() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("moderation.enabled", true);
        config.set("moderation.mode", "monitor");
        config.set("moderation.cloudMode", "enforce");
        config.set("moderation.api.apiKey", "test-key");
        config.set("moderation.api.endpoint", "http://127.0.0.1:9/v1/events");

        List<String> lines = doctor(config);

        assertTrue(lines.contains("  Check Local rules: alerting staff only, run /nmod mode enforce to block"),
                String.join("\n", lines));
        assertTrue(lines.contains("  OK Cloud decisions: blocking"), String.join("\n", lines));
    }

    @Test
    void doctorDoesNotCallAnAntiSpamOnlyServerUnprotected() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("moderation.enabled", true);
        config.set("moderation.offline.enabled", false);

        List<String> lines = doctor(config);

        assertTrue(lines.contains("  Check Rules: no word or link rule; anti-spam still runs"),
                String.join("\n", lines));
    }

    @Test
    void everyLineIsWrittenOutWithNoKeyShowingThrough() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("moderation.enabled", false);
        config.set("moderation.alerts.discordWebhookUrl", "https://example.com/hook");

        String transcript = String.join("\n", doctor(config));

        assertTrue(transcript.contains("Fix Moderation: off, run /nmod on"), transcript);
        assertTrue(transcript.contains("Fix Discord alerts: the URL is not a Discord webhook"), transcript);
        assertFalse(transcript.contains("doctor."), transcript);
    }

    private static List<String> doctor(YamlConfiguration config) {
        CommandSender sender = mock(CommandSender.class);
        new DoctorCmd(TestPlugin.with(config)).execute(sender, "nmod", new String[0]);
        return TestPlugin.lines(sender);
    }
}
