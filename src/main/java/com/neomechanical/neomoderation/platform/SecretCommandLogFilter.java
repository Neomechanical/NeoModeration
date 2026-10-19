package com.neomechanical.neomoderation.platform;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.regex.Pattern;

/**
 * Keeps secrets typed into /nmod out of the server log.
 *
 * <p>Paper and Spigot log every command a player runs ("Steve issued server
 * command: /nmod setup KEY"), and server logs get pasted into support threads.
 * This adds a Log4j filter that drops those lines for the commands that carry a
 * secret: {@code setup <key>}, {@code key set <key>} and
 * {@code discord <webhook URL>}. The filter is built by reflection, so the plugin
 * still compiles against the old Bukkit API; where Log4j is missing nothing
 * changes.</p>
 */
public final class SecretCommandLogFilter {
    private static final String COMMAND_LINE = " issued server command: ";
    private static final Pattern SECRET_COMMAND = Pattern.compile(
            "(?i)/(?:[a-z0-9_.-]+:)?(?:nmod|neomod|neomoderation)\\s+"
                    + "(?:setup\\s+\\S|key\\s+set\\s+\\S|discord\\s+https?://)");

    private SecretCommandLogFilter() {
    }

    /** Whether a log line is a player's command that carries a secret. */
    static boolean hides(String line) {
        return line != null && line.contains(COMMAND_LINE) && SECRET_COMMAND.matcher(line).find();
    }

    /** Adds the filter to the server's root logger. Returns whether it could. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static boolean install() {
        try {
            Class<?> filterType = Class.forName("org.apache.logging.log4j.core.Filter");
            Class<? extends Enum> resultType =
                    (Class<? extends Enum>) Class.forName("org.apache.logging.log4j.core.Filter$Result");
            Class<? extends Enum> stateType =
                    (Class<? extends Enum>) Class.forName("org.apache.logging.log4j.core.LifeCycle$State");
            Class<?> eventType = Class.forName("org.apache.logging.log4j.core.LogEvent");
            Class<?> messageType = Class.forName("org.apache.logging.log4j.message.Message");
            Reader reader = new Reader(eventType, eventType.getMethod("getMessage"), messageType,
                    messageType.getMethod("getFormattedMessage"));
            Object deny = Enum.valueOf(resultType, "DENY");
            Object neutral = Enum.valueOf(resultType, "NEUTRAL");
            Object started = Enum.valueOf(stateType, "STARTED");
            InvocationHandler handler = (proxy, method, args) ->
                    answer(proxy, method, args, reader, deny, neutral, started);
            Object filter = Proxy.newProxyInstance(filterType.getClassLoader(), new Class<?>[]{filterType}, handler);
            Object root = Class.forName("org.apache.logging.log4j.LogManager").getMethod("getRootLogger").invoke(null);
            root.getClass().getMethod("addFilter", filterType).invoke(root, filter);
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return false;
        }
    }

    private static Object answer(Object proxy, Method method, Object[] args, Reader reader, Object deny,
                                 Object neutral, Object started) {
        switch (method.getName()) {
            case "filter":
                return args != null && hides(reader.text(args)) ? deny : neutral;
            case "getOnMatch", "getOnMismatch":
                return neutral;
            case "getState":
                return started;
            case "isStarted":
                return true;
            case "isStopped":
                return false;
            case "stop":
                return method.getReturnType() == boolean.class ? Boolean.TRUE : null;
            case "hashCode":
                return System.identityHashCode(proxy);
            case "equals":
                return args != null && args.length == 1 && proxy == args[0];
            case "toString":
                return "NeoModeration secret command filter";
            default:
                return null;
        }
    }

    /** Reads the text of whatever a Filter method was handed: a LogEvent, a Message or a format string. */
    private record Reader(Class<?> eventType, Method eventMessage, Class<?> messageType, Method formatted) {
        String text(Object[] args) {
            StringBuilder text = new StringBuilder();
            for (Object arg : args) {
                try {
                    Object message = eventType.isInstance(arg) ? eventMessage.invoke(arg) : arg;
                    if (message instanceof String string) {
                        text.append(string).append('\n');
                    } else if (messageType.isInstance(message)) {
                        text.append(formatted.invoke(message)).append('\n');
                    }
                } catch (ReflectiveOperationException | RuntimeException e) {
                    // An argument this filter cannot read cannot carry a command line either.
                }
            }
            return text.toString();
        }
    }
}
