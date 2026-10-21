package com.neomechanical.neomoderation;

import com.neomechanical.neomoderation.commands.NeoModerationCommand;
import com.neomechanical.neomoderation.config.BukkitConfigView;
import com.neomechanical.neomoderation.config.ConfigMigrations;
import com.neomechanical.neomoderation.config.ModerationApiSettings;
import com.neomechanical.neomoderation.config.ModerationSettings;
import com.neomechanical.neomoderation.listener.AdminJoinListener;
import com.neomechanical.neomoderation.listener.ChatModerationListener;
import com.neomechanical.neomoderation.listener.MapArtListener;
import com.neomechanical.neomoderation.listener.PaperAsyncChatBridge;
import com.neomechanical.neomoderation.listener.PlayerQuitListener;
import com.neomechanical.neomoderation.listener.SurfaceModerationListener;
import com.neomechanical.neomoderation.messages.MessageService;
import com.neomechanical.neomoderation.moderation.CaseLog;
import com.neomechanical.neomoderation.moderation.ChatModerationActionExecutor;
import com.neomechanical.neomoderation.moderation.ChatModerationCoordinator;
import com.neomechanical.neomoderation.moderation.ChatModerationProcessor;
import com.neomechanical.neomoderation.moderation.ClientIdentity;
import com.neomechanical.neomoderation.moderation.CloudPlanWatch;
import com.neomechanical.neomoderation.moderation.CloudWiring;
import com.neomechanical.neomoderation.moderation.DetectionHandler;
import com.neomechanical.neomoderation.moderation.DetectionNotifier;
import com.neomechanical.neomoderation.moderation.DiscordAlerts;
import com.neomechanical.neomoderation.moderation.ModerationApiClient;
import com.neomechanical.neomoderation.moderation.MonitorStats;
import com.neomechanical.neomoderation.moderation.PlayerMuteService;
import com.neomechanical.neomoderation.moderation.SpamDetector;
import com.neomechanical.neomoderation.moderation.StartupSummary;
import com.neomechanical.neomoderation.moderation.StrikeService;
import com.neomechanical.neomoderation.moderation.TrialClient;
import com.neomechanical.neomoderation.moderation.UpdateChecker;
import com.neomechanical.neomoderation.platform.MetricsRegistrar;
import com.neomechanical.neomoderation.platform.PlatformScheduler;
import com.neomechanical.neomoderation.platform.SecretCommandLogFilter;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

public final class NeoModerationPlugin extends JavaPlugin {
    private static final int BSTATS_PLUGIN_ID = 32542;

    private ChatModerationCoordinator coordinator;
    // Swapped whole on reload and read from chat threads, so every reader sees a complete one.
    /** Held while config.yml is read and changed, so two commands never edit it at once. */
    private final Object configLock = new Object();
    private volatile ModerationSettings settings;
    private volatile MessageService messages;
    private PlayerMuteService muteService;
    private MonitorStats monitorStats;
    private SpamDetector spamDetector;
    private CaseLog caseLog;
    private DetectionHandler detectionHandler;
    private PlatformScheduler scheduler;
    private DiscordAlerts discordAlerts;
    private String messagesLocale;
    /** Bundled locales whose file on disk holds texts this server wrote. */
    private Set<String> editedLocales = Set.of();
    private final AtomicBoolean trialActivationPending = new AtomicBoolean();
    private final AtomicReference<String> newerVersion = new AtomicReference<>();
    private CloudWiring cloud;
    /** Cancels the ten-minute refresh of what the trial service said. */
    private Runnable stopCloudPlanRefresh = () -> { };

