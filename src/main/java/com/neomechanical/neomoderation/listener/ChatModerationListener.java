package com.neomechanical.neomoderation.listener;

import com.neomechanical.neomoderation.moderation.ChatDecision;
import com.neomechanical.neomoderation.moderation.ChatModerationProcessor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

public final class ChatModerationListener implements Listener {
    private final ChatModerationProcessor processor;

    public ChatModerationListener(ChatModerationProcessor processor) {
        this.processor = processor;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        ChatDecision decision = processor.handleChat(event.getPlayer(), event.getMessage(), event.isAsynchronous());
        switch (decision.type()) {
            case BLOCK -> event.setCancelled(true);
            case CENSOR, SOFTEN -> event.setMessage(decision.message());
            case ALLOW -> {
            }
        }
    }
}
