package com.neomechanical.neomoderation.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the enforcement defaults a fresh install actually receives.
 *
 * The local rules must work immediately, while unreviewed starter words must
 * not trigger a mute, global chat clear or kick ladder on a fresh install.
 */
class ShippedConfigDefaultsTest {

    private static ModerationSettings shipped() throws IOException {
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.loadFromString(Files.readString(Path.of("src/main/resources/config.yml")));
        } catch (org.bukkit.configuration.InvalidConfigurationException e) {
            throw new IllegalStateException("shipped config.yml is not valid YAML", e);
        }
        return ModerationSettings.from(new BukkitConfigView(config));
    }

    @Test
    void freshInstallEnforcesTheLocalRulesItShipsWith() throws IOException {
        assertEquals(ModerationMode.ENFORCE, shipped().mode(),
                "a fresh install must protect chat from the first minute, not merely observe it");
    }

    @Test
    void freshInstallOnlyMonitorsCloudDecisions() throws IOException {
        assertEquals(ModerationMode.MONITOR, shipped().cloudMode(),
                "cloud categories are a model's judgement the operator has not seen yet, "
                        + "so they alert until promoted with /nmod cloudmode enforce");
    }

    @Test
    void freshInstallHasModerationSwitchedOn() throws IOException {
        assertTrue(shipped().enabled());
    }

    @Test
    void firstLocalMatchBlocksWithoutPunishingOrClearingEveryoneElseChat() throws IOException {
        ModerationSettings settings = shipped();
        assertTrue(settings.actions().isEmpty(),
                "a starter word-list hit should block the message without clearing chat or muting a player");
        assertTrue(!settings.strikes().enabled(),
                "a new install should not kick a player after four unreviewed word-list matches");
    }

    @Test
    void aConfigWrittenBeforeStrikesExistedKeepsThemOff() {
        // Configs from before strikes existed have no strikes key; switching on a
        // kick ladder behind an upgrade would punish players nobody chose to punish.
        assertTrue(!ModerationSettings.from(new BukkitConfigView(new YamlConfiguration())).strikes().enabled());
    }

    @Test
    void shippedConfigDocumentsBothModeCommands() throws IOException {
        String raw = Files.readString(Path.of("src/main/resources/config.yml"));
        assertTrue(raw.contains("/nmod mode monitor"),
                "an operator who dislikes enforcement must be able to find the way back");
        assertTrue(raw.contains("/nmod cloudmode enforce"),
                "the config must name the command that promotes cloud decisions");
    }

    @Test
    void updateCheckIsOnByDefault() throws IOException {
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.loadFromString(Files.readString(Path.of("src/main/resources/config.yml")));
        } catch (org.bukkit.configuration.InvalidConfigurationException e) {
            throw new IllegalStateException(e);
        }
        assertTrue(config.getBoolean("updateCheck", false),
                "an install that never learns a newer matcher exists quietly misses more over time");
    }
}
