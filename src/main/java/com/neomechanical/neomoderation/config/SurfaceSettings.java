package com.neomechanical.neomoderation.config;



import java.util.List;
import java.util.Locale;

/**
 * Where local rules apply beyond chat, and how. Synchronous surfaces never call
 * the cloud; they run the offline rules only. All surfaces default OFF so
 * upgrades change nothing until the owner opts in. The global monitor mode
 * always downgrades enforcement to alerts.
 */
public record SurfaceSettings(
        SurfaceMode sign,
        SurfaceMode book,
        SurfaceMode anvil,
        SurfaceMode command,
        List<String> scannedCommands
) {
    public static final List<String> SURFACE_NAMES = List.of("sign", "book", "anvil", "command");
    public static final List<String> MODE_NAMES = List.of("off", "monitor", "censor", "block");

    public enum SurfaceMode {
        OFF,
        MONITOR,
        CENSOR,
        BLOCK;

        public static SurfaceMode parse(String raw) {
            if (raw == null) {
                return OFF;
            }
            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "monitor" -> MONITOR;
                case "censor" -> CENSOR;
                case "block" -> BLOCK;
                default -> OFF;
            };
        }

        public static boolean isValid(String raw) {
            if (raw == null) {
                return false;
            }
            return MODE_NAMES.contains(raw.trim().toLowerCase(Locale.ROOT));
        }
    }

    public static SurfaceSettings from(ConfigView config) {
        List<String> commands = config.getStringList("moderation.surfaces.scannedCommands").stream()
                .map(value -> value == null ? "" : value.trim().toLowerCase(Locale.ROOT))
                .map(value -> value.startsWith("/") ? value.substring(1) : value)
                .filter(value -> !value.isEmpty())
                .distinct()
                .toList();
        if (commands.isEmpty()) {
            commands = List.of("msg", "tell", "w", "whisper", "me", "r", "reply");
        }
        return new SurfaceSettings(
                SurfaceMode.parse(config.getString("moderation.surfaces.sign", "off")),
                SurfaceMode.parse(config.getString("moderation.surfaces.book", "off")),
                SurfaceMode.parse(config.getString("moderation.surfaces.anvil", "off")),
                SurfaceMode.parse(config.getString("moderation.surfaces.command", "off")),
                commands
        );
    }

    public SurfaceMode modeFor(String surface) {
        if (surface == null) {
            return SurfaceMode.OFF;
        }
        return switch (surface.trim().toLowerCase(Locale.ROOT)) {
            case "sign" -> sign;
            case "book" -> book;
            case "anvil" -> anvil;
            case "command" -> command;
            default -> SurfaceMode.OFF;
        };
    }

    public long enabledCount() {
        return java.util.stream.Stream.of(sign, book, anvil, command)
                .filter(mode -> mode != SurfaceMode.OFF)
                .count();
    }
}
