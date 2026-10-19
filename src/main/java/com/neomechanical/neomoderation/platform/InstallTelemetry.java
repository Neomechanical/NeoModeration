package com.neomechanical.neomoderation.platform;

import com.neomechanical.neomoderation.config.ModerationMode;
import com.neomechanical.neomoderation.config.ModerationSettings;
import com.neomechanical.neomoderation.config.SurfaceSettings.SurfaceMode;
import com.neomechanical.neomoderation.moderation.ModerationCoverage;

import java.util.Locale;

/**
 * The questions bStats should have been answering all along, as pure functions
 * of settings and counters so each one is testable without a server.
 *
 * <p>Context for why this file exists at all: on 2026-09-03 the three custom
 * charts registered in code ({@code moderation_mode}, {@code cloud_enabled},
 * {@code chat_censor}) were found to have no counterpart on bstats.org, so
 * every value the plugin had ever sent for them was discarded on arrival. With
 * 99 downloads producing 4 retained external servers, and no way to see how any
 * of them were configured, every retention theory was unfalsifiable.
 *
 * <p>Each chart is chosen to discriminate between competing explanations for
 * removal, not to be interesting:
 * <ul>
 *   <li>{@code protection_state} -- separates "installed and armed" from
 *       "installed and inert", the single most important distinction.</li>
 *   <li>{@code detections_bucket} and {@code has_ever_detected} -- an install
 *       that never fires has nothing to demonstrate its value.</li>
 *   <li>{@code cloud_state} -- distinguishes never-configured from
 *       configured-then-failing, which are opposite problems.</li>
 *   <li>{@code platform_family} -- tells us whether Folia support reached
 *       anyone.</li>
 * </ul>
 *
 * <p>Nothing here identifies a server, an operator, or a player.
 */
public final class InstallTelemetry {

    private InstallTelemetry() {
    }

    /** Configured checks with their prerequisites present; cloud health is reported separately. */
    public static String protectionState(ModerationSettings settings) {
        if (!settings.enabled()) {
            return "disabled";
        }
        ModerationCoverage coverage = ModerationCoverage.from(settings);
        boolean local = coverage.hasLocalChecks();
        boolean cloud = coverage.hasCloudChecks();
        if (!local && !cloud) {
            return "nothing_armed";
        }
        if (!local) {
            return settings.cloudMode() == ModerationMode.MONITOR
                    ? "monitor_cloud_only" : "enforce_cloud_only";
        }
        if (!cloud) {
            return coverage.localEnforces() ? "enforce_local_only" : "monitor_local_only";
        }
        if (!coverage.localEnforces()) {
            return settings.cloudMode() == ModerationMode.MONITOR
                    ? "monitor_all" : "monitor_local_enforce_cloud";
        }
        return settings.cloudMode() == ModerationMode.MONITOR ? "enforce_local_monitor_cloud" : "enforce_all";
    }

    /** Whether any configured local or cloud check can receive content. */
    public static String cloudEnabledState(ModerationSettings settings) {
        ModerationCoverage coverage = ModerationCoverage.from(settings);
        if (coverage.hasLocalChecks()) {
            return coverage.hasCloudChecks() ? "local_and_cloud" : "local_only";
        }
        return coverage.hasCloudChecks() ? "cloud_only" : "nothing_armed";
    }

    /**
     * Why the cloud is or is not working, distinguishing the cases that need
     * opposite responses: a key nobody ever set, a key the platform rejects, a
     * workspace out of credits, and a network that keeps timing out. Reported
     * one for one so a support answer can be given without asking the operator
     * to reproduce anything.
     *
     * @param lastResultKind the last {@code ModerationApiResult.Kind} name, or
     *                       {@code null} when no call has been made yet
     */
    public static String cloudState(ModerationSettings settings, boolean circuitOpen, String lastResultKind) {
        if (settings.api().apiKey().isBlank()) {
            return "no_key";
        }
        if (lastResultKind == null) {
            return "key_untested";
        }
        return switch (lastResultKind) {
            case "FLAGGED", "CLEAR" -> "key_working";
            case "CLIENT_AUTH" -> "key_rejected";
            case "INSUFFICIENT_CREDITS" -> "no_credits";
            case "CLIENT_REQUEST" -> "bad_request";
            case "OVERLOADED" -> "busy";
            case "TRANSIENT_TRANSPORT" -> circuitOpen ? "key_failing" : "transport_flaky";
            default -> "key_untested";
        };
    }

