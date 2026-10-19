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
    private static final Map<String, Map<String, List<String>>> LEGACY_BUNDLED_VALUES = Map.of(
            "en_US", Map.ofEntries(
                    Map.entry("prefix", List.of("&8[&x&0&0&F&F&A&ANeoMod&8]")),
                    Map.entry("help.title", List.of("&8&m----------&r &x&0&0&F&F&A&A&lNeoModeration &8&m----------")),
                    Map.entry("help.dashboard", List.of(
                            "&x&0&0&F&F&A&A&lNeoModeration &8v{version} &7● &f{mode} &8| &7Cloud: &f{cloud} &8| &7Detections: &b{total}"
                    )),
                    Map.entry("help.page", List.of("&8Page {page}/2 · &7try &b/nmod help {next}")),
                    Map.entry("help.hint", List.of("&8Click a command to run it · hover any entry for help")),
                    Map.entry("error.no-api-key", List.of(
                            "{prefix} &cNo API key configured. Please run &e/nmod setup <apiKey>",
                            "{prefix} &cNo API key configured. &7Sign up at &f{url}&7, create a key, then run &e/nmod setup <apiKey>"
                    )),
                    Map.entry("help.usage.test", List.of("/nmod test <msg>")),
                    Map.entry("help.desc.test", List.of("preview the bundled rule; never acts")),
                    Map.entry("setup.usage", List.of(
                            "{prefix} &cUsage: &e/{label} setup <apiKey>&7. Cloud moderation adds zero-day threat feeds, AI evasion & map-art scanning. Get an evaluation key at &f{url}"
                    )),
                    Map.entry("setup.done", List.of(
                            "{prefix} &a&lSuccess! &7Cloud moderation is now &aactive&7. Chat is being scanned."
                    )),
                    Map.entry("trial.requesting", List.of(
                            "{prefix} &7Connecting to NeoMechanical to activate your 14-day cloud trial..."
                    )),
                    Map.entry("trial.already-configured", List.of(
                            "{prefix} &cThis server already has an active API key configured."
                    )),
                    Map.entry("trial.activated", List.of(
                            "{prefix} &a14-day cloud trial activated (expires &f{expires}&a). Cloud chat and map-art checks start in monitor mode. Run &e/nmod doctor &ato verify."
                    )),
                    Map.entry("trial.status-standard", List.of(
                            "{prefix} &aUsing a standard NeoMechanical workspace key. &7Run &e/nmod usage &7for credits and limits."
                    )),
                    Map.entry("key.saved", List.of("{prefix} &aAPI key saved successfully.")),
                    Map.entry("status.cloud-no-key", List.of(
                            "&7Cloud setup: &eno key&7. Free evaluation unlocks AI evasion & map-art NSFW scanning at &f{url}&7, then run &e/nmod setup <key>&7."
                    )),
                    Map.entry("usage.fetching", List.of("{prefix} &7Fetching usage from NeoMechanical...")),
                    Map.entry("usage.title", List.of("&8&m----------&r &x&0&0&F&F&A&A&lNeoMechanical Usage &8&m----------")),
                    Map.entry("usage.error", List.of(
                            "{prefix} &cFailed to fetch API usage. Check your key or try again later."
                    )),
                    Map.entry("test.usage", List.of("{prefix} &cUsage: &e/{label} test <message>")),
                    Map.entry("test.cloud-error", List.of(
                            "{prefix} &7Cloud: &cerror &8(&7{detail}, {ms}ms&8)"
                    )),
                    Map.entry("test.cloud-skipped-key", List.of(
                            "{prefix} &7Cloud: &8skipped (no API key; local rules only)",
                            "{prefix} &7Cloud: &8skipped (no API key). &7Cloud checks can classify individual chat messages and map art: &f{url} &7-> &e/nmod setup <key>&7."
                    )),
                    Map.entry("doctor.title", List.of("&8&m----------&r &x&0&0&F&F&A&A&lNeoModeration Doctor &8&m----------")),
                    Map.entry("privacy.title", List.of("&8&m----------&r &x&0&0&F&F&A&A&lNeoModeration Privacy &8&m----------")),
                    Map.entry("cases.title", List.of("&8&m----------&r &x&0&0&F&F&A&A&lRecent Cases &8&m----------")),
                    Map.entry("preset.list-title", List.of("&8&m----------&r &x&0&0&F&F&A&A&lPolicy Presets &8&m----------"))
            ),
            "es_ES", Map.ofEntries(
                    Map.entry("prefix", List.of("&8[&x&0&0&F&F&A&ANeoMod&8]")),
                    Map.entry("help.title", List.of("&8&m----------&r &x&0&0&F&F&A&A&lNeoModeration &8&m----------")),
                    Map.entry("help.dashboard", List.of(
                            "&x&0&0&F&F&A&A&lNeoModeration &8v{version} &7● &f{mode} &8| &7Nube: &f{cloud} &8| &7Detecciones: &b{total}"
                    )),
                    Map.entry("help.page", List.of("&8Página {page}/2 · &7prueba &b/nmod help {next}")),
                    Map.entry("help.hint", List.of(
                            "&8Haz clic en un comando para ejecutarlo · deja el puntero encima para ver ayuda"
                    )),
                    Map.entry("error.no-api-key", List.of(
                            "{prefix} &cNo hay clave API. Usa &e/nmod setup <apiKey>",
                            "{prefix} &cNo hay clave API. &7Regístrate en &f{url}&7, crea una clave y ejecuta &e/nmod setup <apiKey>"
                    )),
                    Map.entry("help.usage.test", List.of("/nmod test <msj>")),
                    Map.entry("help.desc.test", List.of("probar la regla incluida; nunca actúa")),
                    Map.entry("setup.usage", List.of(
                            "{prefix} &cUso: &e/{label} setup <apiKey>&7. La moderación en la nube añade amenazas de día cero, evasión por IA y análisis de mapas. Obtén una clave de prueba en &f{url}"
                    )),
                    Map.entry("setup.done", List.of(
                            "{prefix} &a&lListo! &7Moderación en la nube &aactiva&7."
                    )),
                    Map.entry("trial.requesting", List.of(
                            "{prefix} &7Conectando con NeoMechanical para activar tu prueba de 14 días..."
                    )),
                    Map.entry("trial.already-configured", List.of(
                            "{prefix} &cEste servidor ya tiene una clave API activa configurada."
                    )),
                    Map.entry("trial.activated", List.of(
                            "{prefix} &aPrueba de 14 días activada (caduca &f{expires}&a). La nube empieza en modo monitor. Ejecuta &e/nmod doctor &apara verificar."
                    )),
                    Map.entry("trial.status-standard", List.of(
                            "{prefix} &aUsando una clave estándar de NeoMechanical. &7Ejecuta &e/nmod usage &7para ver créditos y límites."
                    )),
                    Map.entry("key.saved", List.of("{prefix} &aClave API guardada.")),
                    Map.entry("status.cloud-no-key", List.of(
                            "&7Configuración de nube: &esin clave&7. Regístrate en &f{url}&7, crea una clave y ejecuta &e/nmod setup <key>&7."
                    )),
                    Map.entry("usage.fetching", List.of("{prefix} &7Consultando uso en NeoMechanical...")),
                    Map.entry("usage.title", List.of("&8&m----------&r &x&0&0&F&F&A&A&lUso de NeoMechanical &8&m----------")),
                    Map.entry("usage.error", List.of(
                            "{prefix} &cNo se pudo obtener el uso. Revisa la clave."
                    )),
                    Map.entry("test.usage", List.of("{prefix} &cUso: &e/{label} test <mensaje>")),
                    Map.entry("test.cloud-error", List.of(
                            "{prefix} &7Nube: &cerror &8(&7{detail}, {ms}ms&8)"
                    )),
                    Map.entry("test.cloud-skipped-key", List.of(
                            "{prefix} &7Nube: &8omitida (sin clave API; solo reglas locales)",
                            "{prefix} &7Nube: &8omitida (sin clave API). &7Regístrate en &f{url}&7, crea una clave y ejecuta &e/nmod setup <key>&7."
                    )),
                    Map.entry("doctor.title", List.of("&8&m----------&r &x&0&0&F&F&A&A&lNeoModeration Doctor &8&m----------")),
                    Map.entry("privacy.title", List.of("&8&m----------&r &x&0&0&F&F&A&A&lPrivacidad NeoModeration &8&m----------")),
                    Map.entry("cases.title", List.of("&8&m----------&r &x&0&0&F&F&A&A&lCasos Recientes &8&m----------")),
                    Map.entry("preset.list-title", List.of("&8&m----------&r &x&0&0&F&F&A&A&lPreajustes &8&m----------"))
            )
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
        if (sender instanceof Player player) {
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

        for (Map.Entry<String, List<String>> entry
                : LEGACY_BUNDLED_VALUES.getOrDefault(locale, Map.of()).entrySet()) {
            String replacement = bundled.getString(entry.getKey());
            String current = onDisk.getString(entry.getKey());
            if (replacement != null
                    && current != null
                    && entry.getValue().contains(current)
                    && !Objects.equals(replacement, current)) {
                onDisk.set(entry.getKey(), replacement);
                changed = true;
            }
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
