package com.neomechanical.neomoderation.moderation;

import com.neomechanical.neomoderation.config.ModerationMode;
import com.neomechanical.neomoderation.config.ModerationSettings;
import com.neomechanical.neomoderation.messages.ActionText;
import com.neomechanical.neomoderation.messages.MessageService;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What the console says when the plugin starts, in the server's language: what
 * runs, what does not, and the one command that changes it. A new install
 * blocks local matches without punishing anyone and only alerts on cloud
 * decisions; existing installs keep whatever their config.yml says.
 */
public final class StartupSummary {

    private StartupSummary() {
    }

    public static List<String> lines(ModerationSettings settings, String version, MessageService messages,
                                     CommandSender console) {
        List<String> lines = new ArrayList<>();
        lines.add(line(messages, console, "startup.enabled", Map.of("version", version)));
        if (!settings.enabled()) {
            lines.add(line(messages, console, "startup.disabled", Map.of()));
            lines.add(line(messages, console, "startup.status", Map.of()));
            return lines;
        }
        ModerationCoverage coverage = ModerationCoverage.from(settings);
        if (!coverage.hasLocalChecks()) {
            lines.add(line(messages, console, "startup.local-none", Map.of()));
        } else if (settings.mode() == ModerationMode.MONITOR) {
            lines.add(line(messages, console, "startup.local-monitor", Map.of()));
        } else if (!coverage.localEnforces()) {
            lines.add(line(messages, console, "startup.local-surfaces", Map.of()));
        } else {
            lines.add(line(messages, console, "startup.local-enforce",
                    Map.of("actions", ActionText.describe(messages, console, settings.actions()))));
        }
        if (coverage.hasCloudChecks()) {
            lines.add(line(messages, console, settings.cloudMode() == ModerationMode.MONITOR
                    ? "startup.cloud-monitor" : "startup.cloud-enforce", Map.of()));
        }
        lines.add(line(messages, console, "startup.active",
                Map.of("list", String.join(", ", active(settings, coverage, messages, console)))));
        List<String> inactive = inactive(settings, coverage, messages, console);
        if (!inactive.isEmpty()) {
            lines.add(line(messages, console, "startup.inactive", Map.of("list", String.join(", ", inactive))));
        }
        lines.add(line(messages, console, "startup.try", Map.of()));
        lines.add(line(messages, console, "startup.status", Map.of()));
        return lines;
    }

    private static List<String> active(ModerationSettings settings, ModerationCoverage coverage,
                                       MessageService messages, CommandSender console) {
        List<String> active = new ArrayList<>();
        if (coverage.spam()) {
            active.add(line(messages, console, "startup.item-spam", Map.of()));
        }
        if (coverage.localRules()) {
            active.add(line(messages, console, "startup.item-rules", Map.of(
                    "words", String.valueOf(settings.offline().effectiveWords().size()),
                    "urls", String.valueOf(settings.offline().bannedUrls().size()))));
            if (settings.offline().blockServerAds()) {
                active.add(line(messages, console, "startup.item-server-ads", Map.of()));
            }
        }
        if (coverage.cloudText()) {
            active.add(line(messages, console, "startup.item-cloud-text",
                    Map.of("count", String.valueOf(settings.categories().enabledCount()))));
        }
        if (coverage.mapArt()) {
            active.add(line(messages, console, "startup.item-maps", Map.of()));
        }
        if (active.isEmpty()) {
            active.add(line(messages, console, "startup.item-nothing", Map.of()));
        }
        return active;
    }

    private static List<String> inactive(ModerationSettings settings, ModerationCoverage coverage,
                                         MessageService messages, CommandSender console) {
        List<String> inactive = new ArrayList<>();
        if (settings.api().apiKey().isBlank()) {
            // The biggest gap on a fresh install, and the only one that needs a step outside the server.
            inactive.add(line(messages, console, "startup.missing-cloud", Map.of()));
        } else if (!coverage.hasCloudChecks()) {
            inactive.add(line(messages, console, "startup.missing-cloud-off", Map.of()));
        }
        if (!coverage.spam()) {
            inactive.add(line(messages, console, "startup.missing-spam", Map.of()));
        }
        if (!coverage.localRules()) {
            inactive.add(line(messages, console, "startup.missing-rules", Map.of()));
        }
        return inactive;
    }

    private static String line(MessageService messages, CommandSender console, String key, Map<String, String> values) {
        return MessageService.plain(messages.format(console, key, values));
    }
}