    @Override
    public void onEnable() {
        saveDefaultConfig();
        // Before any command can run: keys and webhook URLs typed into /nmod stay out of the log.
        SecretCommandLogFilter.install();
        scheduler = new PlatformScheduler(this);
        editedLocales = MessageService.prepareFiles(this);
        reloadModerationConfig();
        muteService = new PlayerMuteService(this);
        coordinator = new ChatModerationCoordinator(getLogger());
        cloud = new CloudWiring(coordinator, new TrialClient(), () -> settings.api(), this::runAsync,
                System::currentTimeMillis, () -> messages, () -> getServer().getConsoleSender(), getLogger()::warning);
        cloud.settingsLoaded(null, settings.api(), false);
        stopCloudPlanRefresh = scheduler.runAsyncEvery(10, cloud.plan()::refreshIfStale);
        monitorStats = new MonitorStats();
        discordAlerts = new DiscordAlerts();
        spamDetector = new SpamDetector();
        caseLog = CaseLog.open(getDataFolder().toPath().resolve("cases.db"), getLogger());
        detectionHandler = new DetectionHandler(
                this,
                new ChatModerationActionExecutor("NeoModeration", muteService, player -> getLogger().warning(
                        MessageService.plain(messages.format("action.unsafe-name", Map.of("player", player))))),
                monitorStats,
                new DetectionNotifier(this, discordAlerts),
                new StrikeService()
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
                    new ChatModerationListener(processor),
                    this
            );
        }
        getServer().getPluginManager().registerEvents(new PlayerQuitListener(processor), this);
        getServer().getPluginManager().registerEvents(new MapArtListener(this), this);
        getServer().getPluginManager().registerEvents(new SurfaceModerationListener(this, detectionHandler), this);
        getServer().getPluginManager().registerEvents(new AdminJoinListener(this), this);
        PluginCommand command = getCommand("neomod");
        if (command != null) {
            NeoModerationCommand executor = new NeoModerationCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }
        ClientIdentity.configure(getDescription().getVersion(), scheduler.platformName());
        MetricsRegistrar.register(this, BSTATS_PLUGIN_ID, bundledWordCount());
        checkForUpdates();
        // A fresh install blocks bundled local-rule matches without punishing
        // players, while cloud judgements start in monitor mode. The startup
        // summary makes the active protection and limits visible to admins.
        StartupSummary.lines(settings, getDescription().getVersion(), messages, getServer().getConsoleSender())
                .forEach(getLogger()::info);
    }

    /**
     * Checks for a newer release once, off the server thread. The console hears
     * about it once; admins see it once each when they join (AdminJoinListener).
     * Never downloads anything.
     */
    private void checkForUpdates() {
        if (!getConfig().getBoolean("updateCheck", true)) {
            return;
        }
        String current = getDescription().getVersion();
        runAsync(() -> UpdateChecker.findNewerVersion(current, ClientIdentity.userAgent()).ifPresent(latest -> {
            newerVersion.set(latest);
            // The logger already names the plugin, so the console line goes without the chat prefix.
            getLogger().warning(MessageService.plain(messages.format("update.available",
                    Map.of("latest", latest, "current", current, "prefix", ""))).trim()
                    + " " + UpdateChecker.RELEASES_PAGE);
        }));
    }

    @Override
    public void onDisable() {
        stopCloudPlanRefresh.run();
        if (coordinator != null) {
            coordinator.close();
        }
        if (discordAlerts != null) {
            discordAlerts.close();
        }
        if (caseLog != null) {
            caseLog.close();
        }
        if (muteService != null) {
            muteService.close();
        }
        SecretCommandLogFilter.uninstall();
    }

    /**
     * Re-reads config.yml after it changed. Messages are re-read only when the
     * language settings changed, so an in-game edit does not parse every locale file.
     * The cloud's pauses and last result reset only when the key or endpoint changed:
     * {@code /nmod allow word add} is no reason to call a refused key again.
     */
    public void reloadModerationConfig() {
        reloadModerationConfig(false);
    }

    private void reloadModerationConfig(boolean resetCloud) {
        synchronized (configLock) {
            ModerationApiSettings cloudBefore = settings == null ? null : settings.api();
            reloadConfig();
            String serverLocale = MessageService.resolveLocaleCode(getConfig().getString("locale", "en_US"));
            // A locale file of the server's own ("my_texts") holds texts it wrote, as an edited bundled one does.
            boolean ownTexts = editedLocales.contains(serverLocale)
                    || !MessageService.BUNDLED_LOCALES.contains(serverLocale);
            List<ConfigMigrations.Change> migrated = ConfigMigrations.apply(getConfig(), ownTexts);
            if (!migrated.isEmpty()) {
                saveConfig();
            }
            settings = ModerationSettings.from(new BukkitConfigView(getConfig()), getLogger());
            String locale = getConfig().getString("locale", "en_US") + "|" + perPlayerLanguage();
            if (messages == null || !locale.equals(messagesLocale)) {
                loadMessages();
            }
            migrated.forEach(change ->
                    getLogger().info(MessageService.plain(messages.format(change.key(), change.values()))));
            if (cloud != null) {
                cloud.settingsLoaded(cloudBefore, settings.api(), resetCloud);
            }
        }
    }

    /** What the trial service said last about this server's key. */
    public CloudPlanWatch cloudPlan() {
        return cloud == null ? null : cloud.plan();
    }

    /**
     * /nmod reload: config.yml and every locale file, including files an admin edited by hand, and the cloud tries
     * again now.
     */
    public void reloadEverything() {
        synchronized (configLock) {
            editedLocales = MessageService.prepareFiles(this);
            messagesLocale = null;
            reloadModerationConfig(true);
        }
    }

    /**
     * Changes config.yml and, when {@code edit} returns true, saves and applies it.
     * Every command that changes a setting comes through here, under one lock: on
     * Folia, admins in different regions can run commands at the same moment, and
     * a Bukkit configuration is not safe to share between threads.
     */
    public boolean editConfig(Predicate<FileConfiguration> edit) {
        synchronized (configLock) {
            if (!edit.test(getConfig())) {
                return false;
            }
            saveConfig();
            reloadModerationConfig();
            return true;
        }
    }

    /** Sets one value in config.yml, then saves and applies it. */
    public void setConfig(String path, Object value) {
        editConfig(config -> {
            config.set(path, value);
            return true;
        });
    }

    private void loadMessages() {
        messages = MessageService.load(this, getConfig().getString("locale", "en_US"), perPlayerLanguage());
        messagesLocale = getConfig().getString("locale", "en_US") + "|" + perPlayerLanguage();
    }

    private boolean perPlayerLanguage() {
        return getConfig().getBoolean("perPlayerLanguage", true);
    }

    /** A newer release found at startup, or {@code null}. */
    public String newerVersion() {
        return newerVersion.get();
    }

    /** Size of the word list in the bundled config.yml, which tells a default list from a customised one. */
    private int bundledWordCount() {
        Configuration bundled = getConfig().getDefaults();
        return bundled == null ? 0 : bundled.getStringList("moderation.offline.bannedWords").size();
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

    /** As {@link #runForEntity(Entity, Runnable)}; {@code retired} runs when Folia drops the task for an entity gone. */
    public void runForEntity(Entity entity, Runnable task, Runnable retired) {
        scheduler.runForEntity(entity, task, retired);
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

    public DiscordAlerts discordAlerts() {
        return discordAlerts;
    }

    public SpamDetector spamDetector() {
        return spamDetector;
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
