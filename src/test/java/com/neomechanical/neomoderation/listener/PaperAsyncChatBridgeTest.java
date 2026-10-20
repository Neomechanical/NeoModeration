package com.neomechanical.neomoderation.listener;

import com.neomechanical.neomoderation.moderation.ChatDecision;
import com.neomechanical.neomoderation.moderation.ChatModerationProcessor;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The Paper chat path, with Paper's own chat components (Adventure 4.26.1, as
 * Paper 1.21.11 ships it) in an event shaped like Paper's AsyncChatEvent.
 */
class PaperAsyncChatBridgeTest {

    /** Paper's AsyncChatEvent as the bridge sees it: getPlayer, message() and message(Component). */
    public static final class ChatEvent extends Event implements Cancellable {
        private static final HandlerList HANDLERS = new HandlerList();
        private final Player player;
        private Component message;
        private boolean cancelled;

        ChatEvent(Player player, Component message) {
            super(true);
            this.player = player;
            this.message = message;
        }

        public Player getPlayer() {
            return player;
        }

        public Component message() {
            return message;
        }

        public void message(Component message) {
            this.message = message;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public void setCancelled(boolean cancelled) {
            this.cancelled = cancelled;
        }

        @Override
        public HandlerList getHandlers() {
            return HANDLERS;
        }

        public static HandlerList getHandlerList() {
            return HANDLERS;
        }
    }

    private static ChatEvent chat(Component message, ChatDecision decision) throws Exception {
        ChatModerationProcessor processor = mock(ChatModerationProcessor.class);
        when(processor.handleChat(any(), anyString(), anyBoolean())).thenReturn(decision);
        ChatEvent event = new ChatEvent(mock(Player.class), message);
        PaperAsyncChatBridge.executor(processor).execute(new Listener() {
        }, event);
        return event;
    }

    @Test
    void aShoutedMessageGoesOutInLowerCase() throws Exception {
        ChatEvent event = chat(Component.text("WHO WANTS TO TRADE"), ChatDecision.soften("who wants to trade"));

        assertEquals(Component.text("who wants to trade"), event.message());
        assertFalse(event.isCancelled());
    }

    @Test
    void colourAnotherPluginAddedIsKept() throws Exception {
        Component coloured = Component.text("WHO WANTS TO TRADE", NamedTextColor.GOLD);

        ChatEvent event = chat(coloured, ChatDecision.soften("who wants to trade"));

        assertEquals(coloured, event.message());
        assertFalse(event.isCancelled());
    }

    @Test
    void theRulesReadThePlainTextOfTheMessage() throws Exception {
        ChatModerationProcessor processor = mock(ChatModerationProcessor.class);
        when(processor.handleChat(any(), anyString(), anyBoolean())).thenReturn(ChatDecision.allow());
        ChatEvent event = new ChatEvent(mock(Player.class),
                Component.text("hello ").append(Component.text("there", NamedTextColor.RED)));

        PaperAsyncChatBridge.executor(processor).execute(new Listener() {
        }, event);

        verify(processor).handleChat(event.getPlayer(), "hello there", true);
    }

    @Test
    void aBlockedMessageIsCancelledAndACensoredOneReplaced() throws Exception {
        assertTrue(chat(Component.text("blocked"), ChatDecision.block()).isCancelled());

        ChatEvent censored = chat(Component.text("you badword"), ChatDecision.censor("you *******"));
        assertEquals(Component.text("you *******"), censored.message());
        assertFalse(censored.isCancelled());
    }
}
