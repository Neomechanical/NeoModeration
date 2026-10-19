package com.neomechanical.neomoderation.commands;

import com.neomechanical.neomoderation.NeoModerationPlugin;
import com.neomechanical.neomoderation.Permissions;
import com.neomechanical.neomoderation.messages.MenuRenderer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * The clickable "start a free cloud trial" line, shown in command output to an
 * in-game admin while the server has no cloud key. Never sent unasked, and the
 * click only explains the trial: starting it takes a second click.
 */
public final class TrialPromptSupport {

    private TrialPromptSupport() {
    }

    public static void sendTrialCtaIfPlayer(NeoModerationPlugin plugin, CommandSender sender) {
        if (!(sender instanceof Player) || !sender.hasPermission(Permissions.ADMIN)
                || !plugin.settings().api().apiKey().isBlank()) {
            return;
        }
        plugin.messages().sendMenu(sender, List.of(MenuRenderer.clickableLine(
                plugin.messages().text(sender, "trial.cta-button"), "/nmod trial",
                plugin.messages().text(sender, "trial.cta-hover"), MenuRenderer.ClickAction.RUN)));
    }
}
