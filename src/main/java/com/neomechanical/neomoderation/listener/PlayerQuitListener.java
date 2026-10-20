package com.neomechanical.neomoderation.listener;

import com.neomechanical.neomoderation.moderation.ChatModerationProcessor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/** Lets go of per-player chat state the moment a player leaves. */
public final class PlayerQuitListener implements Listener {
    private final ChatModerationProcessor processor;

    public PlayerQuitListener(ChatModerationProcessor processor) {
        this.processor = processor;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        processor.forget(event.getPlayer().getUniqueId());
    }
}
