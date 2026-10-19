package com.neomechanical.neomoderation.platform;

import com.neomechanical.neomoderation.NeoModerationPlugin;
import com.neomechanical.neomoderation.moderation.ModerationApiResult;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimplePie;

import java.util.Locale;

/**
 * Registers custom bStats telemetry charts for NeoModeration.
 *
 * <p>Preserves telemetry accuracy across nightly server restarts by combining
 * current in-memory detection totals with persistent case log history.</p>
 */
public final class MetricsRegistrar {

    private MetricsRegistrar() {
    }

    /**
     * Calculates the effective lifetime detection count for telemetry by combining
     * in-memory session statistics with persistent SQLite case log records.
     *
     * @param plugin the running plugin instance
     * @return the maximum of in-memory and persistent detection counts
     */
    public static long effectiveDetectionTotal(NeoModerationPlugin plugin) {
        if (plugin == null) {
            return 0L;
        }
        long inMemory = plugin.monitorStats() != null ? plugin.monitorStats().total() : 0L;
        long onDiskDiff = (plugin.caseLog() != null && plugin.caseLog().isAvailable())
                ? Math.max(0L, plugin.caseLog().totalCount() - inMemory)
                : 0L;
        return inMemory + onDiskDiff;
    }

    /**
     * Registers all 14 custom bStats charts for NeoModeration.
     *
     * @param plugin           the plugin instance
     * @param bstatsPluginId   the registered bStats plugin id
     * @param bundledWordCount the default shipped word count to distinguish custom lists
     */
    public static void register(NeoModerationPlugin plugin, int bstatsPluginId, int bundledWordCount) {
        if (plugin == null) {
            return;
        }

        Metrics metrics = new Metrics(plugin, bstatsPluginId);

        // Retained from earlier versions so their history stays continuous.
        metrics.addCustomChart(new SimplePie("moderation_mode",
                () -> plugin.settings().mode().name().toLowerCase(Locale.ROOT)));
        metrics.addCustomChart(new SimplePie("cloud_enabled",
                () -> InstallTelemetry.cloudEnabledState(plugin.settings())));
        metrics.addCustomChart(new SimplePie("chat_censor",
                () -> InstallTelemetry.censorState(plugin.settings())));

        // Is this install actually protecting anything?
        metrics.addCustomChart(new SimplePie("protection_state",
                () -> InstallTelemetry.protectionState(plugin.settings())));
        metrics.addCustomChart(new SimplePie("has_ever_detected",
                () -> InstallTelemetry.hasEverDetected(effectiveDetectionTotal(plugin))));
        metrics.addCustomChart(new SimplePie("detections_bucket",
                () -> InstallTelemetry.detectionsBucket(effectiveDetectionTotal(plugin))));

        // Is the cloud unconfigured, or configured and failing?
        metrics.addCustomChart(new SimplePie("cloud_state", () -> {
            ModerationApiResult.Kind kind = plugin.coordinator() != null
                    ? plugin.coordinator().lastCloudResultKind()
                    : null;
            boolean callBlocked = plugin.coordinator() != null && !plugin.coordinator().isRemoteCallAllowed();
            return InstallTelemetry.cloudState(
                    plugin.settings(),
                    callBlocked,
                    kind == null ? null : kind.name());
        }));

        // What did the operator actually configure?
        metrics.addCustomChart(new SimplePie("word_list_state",
                () -> InstallTelemetry.wordListState(plugin.settings(), bundledWordCount)));
        metrics.addCustomChart(new SimplePie("surfaces_armed",
                () -> InstallTelemetry.surfacesArmed(plugin.settings())));
        metrics.addCustomChart(new SimplePie("spam_state",
                () -> InstallTelemetry.spamState(plugin.settings())));
        metrics.addCustomChart(new SimplePie("strike_state",
                () -> InstallTelemetry.strikeState(plugin.settings())));
        metrics.addCustomChart(new SimplePie("map_art_state",
                () -> InstallTelemetry.mapArtState(plugin.settings())));
        metrics.addCustomChart(new SimplePie("locale_state",
                () -> InstallTelemetry.localeState(plugin.getConfig().getString("locale", "en_US"))));

        // Did Folia and proxy support reach anyone?
        metrics.addCustomChart(new SimplePie("platform_family",
                () -> InstallTelemetry.platformFamily(
                        plugin.getServer().getName(),
                        plugin.scheduler() != null && plugin.scheduler().isFolia())));
    }
}
