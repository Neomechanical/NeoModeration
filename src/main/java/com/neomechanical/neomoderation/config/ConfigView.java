package com.neomechanical.neomoderation.config;

import java.util.List;
import java.util.Map;

/**
 * The whole configuration surface the settings records need, expressed without
 * naming a server platform: seven accessors, path-addressed with dots, each with
 * the caller's default. {@link BukkitConfigView} wraps the server's config.yml
 * and {@link MapConfigView} wraps parsed YAML, so settings parsing is testable
 * with no server.
 */
public interface ConfigView {

    /** Raw value at {@code path}, or {@code null} when absent. */
    Object get(String path);

    boolean getBoolean(String path, boolean defaultValue);

    String getString(String path, String defaultValue);

    int getInt(String path, int defaultValue);

    double getDouble(String path, double defaultValue);

    /** Never null; an absent or non-list value yields an empty list. */
    List<String> getStringList(String path);

    /** Never null; used for the action and escalation ladders. */
    List<Map<?, ?>> getMapList(String path);
}
