package com.neomechanical.neomoderation.listener;

import com.neomechanical.neomoderation.NeoModerationPlugin;
import com.neomechanical.neomoderation.Permissions;
import com.neomechanical.neomoderation.commands.DurationParser;
import com.neomechanical.neomoderation.config.ModerationSettings;
import com.neomechanical.neomoderation.config.SurfaceSettings.SurfaceMode;
import com.neomechanical.neomoderation.config.SurfaceSettings;
import com.neomechanical.neomoderation.moderation.DetectionHandler;
import com.neomechanical.neomoderation.moderation.OfflineModerationEngine;
import com.neomechanical.neomoderation.moderation.OfflineModerationResult;
import com.neomechanical.neomoderation.platform.GameLanguage;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Applies the local rules (and command-rate limiting) to signs, books, anvil
 * renames and the configured message commands. These events are synchronous,
 * so only the offline engine runs here, never the cloud. Everything routes
 * through {@link DetectionHandler}, so monitor mode, strikes, the player's
 * notice, staff alerts and case history behave exactly as in chat.
 *
 * <p>A muted player cannot use any command that sends a message: those listed,
 * {@code scannedCommands}, and every alias the server knows for them, such as
 * EssentialsX's {@code /m} for {@code /msg}.</p>
 */
public final class SurfaceModerationListener implements Listener {
    /** Commands that send a message, by the name their plugin registers. */
    private static final Set<String> MESSAGE_COMMANDS = Set.of(
            "msg", "tell", "w", "whisper", "m", "t", "pm", "dm", "message", "emsg", "epm", "etell", "ewhisper",
            "r", "reply", "er", "ereply", "me", "mail", "email", "teammsg", "tm");

    private final NeoModerationPlugin plugin;
    private final DetectionHandler handler;

