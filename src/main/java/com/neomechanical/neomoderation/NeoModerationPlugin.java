package com.neomechanical.neomoderation;

import com.neomechanical.neomoderation.commands.NeoModerationCommand;
import com.neomechanical.neomoderation.config.BukkitConfigView;
import com.neomechanical.neomoderation.config.ModerationSettings;
import com.neomechanical.neomoderation.listener.AdminWelcomeListener;
import com.neomechanical.neomoderation.listener.ChatModerationListener;
import com.neomechanical.neomoderation.listener.MapArtListener;
import com.neomechanical.neomoderation.listener.PaperAsyncChatBridge;
import com.neomechanical.neomoderation.listener.SurfaceModerationListener;
import com.neomechanical.neomoderation.messages.MessageService;
import com.neomechanical.neomoderation.moderation.CaseLog;
import com.neomechanical.neomoderation.moderation.ChatModerationActionExecutor;
import com.neomechanical.neomoderation.moderation.ChatModerationCoordinator;
import com.neomechanical.neomoderation.moderation.ChatModerationProcessor;
import com.neomechanical.neomoderation.moderation.DetectionHandler;
import com.neomechanical.neomoderation.moderation.DetectionNotifier;
import com.neomechanical.neomoderation.moderation.ModerationApiClient;
import com.neomechanical.neomoderation.moderation.MonitorStats;
import com.neomechanical.neomoderation.moderation.PlayerMuteService;
import com.neomechanical.neomoderation.moderation.SpamDetector;
import com.neomechanical.neomoderation.moderation.StartupSummary;
import com.neomechanical.neomoderation.moderation.StrikeService;
import com.neomechanical.neomoderation.moderation.ClientIdentity;
import com.neomechanical.neomoderation.moderation.UpdateChecker;
import com.neomechanical.neomoderation.platform.MetricsRegistrar;
import com.neomechanical.neomoderation.platform.PlatformScheduler;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.concurrent.atomic.AtomicBoolean;

public final class NeoModerationPlugin extends JavaPlugin {
    private static final int BSTATS_PLUGIN_ID = 32542;
    /** Size of the word list shipped in config.yml, used to tell default from customised. */
    private static final int BUNDLED_WORD_COUNT = 38;

    private ChatModerationCoordinator coordinator;
    private ModerationSettings settings;
    private MessageService messages;
    private PlayerMuteService muteService;
    private MonitorStats monitorStats;
    private DetectionNotifier notifier;
    private SpamDetector spamDetector;
    private StrikeService strikeService;
    private CaseLog caseLog;
    private DetectionHandler detectionHandler;
    private PlatformScheduler scheduler;
    private final AtomicBoolean trialActivationPending = new AtomicBoolean();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        scheduler = new PlatformScheduler(this);
        reloadModerationConfig();
        muteService = new PlayerMuteService(this);
        coordinator = new ChatModerationCoordinator(getLogger());
        monitorStats = new MonitorStats();
        notifier = new DetectionNotifier(this);
        spamDetector = new SpamDetector();
        strikeService = new StrikeService();
        caseLog = CaseLog.open(getDataFolder().toPath().resolve("cases.db"), getLogger());
        detectionHandler = new DetectionHandler(
                this,
                new ChatModerationActionExecutor("NeoModeration", muteService),
                monitorStats,
                notifier,
                strikeService
        );
        ChatModerationProcessor processor = new ChatModerationProcessor(
                this,
                coordinator,
                muteService,
                spamDetector,
                detectionHandler
        );
        // Paper fires the legacy AsyncPlayerChatEvent whenever a legacy listener is
        // registered, so registering both would moderate every message twice
        // (double API usage, double punishments). Prefer the modern Paper event and
        // fall back to the legacy listener only on Spigot.
        if (!PaperAsyncChatBridge.registerIfAvailable(this, processor)) {
            getServer().getPluginManager().registerEvents(
                    new ChatModerationListener(this, processor),
                    this
            );
        }
        getServer().getPluginManager().registerEvents(new MapArtListener(this), this);
        getServer().getPluginManager().registerEvents(new SurfaceModerationListener(this, detectionHandler), this);
        getServer().getPluginManager().registerEvents(new AdminWelcomeListener(this), this);
        PluginCommand command = getCommand("neomod");
        if (command != null) {
            NeoModerationCommand executor = new NeoModerationCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }
        ClientIdentity.configure(getDescription().getVersion(), scheduler.platformName());
        MetricsRegistrar.register(this, BSTATS_PLUGIN_ID, BUNDLED_WORD_COUNT);
        checkForUpdates();
        // A fresh install blocks bundled local-rule matches without punishing
        // players, while cloud judgements start in monitor mode. The startup
        // summary makes the active protection and limits visible to admins.
        StartupSummary.lines(settings, getDescription().getVersion()).forEach(getLogger()::info);
    }

    /**
     * Checks for a newer release once, off the server thread, and says so once.
     * Never nags and never downloads anything.
     */
    private void checkForUpdates() {
        if (!getConfig().getBoolean("updateCheck", true)) {
            return;
        }
        String current = getDescription().getVersion();
        runAsync(() -> UpdateChecker.findNewerVersion(current, ClientIdentity.userAgent())
                .ifPresent(latest -> UpdateChecker.updateLines(latest, current).forEach(getLogger()::warning)));
    }

    @Override
    public void onDisable() {
        if (coordinator != null) {
            coordinator.close();
        }
        if (caseLog != null) {
            caseLog.close();
        }
    }

    public void reloadModerationConfig() {
        reloadConfig();
        settings = ModerationSettings.from(new BukkitConfigView(getConfig()), getLogger());
        messages = MessageService.load(this, getConfig().getString("locale", "en_US"));
        if (coordinator != null) {
            coordinator.resetCircuit();
        }
    }

    /** Persist in-memory config edits and re-apply them atomically. */
    public void saveAndReload() {
        saveConfig();
        reloadModerationConfig();
    }

    public void runAsync(Runnable task) {
        scheduler.runAsync(task);
    }

    public void runSync(Runnable task) {
        scheduler.runGlobal(task);
    }

    public boolean tryStartTrialActivation() {
        return trialActivationPending.compareAndSet(false, true);
    }

    public void finishTrialActivation() {
        trialActivationPending.set(false);
    }

    /**
     * Run work that touches one entity. On Folia this must be the entity's own
     * scheduler, so every punishment and player message goes through here.
     */
    public void runForEntity(Entity entity, Runnable task) {
        scheduler.runForEntity(entity, task);
    }

    public PlatformScheduler scheduler() {
        return scheduler;
    }

    public ModerationSettings settings() {
        return settings;
    }

    public ChatModerationCoordinator coordinator() {
        return coordinator;
    }

    public MessageService messages() {
        return messages;
    }

    public PlayerMuteService muteService() {
        return muteService;
    }

    public MonitorStats monitorStats() {
        return monitorStats;
    }

    public DetectionNotifier notifier() {
        return notifier;
    }

    public SpamDetector spamDetector() {
        return spamDetector;
    }

    public StrikeService strikeService() {
        return strikeService;
    }

    public CaseLog caseLog() {
        return caseLog;
    }

    public DetectionHandler detectionHandler() {
        return detectionHandler;
    }

    public ModerationApiClient apiClient() {
        return coordinator.apiClient();
    }
}
