package com.neomechanical.neomoderation.moderation;

import com.neomechanical.neomoderation.config.BukkitConfigView;
import com.neomechanical.neomoderation.config.ModerationSettings;
import com.neomechanical.neomoderation.messages.MessageService;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Startup must describe the settings that actually ship, including the safe
 * block-only default, rather than a fixture that can drift from config.yml,
 * and say it in the server's language.
 */
class StartupSummaryTest {
    private static final String VERSION = "test-version";

    private static String summary(ModerationSettings settings, String locale) {
        YamlConfiguration english = YamlConfiguration.loadConfiguration(new File("src/main/resources/locale/en_US.yml"));
        YamlConfiguration active = YamlConfiguration.loadConfiguration(new File("src/main/resources/locale/" + locale + ".yml"));
        List<String> lines = StartupSummary.lines(settings, VERSION, MessageService.of(english, active, locale),
                mock(ConsoleCommandSender.class));
        assertTrue(lines.size() <= 8, "the console summary must stay short: " + lines);
        lines.forEach(line -> assertFalse(line.contains("{"), "unfilled placeholder in: " + line));
        return String.join("\n", lines);
    }

    private static ModerationSettings bundledDefaults() throws IOException {
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.loadFromString(Files.readString(Path.of("src/main/resources/config.yml")));
        } catch (org.bukkit.configuration.InvalidConfigurationException e) {
            throw new IllegalStateException("shipped config.yml is not valid YAML", e);
        }
        return ModerationSettings.from(new BukkitConfigView(config));
    }

    private static ModerationSettings settings(String... pairs) {
        YamlConfiguration config = new YamlConfiguration();
        for (int i = 0; i < pairs.length; i += 2) {
            config.set(pairs[i], pairs[i + 1].equals("true") || pairs[i + 1].equals("false")
                    ? Boolean.valueOf(pairs[i + 1]) : pairs[i + 1]);
        }
        return ModerationSettings.from(new BukkitConfigView(config));
    }

    @Test
    void aFreshInstallSaysWhatItBlocksAndThatTheCloudIsOff() throws IOException {
        String text = summary(bundledDefaults(), "en_US");

        assertTrue(text.contains("NeoModeration test-version is running."), text);
        assertTrue(text.contains("Local rules block matching messages"), text);
        assertTrue(text.contains("blocking ads for other servers"), text);
        assertTrue(text.contains("cloud checks (no key; run /nmod trial"), text);
        assertTrue(text.contains("/nmod test badword"), text);
        assertTrue(text.contains("/nmod status"), text);
    }

    @Test
    void monitorModeSaysNothingIsBlockedAndNamesTheWayOut() {
        String text = summary(settings("moderation.enabled", "true", "moderation.mode", "monitor"), "en_US");

        assertTrue(text.contains("alert staff without blocking"), text);
        assertTrue(text.contains("/nmod mode enforce"), text);
    }

    @Test
    void switchedOffSaysSoAndStops() {
        String text = summary(settings("moderation.enabled", "false"), "en_US");

        assertTrue(text.contains("Moderation is off"), text);
        assertFalse(text.contains("Running:"), text);
    }

    @Test
    void aSavedKeyIsNotCalledMissing() {
        String text = summary(settings("moderation.enabled", "true", "moderation.api.apiKey", "nm_test"), "en_US");

        assertFalse(text.contains("no key"), text);
        assertTrue(text.contains("cloud chat checks"), text);
    }

    @Test
    void theSummaryIsWrittenInTheServersLanguage() throws IOException {
        String text = summary(bundledDefaults(), "de_DE");

        assertFalse(text.contains("is running."), "German servers get a German summary: " + text);
    }
}
