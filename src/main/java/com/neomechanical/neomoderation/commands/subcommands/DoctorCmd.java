package com.neomechanical.neomoderation.commands.subcommands;

import com.neomechanical.neomoderation.NeoModerationPlugin;
import com.neomechanical.neomoderation.commands.SubCommand;
import com.neomechanical.neomoderation.config.ModerationSettings;
import com.neomechanical.neomoderation.messages.ActionText;
import com.neomechanical.neomoderation.messages.MessageService;
import com.neomechanical.neomoderation.moderation.DiscordAlerts;
import com.neomechanical.neomoderation.moderation.NeoMechanicalUsageClient;
import com.neomechanical.neomoderation.moderation.UsageSummary;
import org.bukkit.command.CommandSender;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One-command setup check: configuration, actions, cloud key, connection and
 * credits. {@link DoctorChecks} decides; this class renders and asks the
 * account API off the main thread.
 */
public class DoctorCmd implements SubCommand {
    private final NeoModerationPlugin plugin;
    private final NeoMechanicalUsageClient usageClient = new NeoMechanicalUsageClient();

    public DoctorCmd(NeoModerationPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getName() {
        return "doctor";
    }

    @Override
    public List<String> getAliases() {
        return List.of("diagnose");
    }

    @Override
    public void execute(CommandSender sender, String label, String[] args) {
        ModerationSettings settings = plugin.settings();
        MessageService messages = plugin.messages();
        sender.sendMessage(StatusCmd.dashboard(plugin, sender));
        messages.send(sender, "doctor.title");
        DiscordAlerts.Problem discordProblem = plugin.discordAlerts() == null ? null : plugin.discordAlerts().lastProblem();
        DoctorChecks.local(settings, plugin.caseLog().isAvailable(), discordProblem).forEach(check -> render(sender, check));
        List<DoctorChecks.Check> cloud = DoctorChecks.cloudSetup(settings,
                plugin.coordinator().isRemoteCallAllowed(), plugin.coordinator().lastCloudResultKind(),
                plugin.coordinator().busySkipped());
        cloud.forEach(check -> render(sender, check));
        if (!DoctorChecks.readyForAccountCheck(cloud)) {
            messages.sendFooter(sender);
            return;
        }
        messages.send(sender, "doctor.checking-cloud");
        plugin.runAsync(() -> {
            long start = System.nanoTime();
            List<DoctorChecks.Check> account;
            try {
                UsageSummary usage = usageClient.fetchUsage(settings.api());
                account = DoctorChecks.account(usage, elapsedMs(start));
            } catch (NeoMechanicalUsageClient.UsageException e) {
                account = List.of(DoctorChecks.accountFailure(e, elapsedMs(start)));
            }
            List<DoctorChecks.Check> result = account;
            plugin.runSync(() -> {
                result.forEach(check -> render(sender, check));
                messages.sendFooter(sender);
            });
        });
    }

    private void render(CommandSender sender, DoctorChecks.Check check) {
        MessageService messages = plugin.messages();
        Map<String, String> values = new HashMap<>(check.values());
        if ("actions-list".equals(check.detail())) {
            values.put("actions", ActionText.describe(messages, sender, plugin.settings().actions()));
        }
        messages.send(sender, "doctor." + check.level().name().toLowerCase(Locale.ROOT), Map.of(
                "check", messages.text(sender, "doctor.check." + check.check()),
                "detail", messages.format(sender, "doctor.detail." + check.detail(), values)));
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
