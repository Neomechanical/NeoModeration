package com.neomechanical.neomoderation.config;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Upgrade safety for the split enforcement level introduced in 1.6.0.
 *
 * <p>Every config written before 1.6.0 has no {@code cloudMode} key. Such an
 * install must keep behaving exactly as it did, so the absent key follows
 * {@code mode} rather than defaulting to anything of its own.
 */
class CloudModeTest {

    private static ModerationSettings parse(YamlConfiguration config) {
        return ModerationSettings.from(new BukkitConfigView(config));
    }

    @Test
    void absentCloudModeFollowsTheLocalModeSoUpgradesChangeNothing() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("moderation.enabled", true);
        config.set("moderation.mode", "monitor");

        assertEquals(ModerationMode.MONITOR, parse(config).cloudMode());
    }

    @Test
    void absentCloudModeFollowsEnforceToo() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("moderation.enabled", true);
        config.set("moderation.mode", "enforce");

        assertEquals(ModerationMode.ENFORCE, parse(config).cloudMode());
    }

    @Test
    void absentCloudModeFollowsTheLocalModeEvenWithTheBundledDefaultsLoaded()
            throws IOException, InvalidConfigurationException {
        // The plugin loads the bundled config.yml as defaults, and it says
        // cloudMode: monitor. A server that removed the key must still follow mode.
        YamlConfiguration bundled = new YamlConfiguration();
        bundled.loadFromString(Files.readString(Path.of("src/main/resources/config.yml")));
        YamlConfiguration config = new YamlConfiguration();
        config.setDefaults(bundled);
        config.set("moderation.enabled", true);
        config.set("moderation.mode", "enforce");

        assertEquals(ModerationMode.ENFORCE, parse(config).cloudMode());
    }

    @Test
    void anEmptyConfigStillEnforcesBothForUpgradeSafety() {
        // A pre-1.3.0 config has no mode key at all; it must not silently weaken.
        assertEquals(ModerationMode.ENFORCE, parse(new YamlConfiguration()).mode());
        assertEquals(ModerationMode.ENFORCE, parse(new YamlConfiguration()).cloudMode());
    }

    @Test
    void explicitCloudModeOverridesTheLocalMode() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("moderation.enabled", true);
        config.set("moderation.mode", "enforce");
        config.set("moderation.cloudMode", "monitor");

        ModerationSettings settings = parse(config);
        assertEquals(ModerationMode.ENFORCE, settings.mode());
        assertEquals(ModerationMode.MONITOR, settings.cloudMode());
    }

    @Test
    void anUnrecognisedCloudModeParsesAsEnforceLikeTheLocalOne() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("moderation.mode", "monitor");
        config.set("moderation.cloudMode", "banana");

        assertEquals(ModerationMode.ENFORCE, parse(config).cloudMode());
    }
}
