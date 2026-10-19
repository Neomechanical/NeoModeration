package com.neomechanical.neomoderation.messages;

import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Every word a player or admin reads comes from here, from the locale files.
 *
 * <p>Messages go out in the server's {@code locale}. With
 * {@code perPlayerLanguage} on, a player whose Minecraft language is one of the
 * bundled locales reads that language instead; the console and every other
 * player read the server's. A key missing from a locale falls back to English.</p>
 */
public final class MessageService {
    public static final String DEFAULT_LOCALE = "en_US";
    public static final List<String> BUNDLED_LOCALES = List.of(
            "en_US", "es_ES", "fr_FR", "de_DE", "pt_BR", "tr_TR"
    );
    private static final Pattern COLOR_CODE = Pattern.compile("(?i)§[0-9a-fk-orx]");

    private final YamlConfiguration fallback;
    private final YamlConfiguration active;
    private final String activeCode;
    private final Map<String, YamlConfiguration> playerLocales;

    private MessageService(YamlConfiguration fallback, YamlConfiguration active, String activeCode,
                           Map<String, YamlConfiguration> playerLocales) {
        this.fallback = fallback;
        this.active = active;
        this.activeCode = activeCode;
        this.playerLocales = playerLocales;
    }

    /** Messages from one locale over the English fallback, without files; for tools and tests. */
    public static MessageService of(YamlConfiguration english, YamlConfiguration active, String activeCode) {
        return new MessageService(english, active, activeCode, Map.of());
    }

    /** Reads the locale files from disk; {@link LocaleStore#prepare} must have run once before. */
    public static MessageService load(JavaPlugin plugin, String locale, boolean perPlayerLanguage) {
        File directory = new File(plugin.getDataFolder(), "locale");
        Map<String, YamlConfiguration> loaded = new LinkedHashMap<>();
        for (String code : BUNDLED_LOCALES) {
            File file = new File(directory, code + ".yml");
            YamlConfiguration yaml = file.exists() ? YamlConfiguration.loadConfiguration(file) : LocaleStore.bundled(plugin, code);
            if (yaml != null) {
                loaded.put(code, yaml);
            }
        }
        YamlConfiguration fallback = loaded.getOrDefault(DEFAULT_LOCALE, new YamlConfiguration());
        String resolved = resolveLocaleCode(locale);
        YamlConfiguration active = loaded.get(resolved);
        if (active == null) {
            File custom = new File(directory, resolved + ".yml");
            active = custom.exists() ? YamlConfiguration.loadConfiguration(custom) : fallback;
        }
        return new MessageService(fallback, active, resolved, perPlayerLanguage ? Map.copyOf(loaded) : Map.of());
    }

    /** Writes or upgrades the bundled locale files on disk (startup and /nmod reload). */
    public static void prepareFiles(JavaPlugin plugin) {
        LocaleStore.prepare(plugin);
    }

    /**
     * Resolves short, lowercase, or hyphenated locale codes (such as {@code fr_fr},
     * {@code es}, {@code de-DE}) to their canonical bundled locale identifier.
     */
    public static String resolveLocaleCode(String locale) {
        if (locale == null || locale.isBlank()) {
            return DEFAULT_LOCALE;
        }
        String normalized = locale.trim().replace('-', '_').toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "en", "en_us", "en_gb", "en_au", "en_ca", "en_nz", "en_ie" -> "en_US";
            case "es", "es_es", "es_mx", "es_ar", "es_419", "es_cl", "es_co", "es_ve", "es_uy", "es_ec" -> "es_ES";
            case "fr", "fr_fr", "fr_ca", "fr_be", "fr_ch" -> "fr_FR";
            case "de", "de_de", "de_at", "de_ch" -> "de_DE";
            case "pt", "pt_br", "pt_pt" -> "pt_BR";
            case "tr", "tr_tr" -> "tr_TR";
            default -> locale.trim();
        };
    }

    /** {@code text} without colour codes, hex colours included: for the console log, Discord and patterns. */
    public static String plain(String text) {
        return text == null ? "" : COLOR_CODE.matcher(text).replaceAll("");
    }

    public void send(CommandSender sender, String key) {
        send(sender, key, Map.of());
    }

    public void send(CommandSender sender, String key, Map<String, String> placeholders) {
        sender.sendMessage(format(sender, key, placeholders));
    }

    /** A message in the server's language. */
    public String format(String key, Map<String, String> placeholders) {
        return render(active, key, placeholders);
    }

    /** A message in the language {@code reader} reads. */
    public String format(CommandSender reader, String key, Map<String, String> placeholders) {
        return render(localeOf(reader), key, placeholders);
    }

    /** A message with no placeholders, in the language {@code reader} reads. */
    public String text(CommandSender reader, String key) {
        return format(reader, key, Map.of());
    }

    /** Shared closing divider; command output ends with it. */
    public void sendFooter(CommandSender sender) {
        sender.sendMessage(text(sender, "help.footer"));
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

    private String render(YamlConfiguration locale, String key, Map<String, String> placeholders) {
        String template = lookup(locale, key);
        Map<String, String> values = new HashMap<>(placeholders);
        if (template.contains("{prefix}") && !values.containsKey("prefix")) {
            values.put("prefix", Templates.render(lookup(locale, "prefix"), Map.of(), name -> color(locale, name)));
        }
        return Templates.render(template, values, name -> color(locale, name));
    }

    private String lookup(YamlConfiguration locale, String key) {
        String value = locale.getString(key);
        if (value == null) {
            value = fallback.getString(key);
        }
        return value == null ? key : value;
    }

    private String color(YamlConfiguration locale, String name) {
        String value = locale.getString("colors." + name);
        return value != null ? value : fallback.getString("colors." + name, "");
    }

    private YamlConfiguration localeOf(CommandSender reader) {
        return playerLocales.getOrDefault(localeCodeOf(reader), active);
    }

    /** The locale {@code reader} reads, such as {@code fr_FR}: theirs when per-player language applies. */
    public String localeCodeOf(CommandSender reader) {
        if (playerLocales.isEmpty() || !(reader instanceof Player player)) {
            return activeCode;
        }
        String clientLocale = PlayerLanguage.of(player);
        String code = clientLocale == null ? null : resolveLocaleCode(clientLocale);
        return code != null && playerLocales.containsKey(code) ? code : activeCode;
    }

    /** Reads a player's Minecraft language across server versions without a compile-time dependency. */
    private static final class PlayerLanguage {
        /** {@code getLocale()} on each server's player class; {@code null} where the server has none. */
        private static final ClassValue<Method> GET_LOCALE = new ClassValue<>() {
            @Override
            protected Method computeValue(Class<?> type) {
                try {
                    return type.getMethod("getLocale");
                } catch (NoSuchMethodException e) {
                    return null;
                }
            }
        };

        private PlayerLanguage() {
        }

        static String of(Player player) {
            try {
                Method getLocale = GET_LOCALE.get(player.getClass());
                Object value = getLocale != null ? getLocale.invoke(player) : null;
                return value instanceof String text && !text.isBlank() ? text : null;
            } catch (ReflectiveOperationException | RuntimeException e) {
                return null;
            }
        }
    }
}
