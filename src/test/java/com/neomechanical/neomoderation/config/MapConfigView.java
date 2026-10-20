package com.neomechanical.neomoderation.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** A {@link ConfigView} over a parsed YAML map, for tests that build settings without Bukkit. */
public final class MapConfigView implements ConfigView {

    private final Map<String, Object> root;

    public MapConfigView(Map<String, Object> root) {
        this.root = root == null ? Map.of() : root;
    }

    @Override
    public Object get(String path) {
        Object current = root;
        for (String segment : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(segment);
            if (current == null) {
                return null;
            }
        }
        return current == root ? null : current;
    }

    @Override
    public boolean getBoolean(String path, boolean defaultValue) {
        Object raw = get(path);
        if (raw instanceof Boolean value) {
            return value;
        }
        if (raw instanceof String text) {
            String normalized = text.trim().toLowerCase(Locale.ROOT);
            if ("true".equals(normalized)) {
                return true;
            }
            if ("false".equals(normalized)) {
                return false;
            }
        }
        return defaultValue;
    }

    @Override
    public String getString(String path, String defaultValue) {
        Object raw = get(path);
        if (raw == null) {
            return defaultValue;
        }
        return raw instanceof String text ? text : String.valueOf(raw);
    }

    @Override
    public int getInt(String path, int defaultValue) {
        Object raw = get(path);
        if (raw instanceof Number number) {
            return number.intValue();
        }
        if (raw instanceof String text) {
            try {
                return (int) Double.parseDouble(text.trim());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    @Override
    public double getDouble(String path, double defaultValue) {
        Object raw = get(path);
        if (raw instanceof Number number) {
            return number.doubleValue();
        }
        if (raw instanceof String text) {
            try {
                return Double.parseDouble(text.trim());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    @Override
    public List<String> getStringList(String path) {
        Object raw = get(path);
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<String> values = new ArrayList<>(list.size());
        for (Object entry : list) {
            if (entry != null) {
                values.add(entry instanceof String text ? text : String.valueOf(entry));
            }
        }
        return List.copyOf(values);
    }

    @Override
    public List<Map<?, ?>> getMapList(String path) {
        Object raw = get(path);
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Map<?, ?>> values = new ArrayList<>(list.size());
        for (Object entry : list) {
            if (entry instanceof Map<?, ?> map) {
                values.add(map);
            }
        }
        return List.copyOf(values);
    }
}
