package com.neomechanical.neomoderation.messages;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps the locale files in {@code plugins/NeoModeration/locale} current.
 *
 * <p>A missing file is written from the jar. In an existing file a missing key is
 * added, and a text the server never edited follows the bundled text when an
 * update changes it. "Never edited" means it matches a text some release shipped:
 * {@code locale/history/<code>.txt} in the jar fingerprints every text up to
 * 1.7.0, and {@code .shipped.yml} beside the files records what this install
 * wrote since. A text the server edited is never touched. A key the plugin no
 * longer uses is removed only while it still holds a shipped text.</p>
 */
final class LocaleStore {
    private static final Pattern PARSER_POSITION = Pattern.compile("line \\d+, column \\d+");
    private static final String SHIPPED_FILE = ".shipped.yml";

    private LocaleStore() {
    }

    /**
     * Writes or upgrades every bundled locale file. Run at startup and on /nmod reload.
     * Returns the locales whose file holds a text the server wrote itself.
     */
    static Set<String> prepare(JavaPlugin plugin) {
        File directory = new File(plugin.getDataFolder(), "locale");
        File shippedFile = new File(directory, SHIPPED_FILE);
        YamlConfiguration shipped = YamlConfiguration.loadConfiguration(shippedFile);
        boolean shippedChanged = false;
        Set<String> edited = new HashSet<>();
        for (String code : MessageService.BUNDLED_LOCALES) {
            YamlConfiguration bundled = bundled(plugin, code);
            if (bundled == null) {
                plugin.getLogger().warning("The jar has no locale " + code + "; keeping the file on disk.");
                continue;
            }
            File target = new File(directory, code + ".yml");
            if (!target.exists()) {
                plugin.saveResource("locale/" + code + ".yml", false);
            } else {
                YamlConfiguration onDisk = read(plugin, target, true);
                if (onDisk == null) {
                    // Unreadable: never overwrite it, and leave its fingerprints for when it is fixed.
                    edited.add(code);
                    continue;
                }
                Map<String, Set<String>> known = history(plugin, code);
                addFingerprints(known, shipped, code);
                if (merge(onDisk, bundled, (key, value) -> known.getOrDefault(key, Set.of()).contains(fingerprint(value)))) {
                    save(plugin, onDisk, target);
                }
                if (holdsOwnTexts(onDisk, bundled)) {
                    edited.add(code);
                }
            }
            shippedChanged |= record(shipped, code, bundled);
        }
        if (shippedChanged) {
            shipped.options().header("Written by NeoModeration so an update can replace texts you have not "
                    + "edited. Do not edit.");
            save(plugin, shipped, shippedFile);
        }
        return edited;
    }

    /**
     * A locale file from disk, or {@code null} when it is not valid YAML. A broken
     * file is left exactly as it is, so a typo never costs a server its own texts;
     * {@code report} says it in the console (once, from {@link #prepare}).
     */
    static YamlConfiguration read(JavaPlugin plugin, File file, boolean report) {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
            return yaml;
        } catch (IOException | InvalidConfigurationException e) {
            if (!report) {
                return null;
            }
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage().strip();
            int lineBreak = reason.indexOf('\n');
            // The parser names where it gave up last: "line 4, column 5".
            Matcher where = PARSER_POSITION.matcher(reason);
            String position = null;
            while (where.find()) {
                position = where.group();
            }
            plugin.getLogger().warning("Could not read locale/" + file.getName()
                    + (position == null ? "" : " (" + position + ")")
                    + ", so it was left as it is and the built-in texts are used until it is fixed: "
                    + (lineBreak > 0 ? reason.substring(0, lineBreak) : reason));
            return null;
        }
    }

    /** Whether {@code onDisk} still holds a text, at a key the jar ships, that differs from the jar's. */
    static boolean holdsOwnTexts(YamlConfiguration onDisk, YamlConfiguration bundled) {
        for (String key : bundled.getKeys(true)) {
            if (!bundled.isConfigurationSection(key) && onDisk.isSet(key)
                    && !Objects.equals(onDisk.getString(key), bundled.getString(key))) {
                return true;
            }
        }
        return false;
    }

    /** The bundled locale from the jar, or {@code null} when the jar lacks it. */
    static YamlConfiguration bundled(JavaPlugin plugin, String code) {
        try (InputStream stream = plugin.getResource("locale/" + code + ".yml")) {
            if (stream == null) {
                return null;
            }
            return YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Brings {@code onDisk} up to {@code bundled}. Returns whether anything changed.
     * {@code shippedText} tells whether a value at a key is one the plugin wrote.
     */
    static boolean merge(YamlConfiguration onDisk, YamlConfiguration bundled, BiPredicate<String, String> shippedText) {
        boolean changed = false;
        for (String key : bundled.getKeys(true)) {
            if (bundled.isConfigurationSection(key)) {
                continue;
            }
            String latest = bundled.getString(key);
            if (!onDisk.isSet(key)) {
                onDisk.set(key, latest);
                changed = true;
                continue;
            }
            String current = onDisk.getString(key);
            if (current != null && !current.equals(latest) && shippedText.test(key, current)) {
                onDisk.set(key, latest);
                changed = true;
            }
        }
        for (String key : onDisk.getKeys(true)) {
            if (onDisk.isConfigurationSection(key) || bundled.isSet(key)) {
                continue;
            }
            String current = onDisk.getString(key);
            if (current != null && shippedText.test(key, current)) {
                onDisk.set(key, null);
                changed = true;
            }
        }
        return changed;
    }

    static String fingerprint(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Fingerprints of every text the jar's history file says a release shipped, by key. */
    static Map<String, Set<String>> history(JavaPlugin plugin, String code) {
        Map<String, Set<String>> known = new HashMap<>();
        try (InputStream stream = plugin.getResource("locale/history/" + code + ".txt")) {
            if (stream == null) {
                return known;
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                int tab = line.indexOf('\t');
                if (line.startsWith("#") || tab <= 0) {
                    continue;
                }
                Set<String> hashes = known.computeIfAbsent(line.substring(0, tab), ignored -> new HashSet<>());
                for (String hash : line.substring(tab + 1).split(",")) {
                    hashes.add(hash.trim());
                }
            }
        } catch (IOException ignored) {
            // Without history only .shipped.yml can prove a text unedited; nothing is lost.
        }
        return known;
    }

    private static void addFingerprints(Map<String, Set<String>> known, YamlConfiguration shipped, String code) {
        if (!shipped.isConfigurationSection(code)) {
            return;
        }
        for (String key : shipped.getConfigurationSection(code).getKeys(false)) {
            String hash = shipped.getString(code + "." + key);
            if (hash != null) {
                known.computeIfAbsent(key.replace('|', '.'), ignored -> new HashSet<>()).add(hash);
            }
        }
    }

    /** Records the fingerprints of the bundled texts this install now holds. */
    private static boolean record(YamlConfiguration shipped, String code, YamlConfiguration bundled) {
        boolean changed = false;
        for (String key : bundled.getKeys(true)) {
            if (bundled.isConfigurationSection(key)) {
                continue;
            }
            // Dots would nest the fingerprint file; keys are stored flat with '|'.
            String path = code + "." + key.replace('.', '|');
            String hash = fingerprint(bundled.getString(key, ""));
            if (!hash.equals(shipped.getString(path))) {
                shipped.set(path, hash);
                changed = true;
            }
        }
        return changed;
    }

    private static void save(JavaPlugin plugin, YamlConfiguration yaml, File target) {
        try {
            yaml.save(target);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save " + target.getName() + ": " + e.getMessage());
        }
    }
}