    public SurfaceModerationListener(NeoModerationPlugin plugin, DetectionHandler handler) {
        this.plugin = plugin;
        this.handler = handler;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSignChange(SignChangeEvent event) {
        SurfaceMode mode = plugin.settings().surfaces().sign();
        DetectionHandler.Disposition effective =
                moderate(event.getPlayer(), mode, "sign", Arrays.asList(event.getLines()));
        if (effective == DetectionHandler.Disposition.BLOCK) {
            event.setCancelled(true);
        } else if (effective == DetectionHandler.Disposition.CENSOR) {
            for (int i = 0; i < 4; i++) {
                String line = event.getLine(i);
                if (line != null && !line.isEmpty()) {
                    event.setLine(i, OfflineModerationEngine.censor(line, plugin.settings().offline(),
                            GameLanguage.of(event.getPlayer())));
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBookEdit(PlayerEditBookEvent event) {
        SurfaceMode mode = plugin.settings().surfaces().book();
        BookMeta newMeta = event.getNewBookMeta();
        List<String> text = new ArrayList<>();
        if (newMeta.hasTitle()) {
            text.add(newMeta.getTitle());
        }
        text.addAll(newMeta.getPages());
        DetectionHandler.Disposition effective =
                moderate(event.getPlayer(), mode, "book", text);
        if (effective == DetectionHandler.Disposition.BLOCK) {
            event.setCancelled(true);
        } else if (effective == DetectionHandler.Disposition.CENSOR) {
            var offline = plugin.settings().offline();
            String speaker = GameLanguage.of(event.getPlayer());
            if (newMeta.hasTitle()) {
                newMeta.setTitle(OfflineModerationEngine.censor(newMeta.getTitle(), offline, speaker));
            }
            List<String> pages = new ArrayList<>(newMeta.getPages());
            pages.replaceAll(page -> OfflineModerationEngine.censor(page, offline, speaker));
            newMeta.setPages(pages);
            event.setNewBookMeta(newMeta);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAnvilResultTake(InventoryClickEvent event) {
        if (event.getInventory().getType() != InventoryType.ANVIL
                || event.getRawSlot() != 2
                || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        SurfaceMode mode = plugin.settings().surfaces().anvil();
        ItemStack item = event.getCurrentItem();
        if (item == null || !item.hasItemMeta()) {
            return;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasDisplayName()) {
            return;
        }
        String name = meta.getDisplayName();
        DetectionHandler.Disposition effective = moderate(player, mode, "anvil", List.of(name));
        if (effective == DetectionHandler.Disposition.BLOCK) {
            event.setCancelled(true);
        } else if (effective == DetectionHandler.Disposition.CENSOR) {
            meta.setDisplayName(OfflineModerationEngine.censor(name, plugin.settings().offline(), GameLanguage.of(player)));
            item.setItemMeta(meta);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        ModerationSettings settings = plugin.settings();
        Player player = event.getPlayer();
        String[] parts = event.getMessage().split("\\s+");
        if (parts.length == 0 || player.hasPermission(Permissions.BYPASS)) {
            return;
        }
        String command = parts[0].startsWith("/") ? parts[0].substring(1) : parts[0];
        command = command.toLowerCase(Locale.ROOT);
        String bareCommand = command.substring(command.indexOf(':') + 1);
        boolean scanned = settings.surfaces().scannedCommands().contains(command)
                || settings.surfaces().scannedCommands().contains(bareCommand);
        // A mute covers every message command too, whatever the command surface is set to.
        if ((scanned || sendsMessage(bareCommand)) && plugin.muteService().isMuted(player.getUniqueId())) {
            event.setCancelled(true);
            plugin.messages().send(player, "mute.blocked", Map.of("duration",
                    DurationParser.format(plugin.muteService().remainingSeconds(player.getUniqueId()))));
            return;
        }
        if (!scanned) {
            return;
        }
        SurfaceMode mode = settings.surfaces().command();
        if (mode == SurfaceMode.OFF || !settings.enabled()) {
            return;
        }

        Optional<String> spamReason = plugin.spamDetector()
                .checkCommand(player.getUniqueId(), settings.spam(), System.currentTimeMillis());
        if (spamReason.isPresent()) {
            DetectionHandler.Disposition effective = handler.handle(
                    player, "command", spamReason.get(), event.getMessage(),
                    requestedFor(mode, false));
            if (effective != DetectionHandler.Disposition.ALLOW) {
                event.setCancelled(true);
            }
            return;
        }

        // "/msg Notch hello": the recipient is a player name, not something the
        // sender wrote, so it is neither checked nor censored.
        int firstChecked = parts.length > 2 && plugin.getServer().getPlayerExact(parts[1]) != null ? 2 : 1;
        if (parts.length <= firstChecked) {
            return;
        }
        String kept = String.join(" ", Arrays.copyOfRange(parts, 0, firstChecked));
        String args = String.join(" ", Arrays.copyOfRange(parts, firstChecked, parts.length));
        String speaker = GameLanguage.of(player);
        OfflineModerationResult result = OfflineModerationEngine.evaluate(args, settings.offline(), speaker);
        if (!result.flagged()) {
            return;
        }
        DetectionHandler.Disposition effective =
                handler.handle(player, "command", result.reason(), args,
                        requestedFor(mode, mode != SurfaceMode.CENSOR || canCensor(List.of(args), settings, speaker)));
        if (effective == DetectionHandler.Disposition.BLOCK) {
            event.setCancelled(true);
        } else if (effective == DetectionHandler.Disposition.CENSOR) {
            event.setMessage(kept + " " + OfflineModerationEngine.censor(args, settings.offline(), speaker));
        }
    }

    /** Whether a command sends a message: by its name, or by the command the server runs for it. */
    private boolean sendsMessage(String command) {
        if (MESSAGE_COMMANDS.contains(command)) {
            return true;
        }
        Command resolved = resolve(command);
        if (resolved == null) {
            return false;
        }
        String name = resolved.getName().toLowerCase(Locale.ROOT);
        return MESSAGE_COMMANDS.contains(name.substring(name.indexOf(':') + 1));
    }

    /** The command the server runs for a label, through its command map, which the 1.8 API does not expose. */
    private Command resolve(String label) {
        try {
            Object map = plugin.getServer().getClass().getMethod("getCommandMap").invoke(plugin.getServer());
            return map instanceof CommandMap commandMap ? commandMap.getCommand(label) : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** Runs local rules and routes any hit through the shared handler. */
    private DetectionHandler.Disposition moderate(Player player, SurfaceMode mode, String surface, List<String> parts) {
        ModerationSettings settings = plugin.settings();
        String text = String.join(" ", parts);
        if (mode == SurfaceMode.OFF
                || !settings.enabled()
                || text.isBlank()
                || player.hasPermission(Permissions.BYPASS)) {
            return DetectionHandler.Disposition.ALLOW;
        }
        String speaker = GameLanguage.of(player);
        OfflineModerationResult result = OfflineModerationEngine.evaluate(text, settings.offline(), speaker);
        if (!result.flagged()) {
            return DetectionHandler.Disposition.ALLOW;
        }
        return handler.handle(player, surface, result.reason(), text,
                requestedFor(mode, mode != SurfaceMode.CENSOR || canCensor(parts, settings, speaker)));
    }

    private static boolean canCensor(List<String> parts, ModerationSettings settings, String speaker) {
        String censored = String.join(" ", parts.stream()
                .map(part -> OfflineModerationEngine.censor(part, settings.offline(), speaker)).toList());
        return !censored.equals(String.join(" ", parts))
                && !OfflineModerationEngine.evaluate(censored, settings.offline(), speaker).flagged();
    }

    /** Spam hits can't be censored, so they escalate CENSOR to BLOCK. */
    private static DetectionHandler.Disposition requestedFor(SurfaceMode mode, boolean censorable) {
        return switch (mode) {
            case MONITOR -> DetectionHandler.Disposition.ALLOW;
            case CENSOR -> censorable ? DetectionHandler.Disposition.CENSOR : DetectionHandler.Disposition.BLOCK;
            case BLOCK, OFF -> DetectionHandler.Disposition.BLOCK;
        };
    }
}
