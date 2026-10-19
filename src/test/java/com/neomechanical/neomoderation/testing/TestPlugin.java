package com.neomechanical.neomoderation.testing;

import com.neomechanical.neomoderation.NeoModerationPlugin;
import com.neomechanical.neomoderation.config.BukkitConfigView;
import com.neomechanical.neomoderation.config.ModerationSettings;
import com.neomechanical.neomoderation.messages.MessageService;
import com.neomechanical.neomoderation.moderation.CaseLog;
import com.neomechanical.neomoderation.moderation.ChatModerationCoordinator;
import com.neomechanical.neomoderation.moderation.MonitorStats;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.java.JavaPlugin;
import org.mockito.ArgumentCaptor;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * A mocked plugin around real settings and the real bundled messages, so a test
 * checks the words an admin or player reads rather than which key was asked for.
 * Scheduled tasks run at once on the calling thread.
 */
public final class TestPlugin {
    public static final String VERSION = "test-version";

    private TestPlugin() {
    }

    /** A plugin whose config.yml is {@code config}, read the way the plugin reads it. */
    public static NeoModerationPlugin with(YamlConfiguration config) {
        return with(ModerationSettings.from(new BukkitConfigView(config)));
    }

    public static NeoModerationPlugin with(ModerationSettings settings) {
        NeoModerationPlugin plugin = mock(NeoModerationPlugin.class);
        when(plugin.settings()).thenReturn(settings);
        when(plugin.messages()).thenReturn(english());
        when(plugin.monitorStats()).thenReturn(new MonitorStats());
        CaseLog caseLog = mock(CaseLog.class);
        when(caseLog.isAvailable()).thenReturn(true);
        when(plugin.caseLog()).thenReturn(caseLog);
        ChatModerationCoordinator coordinator = mock(ChatModerationCoordinator.class);
        when(coordinator.isRemoteCallAllowed()).thenReturn(true);
        when(plugin.coordinator()).thenReturn(coordinator);
        when(plugin.getDescription()).thenReturn(new PluginDescriptionFile("NeoModeration", VERSION, "test.Main"));
        runTasksAtOnce(plugin);
        return plugin;
    }

    /** Makes the plugin's scheduler run every task immediately, on this thread. */
    public static void runTasksAtOnce(NeoModerationPlugin plugin) {
        doAnswer(call -> {
            call.<Runnable>getArgument(0).run();
            return null;
        }).when(plugin).runAsync(any(Runnable.class));
        doAnswer(call -> {
            call.<Runnable>getArgument(0).run();
            return null;
        }).when(plugin).runSync(any(Runnable.class));
        doAnswer(call -> {
            call.<Runnable>getArgument(1).run();
            return null;
        }).when(plugin).runForEntity(any(), any(Runnable.class));
    }

    /** A bundled locale file, such as {@code de_DE}. */
    public static YamlConfiguration locale(String code) {
        return YamlConfiguration.loadConfiguration(new File("src/main/resources/locale/" + code + ".yml"));
    }

    /** The messages a fresh English install shows. */
    public static MessageService english() {
        YamlConfiguration english = locale(MessageService.DEFAULT_LOCALE);
        return MessageService.of(english, english, MessageService.DEFAULT_LOCALE);
    }

    /** The messages in {@code code}, over the English fallback. */
    public static MessageService messages(String code) {
        return MessageService.of(locale(MessageService.DEFAULT_LOCALE), locale(code), code);
    }

    /**
     * The messages exactly as the plugin loads them from a server folder: the
     * bundled files, {@code serverLocale} for the console, and each player's own
     * game language when {@code perPlayerLanguage} is on.
     */
    public static MessageService loaded(File dataFolder, String serverLocale, boolean perPlayerLanguage) {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(dataFolder);
        when(plugin.getResource(anyString())).thenAnswer(call ->
                TestPlugin.class.getClassLoader().getResourceAsStream(call.<String>getArgument(0)));
        return MessageService.load(plugin, serverLocale, perPlayerLanguage);
    }

    /** A player whose Minecraft language is {@code gameLocale}, such as {@code de_de}. */
    public static Player player(String name, String gameLocale) {
        Player player = mock(Player.class, withSettings().extraInterfaces(GameLanguage.class));
        when(((GameLanguage) player).getLocale()).thenReturn(gameLocale);
        when(player.getName()).thenReturn(name);
        when(player.getUniqueId()).thenReturn(UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)));
        return player;
    }

    /** {@code Player.getLocale()}, which the 1.8 API the plugin compiles against does not have. */
    public interface GameLanguage {
        String getLocale();
    }

    /** Every chat line a mock sender received, without colour codes. */
    public static List<String> lines(CommandSender sender) {
        return rawLines(sender).stream().map(TestPlugin::plain).toList();
    }

    /** Every chat line a mock sender received, colour codes and all. */
    public static List<String> rawLines(CommandSender sender) {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(sender, atLeast(0)).sendMessage(captor.capture());
        return captor.getAllValues();
    }

    /** {@link #lines} joined into one text, one line each. */
    public static String transcript(CommandSender sender) {
        return String.join("\n", lines(sender));
    }

    public static String plain(String text) {
        return MessageService.plain(text);
    }
}
