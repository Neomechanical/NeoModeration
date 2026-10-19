package com.neomechanical.neomoderation.commands;

import com.neomechanical.neomoderation.NeoModerationPlugin;
import com.neomechanical.neomoderation.messages.MenuRenderer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;

/**
 * Shared helper for rendering clickable cloud trial call-to-action buttons
 * in command responses and first-join admin prompts.
 */
public final class TrialPromptSupport {

    private TrialPromptSupport() {
    }

    /**
     * Sends a clickable trial activation button to the sender if they are an in-game
     * player and the server currently has no configured cloud API key.
     *
     * @param plugin the plugin instance
     * @param sender the command sender
     */
    public static void sendTrialCtaIfPlayer(NeoModerationPlugin plugin, CommandSender sender) {
        if (plugin == null || sender == null) {
            return;
        }
        if (!(sender instanceof Player)) {
            return;
        }
        if (plugin.settings() == null
                || plugin.settings().api() == null
                || !plugin.settings().api().apiKey().isBlank()) {
            return;
        }
        if (plugin.messages() == null) {
            return;
        }

        String text = plugin.messages().format("trial.cta-button", Map.of());
        plugin.messages().sendMenu(sender, List.of(
                MenuRenderer.clickableLine(text, "/nmod trial", "&b/nmod trial", MenuRenderer.ClickAction.RUN)
        ));
    }
}
