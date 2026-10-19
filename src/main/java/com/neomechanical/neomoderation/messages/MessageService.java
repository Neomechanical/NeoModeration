package com.neomechanical.neomoderation.messages;

import net.md_5.bungee.api.chat.BaseComponent;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class MessageService {
    private static final String DEFAULT_LOCALE = "en_US";
    public static final List<String> BUNDLED_LOCALES = List.of(
            "en_US", "es_ES", "fr_FR", "de_DE", "pt_BR", "tr_TR"
    );

    private final YamlConfiguration fallback;
    private final YamlConfiguration active;

    private MessageService(YamlConfiguration fallback, YamlConfiguration active) {
        this.fallback = fallback;
        this.active = active;
    }

    public static MessageService load(JavaPlugin plugin, String locale) {
        java.util.Map<String, YamlConfiguration> bundledConfigs = new java.util.LinkedHashMap<>();
        for (String code : BUNDLED_LOCALES) {
            bundledConfigs.put(code, ensureLocale(plugin, code));
        }
        YamlConfiguration fallback = bundledConfigs.get(DEFAULT_LOCALE);
        String resolved = resolveLocaleCode(locale);
        YamlConfiguration active = bundledConfigs.get(resolved);
        if (active == null) {
            File localeDir = new File(plugin.getDataFolder(), "locale");
            File requestedFile = new File(localeDir, resolved + ".yml");
            active = requestedFile.exists()
                    ? YamlConfiguration.loadConfiguration(requestedFile)
                    : fallback;
        }
        return new MessageService(fallback, active);
    }

    /**
     * Resolves short, lowercase, or hyphenated locale codes (such as {@code fr_fr},
     * {@code es}, {@code de-DE}) to their canonical bundled locale identifier.
     */
    public static String resolveLocaleCode(String locale) {
        if (locale == null || locale.isBlank()) {
            return DEFAULT_LOCALE;
        }
        String normalized = locale.trim().replace('-', '_').toLowerCase(java.util.Locale.ROOT);
        return switch (normalized) {
            case "en", "en_us", "en_gb", "en_au", "en_ca" -> "en_US";
            case "es", "es_es", "es_mx", "es_ar", "es_419" -> "es_ES";
            case "fr", "fr_fr", "fr_ca", "fr_be", "fr_ch" -> "fr_FR";
            case "de", "de_de", "de_at", "de_ch" -> "de_DE";
            case "pt", "pt_br", "pt_pt" -> "pt_BR";
            case "tr", "tr_tr" -> "tr_TR";
            default -> locale.trim();
        };
    }

    public void send(CommandSender sender, String key) {
        send(sender, key, Map.of());
    }

    public void send(CommandSender sender, String key, Map<String, String> placeholders) {
        sender.sendMessage(format(key, placeholders));
    }

    public String format(String key, Map<String, String> placeholders) {
        String message = active.getString(key, fallback.getString(key, key));
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            message = message.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        // The shared chat prefix is a locale entry so every line stays consistent
        // and rebrandable from one place.
        message = message.replace("{prefix}", active.getString("prefix", fallback.getString("prefix", "")));
        return ChatColor.translateAlternateColorCodes('&', message);
    }

    /**
     * Shared dashboard header line (version, mode, cloud state, detection
     * count). Every command output opens with this so the plugin reads as one
     * surface; all wording stays in the {@code help.dashboard} locale key.
     */
    public String dashboardLine(String version, String mode, String cloud, long total) {
        return format("help.dashboard", Map.of(
                "version", version,
                "mode", mode,
                "cloud", cloud,
                "total", String.valueOf(total)));
    }

    /** Sends {@link #dashboardLine} as a plain-text line. */
    public void sendDashboard(CommandSender sender, String version, String mode, String cloud, long total) {
        sender.sendMessage(dashboardLine(version, mode, cloud, total));
    }

    /** Shared closing divider; every command output ends with this. */
    public String footerLine() {
        return format("help.footer", Map.of());
    }

    /** Sends {@link #footerLine} as a plain-text line. */
    public void sendFooter(CommandSender sender) {
        sender.sendMessage(footerLine());
    }

    /**
     * Sends a pre-rendered menu page: component lines to players (clickable),
     * legacy-text lines to console and other non-player senders.
     */
    public void sendMenu(CommandSender sender, List<MenuRenderer.Line> lines) {
        if (sender instanceof Player player && player.spigot() != null) {
            for (MenuRenderer.Line line : lines) {
                player.spigot().sendMessage(line.player());
            }
            return;
        }
        for (MenuRenderer.Line line : lines) {
            sender.sendMessage(line.console());
        }
    }

    /**
     * Copy bundled locale only when missing, then merge any new default keys without
     * overwriting operator customizations.
     */
    private static YamlConfiguration ensureLocale(JavaPlugin plugin, String locale) {
        File target = new File(plugin.getDataFolder(), "locale/" + locale + ".yml");
        if (!target.exists()) {
            plugin.saveResource("locale/" + locale + ".yml", false);
            return YamlConfiguration.loadConfiguration(target);
        }
        YamlConfiguration onDisk = YamlConfiguration.loadConfiguration(target);
        YamlConfiguration bundled = loadBundled(plugin, locale);
        if (bundled == null) {
            plugin.getLogger().warning("Could not load bundled locale " + locale
                    + "; keeping the existing locale values.");
            return onDisk;
        }
        boolean changed = mergeBundledLocale(locale, onDisk, bundled);
        if (changed) {
            try {
                onDisk.save(target);
            } catch (IOException e) {
                plugin.getLogger().warning("Could not persist locale " + locale
                        + " migration to " + target + ": " + e.getMessage()
                        + ". The merged values remain active until restart.");
            }
        }
        return onDisk;
    }

    /**
     * Adds new keys and migrates only exact, known bundled values from the previous
     * release. Any operator-edited value remains untouched.
     */
    static boolean mergeBundledLocale(
            String locale,
            YamlConfiguration onDisk,
            YamlConfiguration bundled
    ) {
        boolean changed = false;
        Set<String> keys = bundled.getKeys(true);
        for (String key : keys) {
            if (bundled.isConfigurationSection(key)) {
                continue;
            }
            if (!onDisk.contains(key)) {
                onDisk.set(key, bundled.get(key));
                changed = true;
            }
        }
        if (LocaleLegacyMigrations.apply(locale, onDisk, bundled)) {
            changed = true;
        }
        return changed;
    }

    private static YamlConfiguration loadBundled(JavaPlugin plugin, String locale) {
        try (InputStream stream = plugin.getResource("locale/" + locale + ".yml")) {
            if (stream == null) {
                return null;
            }
            return YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
        } catch (IOException e) {
            plugin.getLogger().warning("Could not read bundled locale " + locale + ": " + e.getMessage());
            return null;
        }
    }
}
