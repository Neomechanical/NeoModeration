package com.neomechanical.neomoderation.commands.subcommands;

import com.neomechanical.neomoderation.NeoModerationPlugin;
import com.neomechanical.neomoderation.Permissions;
import com.neomechanical.neomoderation.commands.SubCommand;
import com.neomechanical.neomoderation.commands.TrialPromptSupport;
import com.neomechanical.neomoderation.config.ModerationMode;
import com.neomechanical.neomoderation.config.ModerationSettings;
import com.neomechanical.neomoderation.messages.ActionText;
import com.neomechanical.neomoderation.messages.MessageService;
import com.neomechanical.neomoderation.moderation.CloudRecovery;
import com.neomechanical.neomoderation.moderation.DiscordAlerts;
import com.neomechanical.neomoderation.moderation.ModerationApiResult;
import com.neomechanical.neomoderation.moderation.ModerationCoverage;
import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.Map;

/** /nmod status: what is protected right now, one fact per line. */
public class StatusCmd implements SubCommand {
    private final NeoModerationPlugin plugin;

    public StatusCmd(NeoModerationPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getName() {
        return "status";
    }

    @Override
    public String getPermission() {
        return Permissions.MODERATE;
    }

    @Override
    public List<String> getAliases() {
        return List.of("info");
    }

    /** The one-line header every command page opens with, in the reader's language. */
    static String dashboard(NeoModerationPlugin plugin, CommandSender reader) {
        ModerationSettings settings = plugin.settings();
        MessageService messages = plugin.messages();
        return messages.format(reader, "help.dashboard", Map.of(
                "version", plugin.getDescription().getVersion(),
                "mode", modeLabel(messages, reader, settings.mode()),
                "cloud", settings.api().apiKey().isBlank()
                        ? messages.text(reader, "label.off")
                        : modeLabel(messages, reader, settings.cloudMode()),
                "total", String.valueOf(plugin.monitorStats().total())));
    }

    static String modeLabel(MessageService messages, CommandSender reader, ModerationMode mode) {
        return messages.text(reader, mode == ModerationMode.MONITOR ? "label.alert-only" : "label.blocking");
    }

    static String onOff(MessageService messages, CommandSender reader, boolean on) {
        return messages.text(reader, on ? "label.on" : "label.off");
    }

    @Override
    public void execute(CommandSender sender, String label, String[] args) {
        ModerationSettings settings = plugin.settings();
        ModerationCoverage coverage = ModerationCoverage.from(settings);
        MessageService messages = plugin.messages();
        sender.sendMessage(dashboard(plugin, sender));
        messages.send(sender, "status.enabled", Map.of("value", onOff(messages, sender, settings.enabled())));
        messages.send(sender, "status.local", Map.of("value", messages.text(sender, localKey(settings, coverage))));
        messages.send(sender, "status.rules", Map.of(
                "words", String.valueOf(settings.offline().effectiveWords().size()),
                "urls", String.valueOf(settings.offline().bannedUrls().size()),
                "exceptions", String.valueOf(settings.offline().allowedWords().size()
                        + settings.offline().allowedUrls().size())));
        messages.send(sender, "status.packs", Map.of("value", settings.offline().languagePacks().isEmpty()
                ? messages.text(sender, "label.none")
                : String.join(", ", settings.offline().languagePacks())));
        messages.send(sender, "status.player-language", Map.of("value", messages.text(sender,
                settings.offline().trustPlayerLanguage() ? "status.value.player-language-on"
                        : "status.value.player-language-off")));
        messages.send(sender, "status.server-ads", Map.of("value", messages.text(sender,
                settings.offline().blockServerAds() ? "status.value.server-ads-on" : "status.value.server-ads-off")));
        messages.send(sender, "status.surfaces", Map.of("value", settings.surfaces().enabledCount() == 0
                ? messages.text(sender, "status.value.surfaces-off")
                : messages.format(sender, "status.value.surfaces-on",
                        Map.of("count", String.valueOf(settings.surfaces().enabledCount())))));
        messages.send(sender, "status.checks", Map.of(
                "spam", onOff(messages, sender, coverage.spam()),
                "strikes", onOff(messages, sender, settings.strikes().enabled()),
                "cases", onOff(messages, sender, settings.cases().enabled() && plugin.caseLog().isAvailable())));
        messages.send(sender, "status.actions", Map.of("value", ActionText.describe(messages, sender, settings.actions())));
        messages.send(sender, "status.alerts", Map.of("value", !settings.alerts().enabled()
                ? messages.text(sender, "label.off")
                : messages.text(sender, settings.alerts().includeMessage()
                        ? "status.value.alerts-preview" : "status.value.alerts-hidden")));
        messages.send(sender, "status.discord", Map.of("value",
                onOff(messages, sender, DiscordAlerts.isWebhookUrl(settings.alerts().discordWebhookUrl()))));
        if (settings.api().apiKey().isBlank()) {
            // Only admins can start a trial, so only they are pointed at it.
            messages.send(sender, sender.hasPermission(Permissions.ADMIN)
                    ? "status.cloud-no-key" : "status.cloud-no-key-staff");
            TrialPromptSupport.sendTrialCtaIfPlayer(plugin, sender);
            return;
        }
        messages.send(sender, "status.cloud", Map.of("value", cloudValue(messages, sender, settings, coverage)));
        messages.send(sender, cloudStatusKey(plugin.coordinator().lastCloudResultKind()), Map.of(
                "keysUrl", CloudRecovery.API_KEYS_URL,
                "billingUrl", CloudRecovery.BILLING_URL));
        long busy = plugin.coordinator().busySkipped();
        if (busy > 0) {
            messages.send(sender, "status.cloud-busy", Map.of("count", String.valueOf(busy)));
        }
    }

    private static String localKey(ModerationSettings settings, ModerationCoverage coverage) {
        if (!coverage.hasLocalChecks()) {
            return "label.not-armed";
        }
        if (settings.mode() == ModerationMode.MONITOR) {
            return "status.value.local-monitor";
        }
        return coverage.localEnforces() ? "status.value.local-enforce" : "status.value.local-surfaces-only";
    }

    private static String cloudValue(MessageService messages, CommandSender sender, ModerationSettings settings,
                                     ModerationCoverage coverage) {
        if (!coverage.hasCloudChecks()) {
            return messages.text(sender, "status.value.cloud-nothing");
        }
        return messages.format(sender, "status.value.cloud-checks", Map.of(
                "categories", String.valueOf(coverage.cloudText() ? settings.categories().enabledCount() : 0),
                "maps", coverage.mapArt() ? messages.text(sender, "status.value.cloud-maps") : "",
                "mode", modeLabel(messages, sender, settings.cloudMode())));
    }

    static String cloudStatusKey(ModerationApiResult.Kind kind) {
        if (kind == null) {
            return "status.cloud-unverified";
        }
        return switch (kind) {
            case FLAGGED, CLEAR -> "status.cloud-ready";
            case CLIENT_AUTH -> "status.cloud-auth";
            case INSUFFICIENT_CREDITS -> "status.cloud-credits";
            case CLIENT_REQUEST -> "status.cloud-request-error";
            // A busy server is counted on its own line, never recorded as the cloud's result.
            case TRANSIENT_TRANSPORT, OVERLOADED -> "status.cloud-transient";
        };
    }
}
