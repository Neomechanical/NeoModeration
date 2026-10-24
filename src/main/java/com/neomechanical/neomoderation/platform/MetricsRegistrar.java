package com.neomechanical.neomoderation.platform;

import com.neomechanical.neomoderation.NeoModerationPlugin;
import com.neomechanical.neomoderation.moderation.CloudPlanNotices;
import com.neomechanical.neomoderation.moderation.CloudPlanWatch;
import com.neomechanical.neomoderation.moderation.FreePlan;
import com.neomechanical.neomoderation.moderation.ModerationApiResult;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimplePie;

import java.io.File;
import java.time.Duration;
import java.time.Instant;
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
     * Registers all 23 custom bStats charts for NeoModeration.
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
                    kind == null ? null : kind.name(),
                    plugin.cloudPlan().state().name());
        }));

        // What real servers see, with no message text: the detection rate, the operator's allow-list, how many
        // players write in another language, whether their language is trusted, and how much chat a day (1.7.1).
        metrics.addCustomChart(new SimplePie("chat_detection_rate", () -> InstallTelemetry.chatDetectionRate(
                plugin.monitorStats().chatMessages(), plugin.monitorStats().chatDetections())));
        metrics.addCustomChart(new SimplePie("allow_list_size",
                () -> InstallTelemetry.allowListSize(plugin.settings())));
        metrics.addCustomChart(new SimplePie("players_other_language", () -> InstallTelemetry.playersInAnotherLanguage(
                plugin.settings().offline().locale(), GameLanguage.ofOnlinePlayers(plugin.getServer()))));
        metrics.addCustomChart(new SimplePie("chat_messages_per_day", () -> InstallTelemetry.chatMessagesPerDay(
                plugin.monitorStats().chatMessages(),
                Duration.between(plugin.monitorStats().since(), Instant.now()).toMillis())));
        metrics.addCustomChart(new SimplePie("player_language_trust",
                () -> InstallTelemetry.playerLanguageTrust(plugin.settings())));

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
                () -> InstallTelemetry.localeState(plugin.settings().offline().locale())));

        // The free plan (1.8.0): how old installs are, which plan each is on, which offers admins saw, and why a
        // start was refused (decision 0298 call 9 reads the refusals).
        metrics.addCustomChart(new SimplePie("install_age",
                () -> InstallAge.bucket(plugin.getDataFolder(), InstallAge.todayUtc())));
        metrics.addCustomChart(new SimplePie("cloud_plan", () -> {
            CloudPlanWatch.Snapshot plan = plugin.cloudPlan().current();
            FreePlan free = plan.status() == null ? null : plan.status().freePlan();
            return InstallTelemetry.cloudPlan(!plugin.settings().api().apiKey().isBlank(), plan.state().name(),
                    free != null && free.claimed(), free != null && free.claimPending());
        }));
        metrics.addCustomChart(new SimplePie("plan_offer", () -> InstallTelemetry.planOffer(
                new File(plugin.getDataFolder(), CloudPlanNotices.FREE_OFFER_MARKER).exists(),
                new File(plugin.getDataFolder(), CloudPlanNotices.COME_BACK_MARKER).exists())));
        metrics.addCustomChart(new SimplePie("start_refused", plugin::lastStartRefusal));

        // Did Folia support reach anyone?
        metrics.addCustomChart(new SimplePie("platform_family",
                () -> InstallTelemetry.platformFamily(
                        plugin.getServer().getName(),
                        plugin.scheduler() != null && plugin.scheduler().isFolia())));
    }
}
