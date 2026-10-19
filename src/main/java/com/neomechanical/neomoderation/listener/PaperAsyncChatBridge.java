package com.neomechanical.neomoderation.listener;

import com.neomechanical.neomoderation.NeoModerationPlugin;
import com.neomechanical.neomoderation.moderation.ChatDecision;
import com.neomechanical.neomoderation.moderation.ChatModerationProcessor;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventException;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;

import java.lang.reflect.Method;

public final class PaperAsyncChatBridge {
    private static final String PAPER_ASYNC_CHAT_EVENT = "io.papermc.paper.event.player.AsyncChatEvent";
    private static final String PLAIN_SERIALIZER = "net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer";

    private PaperAsyncChatBridge() {
    }

    public static boolean registerIfAvailable(NeoModerationPlugin plugin, ChatModerationProcessor processor) {
        Class<? extends Event> eventClass = paperAsyncChatEventClass();
        if (eventClass == null) {
            return false;
        }

        Listener listener = new Listener() {
        };
        EventExecutor executor = new PaperAsyncChatExecutor(processor);
        plugin.getServer().getPluginManager().registerEvent(
                eventClass,
                listener,
                EventPriority.HIGHEST,
                executor,
                plugin,
                true
        );
        plugin.getLogger().info("Paper AsyncChatEvent bridge enabled.");
        return true;
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends Event> paperAsyncChatEventClass() {
        try {
            return (Class<? extends Event>) Class.forName(PAPER_ASYNC_CHAT_EVENT).asSubclass(Event.class);
        } catch (ClassNotFoundException ignored) {
            return null;
        }
    }

    private static final class PaperAsyncChatExecutor implements EventExecutor {
        private final ChatModerationProcessor processor;

        private PaperAsyncChatExecutor(ChatModerationProcessor processor) {
            this.processor = processor;
        }

        @Override
        public void execute(Listener listener, Event event) throws EventException {
            try {
                Player player = (Player) event.getClass().getMethod("getPlayer").invoke(event);
                Object component = event.getClass().getMethod("message").invoke(event);
                // Another plugin can make a player chat from the server thread, which must not wait for the cloud.
                ChatDecision decision = processor.handleChat(player, plainText(component), event.isAsynchronous());
                if (decision.type() == ChatDecision.Type.BLOCK && event instanceof Cancellable cancellable) {
                    cancellable.setCancelled(true);
                } else if (decision.type() == ChatDecision.Type.CENSOR
                        && !setMessage(event, decision.message())
                        && event instanceof Cancellable cancellable) {
                    // If Adventure isn't reachable for some reason, blocking is the
                    // safe fallback for a flagged message.
                    cancellable.setCancelled(true);
                } else if (decision.type() == ChatDecision.Type.SOFTEN && isPlain(component)) {
                    // Only plain text is softened; colours or hover text another plugin added are kept.
                    setMessage(event, decision.message());
                }
            } catch (ReflectiveOperationException exception) {
                throw new EventException(exception);
            }
        }

        /** Replaces the chat Component with plain censored text via reflection. */
        private static boolean setMessage(Event event, String censored) {
            try {
                Class<?> componentClass = Class.forName("net.kyori.adventure.text.Component");
                Object component = componentClass.getMethod("text", String.class).invoke(null, censored);
                for (Method method : event.getClass().getMethods()) {
                    if ("message".equals(method.getName())
                            && method.getParameterCount() == 1
                            && method.getParameterTypes()[0].isInstance(component)) {
                        method.invoke(event, component);
                        return true;
                    }
                }
            } catch (ReflectiveOperationException ignored) {
                // Fall through: caller cancels the event instead.
            }
            return false;
        }
    }

    /** Whether a chat component is bare text, with no style and no parts another plugin added. */
    private static boolean isPlain(Object component) {
        try {
            Object children = component.getClass().getMethod("children").invoke(component);
            Object style = component.getClass().getMethod("style").invoke(component);
            return children instanceof java.util.List<?> list && list.isEmpty()
                    && Boolean.TRUE.equals(style.getClass().getMethod("isEmpty").invoke(style));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    private static String plainText(Object component) {
        if (component == null) {
            return "";
        }
        try {
            Class<?> serializerClass = Class.forName(PLAIN_SERIALIZER);
            Object serializer = serializerClass.getMethod("plainText").invoke(null);
            for (Method method : serializerClass.getMethods()) {
                if ("serialize".equals(method.getName())
                        && method.getParameterCount() == 1
                        && method.getParameterTypes()[0].isInstance(component)) {
                    return String.valueOf(method.invoke(serializer, component));
                }
            }
        } catch (ReflectiveOperationException ignored) {
            // Fall through to a safe representation on older or shaded Paper variants.
        }
        return String.valueOf(component);
    }
}
