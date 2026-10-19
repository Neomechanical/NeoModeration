package com.neomechanical.neomoderation.config;

import org.bukkit.configuration.ConfigurationSection;

import java.util.List;
import java.util.Map;

/**
 * A {@link ConfigView} backed by a Bukkit configuration, so the settings records
 * stay independent of Bukkit and testable without a server.
 *
 * <p>{@link #get} reads only what the server's config.yml says. Bukkit's own
 * {@code get(path)} falls back to the bundled config.yml, which made a removed
 * {@code moderation.cloudMode} read as the bundled "monitor" instead of following
 * {@code moderation.mode} as documented.</p>
 */
public final class BukkitConfigView implements ConfigView {

    private final ConfigurationSection config;

    public BukkitConfigView(ConfigurationSection config) {
        this.config = config;
    }

    @Override
    public Object get(String path) {
        return config.get(path, null);
    }

    @Override
    public boolean getBoolean(String path, boolean defaultValue) {
        return config.getBoolean(path, defaultValue);
    }

    @Override
    public String getString(String path, String defaultValue) {
        return config.getString(path, defaultValue);
    }

    @Override
    public int getInt(String path, int defaultValue) {
        return config.getInt(path, defaultValue);
    }

    @Override
    public double getDouble(String path, double defaultValue) {
        return config.getDouble(path, defaultValue);
    }

    @Override
    public List<String> getStringList(String path) {
        return config.getStringList(path);
    }

    @Override
    public List<Map<?, ?>> getMapList(String path) {
        return List.copyOf(config.getMapList(path));
    }
}