    /**
     * Detection volume since startup, as a bucket rather than a number: bStats
     * pies need low cardinality, and the question is order of magnitude.
     */
    public static String detectionsBucket(long total) {
        if (total <= 0) {
            return "0";
        }
        if (total < 10) {
            return "1-9";
        }
        if (total < 100) {
            return "10-99";
        }
        if (total < 1000) {
            return "100-999";
        }
        return "1000+";
    }

    /** Whether a detection happened since this server started, despite the historical chart ID. */
    public static String hasEverDetected(long total) {
        return total > 0 ? "yes" : "no";
    }

    /** Non-chat surfaces with an active local rule or command rate check. */
    public static String surfacesArmed(ModerationSettings settings) {
        ModerationCoverage coverage = ModerationCoverage.from(settings);
        long count = 0;
        if (coverage.offlineRulesConfigured()) {
            count += settings.surfaces().sign() != SurfaceMode.OFF ? 1 : 0;
            count += settings.surfaces().book() != SurfaceMode.OFF ? 1 : 0;
            count += settings.surfaces().anvil() != SurfaceMode.OFF ? 1 : 0;
        }
        if ((coverage.offlineRulesConfigured() || coverage.commandSpam())
                && settings.surfaces().command() != SurfaceMode.OFF) {
            count++;
        }
        return count == 0 ? "none" : String.valueOf(count);
    }

    /** Whether the operator kept, extended, or emptied the bundled word list. */
    public static String wordListState(ModerationSettings settings, int bundledSize) {
        if (!settings.offline().enabled()) {
            return "offline_disabled";
        }
        int size = settings.offline().bannedWords().size();
        if (size == 0) {
            return "empty";
        }
        if (size < bundledSize) {
            return "trimmed";
        }
        if (size == bundledSize) {
            return "bundled_default";
        }
        return "extended";
    }

    /** Server family, so Folia adoption is visible. */
    public static String platformFamily(String platformName, boolean folia) {
        if (folia) {
            return "Folia";
        }
        String name = platformName == null ? "" : platformName.trim();
        return name.isEmpty() ? "unknown" : name;
    }

    /** Anti-spam on/off, the other half of "is anything armed". */
    public static String spamState(ModerationSettings settings) {
        return ModerationCoverage.from(settings).spam() ? "on" : "off";
    }

    /** Whether local hits are censored or blocked outright. */
    public static String censorState(ModerationSettings settings) {
        return settings.chatCensorLocal() ? "censor" : "block";
    }

    /** Which locale the operator selected, lower-cased for chart tidiness. */
    public static String localeState(String locale) {
        if (locale == null || locale.isBlank()) {
            return "en_us";
        }
        return locale.trim().toLowerCase(Locale.ROOT);
    }

    /** Strike escalation on/off. */
    public static String strikeState(ModerationSettings settings) {
        return settings.strikes().enabled() ? "on" : "off";
    }

    /** Map-art scanning: only meaningful when a key is present. */
    public static String mapArtState(ModerationSettings settings) {
        if (!settings.enabled() || !settings.mapArt().enabled()
                || (!settings.mapArt().scanOnHold() && !settings.mapArt().scanOnFrameInteract())) {
            return "off";
        }
        return settings.api().apiKey().isBlank() ? "on_but_no_key" : "on";
    }
}
