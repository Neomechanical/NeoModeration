package com.neomechanical.neomoderation.platform;

import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.Locale;

/**
 * A player's Minecraft language, such as {@code pt_pt}, read across server
 * versions without a compile-time dependency: the 1.8 API the plugin compiles
 * against has no {@code Player#getLocale()}.
 */
public final class GameLanguage {
    /** {@code getLocale()} on each server's player class; {@code null} where the server has none. */
    private static final ClassValue<Method> GET_LOCALE = new ClassValue<>() {
        @Override
        protected Method computeValue(Class<?> type) {
            try {
                return type.getMethod("getLocale");
            } catch (NoSuchMethodException e) {
                return null;
            }
        }
    };

    private GameLanguage() {
    }

    /** The player's game language in lower case, or {@code null} when the server does not say. */
    public static String of(Player player) {
        if (player == null) {
            return null;
        }
        try {
            Method getLocale = GET_LOCALE.get(player.getClass());
            Object value = getLocale != null ? getLocale.invoke(player) : null;
            return value instanceof String text && !text.isBlank() ? text.trim().toLowerCase(Locale.ROOT) : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }
}
