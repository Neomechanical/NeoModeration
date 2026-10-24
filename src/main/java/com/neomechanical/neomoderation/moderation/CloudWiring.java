package com.neomechanical.neomoderation.moderation;

import com.neomechanical.neomoderation.config.ModerationApiSettings;
import com.neomechanical.neomoderation.messages.DateText;
import com.neomechanical.neomoderation.messages.MessageService;
import org.bukkit.command.CommandSender;

import java.util.Map;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * How the cloud's parts follow the settings: the coordinator's calls and pauses, what the trial service says about
 * the key ({@link CloudPlanWatch}) and the console's notices about it ({@link CloudNoticeLog}). The order is the point,
 * and it lives only here. On every load of the settings the coordinator learns the new ones first, so a result still
 * in flight for the old key or endpoint is dropped rather than pausing the new one; then the pauses reset, when the
 * key, endpoint or timeouts changed or on {@code /nmod reload}, and never for an edit like {@code /nmod allow word add}
 * that is no reason to call a refused key again; and the watch hears of a new key last, whose notices are told again.
 */
public final class CloudWiring {

    private final ChatModerationCoordinator coordinator;
    private final CloudPlanWatch plan;
    private final CloudNoticeLog notices = new CloudNoticeLog();
    private final Supplier<MessageService> messages;
    private final Supplier<CommandSender> console;
    private final Consumer<String> warn;

    /**
     * @param messages the messages of the moment, which a reload may replace
     * @param warn     where a console notice goes: the plugin's logger, which names the plugin itself
     */
    public CloudWiring(ChatModerationCoordinator coordinator, TrialClient client, Supplier<ModerationApiSettings> api,
                       Consumer<Runnable> async, LongSupplier clock, Supplier<MessageService> messages,
                       Supplier<CommandSender> console, Consumer<String> warn) {
        this.coordinator = coordinator;
        this.messages = messages;
        this.console = console;
        this.warn = warn;
        this.plan = new CloudPlanWatch(client, api, async, clock, this::tellConsole);
        coordinator.onRefusal(plan::refused);
        coordinator.onFirstSuccess(() -> {
            // A key that works again may run out again: its next refusal is told once more.
            notices.forget();
            plan.succeeded();
        });
    }

    public CloudPlanWatch plan() {
        return plan;
    }

    /**
     * The settings were loaded: {@code before} is null at startup. {@code reloadEverything} is {@code /nmod reload},
     * which tries the cloud again now.
     */
    public void settingsLoaded(ModerationApiSettings before, ModerationApiSettings after, boolean reloadEverything) {
        coordinator.useApi(after);
        if (before != null && (reloadEverything || !after.equals(before))) {
            coordinator.resetCircuit();
        }
        if (plan.keyChanged(after.apiKey())) {
            notices.forget();
        }
    }

    /** A free plan an account now holds: checks held for today's cap go on, billed to the account. */
    public static void freePlanAnswered(ChatModerationCoordinator coordinator, CloudPlanWatch.Snapshot snapshot) {
        if (snapshot.state() == CloudPlanWatch.State.FREE_PLAN && snapshot.status().freePlan().claimed()) {
            coordinator.freePlanClaimed();
        }
    }

    /** What the trial service said about the key, told to the console once for each notice. */
    private void tellConsole(CloudPlanWatch.Snapshot snapshot, ModerationApiResult.Kind refusal) {
        freePlanAnswered(coordinator, snapshot);
        CloudPlanNotices.Notice notice = notices.next(snapshot, refusal);
        if (notice == null) {
            return;
        }
        MessageService text = messages.get();
        CommandSender reader = console.get();
        warn.accept(MessageService.plain(text.format(reader, notice.key(), Map.of(
                "date", DateText.of(text, reader, notice.dateIso()), "url", notice.url()))));
    }
}
