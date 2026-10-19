package com.neomechanical.neomoderation.messages;

import org.bukkit.configuration.file.YamlConfiguration;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Centralized registry of exact bundled locale values from prior releases so
 * unmodified locale files on disk upgrade cleanly while operator customizations
 * stay untouched.
 */
final class LocaleLegacyMigrations {
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

    private LocaleLegacyMigrations() {
    }

    static boolean apply(String locale, YamlConfiguration onDisk, YamlConfiguration bundled) {
        boolean changed = false;
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
}
