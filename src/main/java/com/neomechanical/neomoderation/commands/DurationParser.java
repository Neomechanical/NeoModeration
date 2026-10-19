package com.neomechanical.neomoderation.commands;

import com.neomechanical.neomoderation.config.InputLimits;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DurationParser {
    private static final Pattern PATTERN = Pattern.compile("^(\\d+)([smhd])?$");

    private DurationParser() {
    }

    public static int parseSeconds(String raw, int defaultSeconds) {
        if (raw == null || raw.isBlank()) {
            return defaultSeconds;
        }
        Matcher matcher = PATTERN.matcher(raw.trim().toLowerCase(Locale.ROOT));
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid duration: " + raw);
        }
        long amount = Long.parseLong(matcher.group(1));
        if (amount <= 0) {
            throw new IllegalArgumentException("Duration must be positive");
        }
        String unit = matcher.group(2);
        long seconds;
        try {
            if (unit == null || unit.equals("s")) {
                seconds = amount;
            } else {
                seconds = switch (unit) {
                    case "m" -> Math.multiplyExact(amount, 60L);
                    case "h" -> Math.multiplyExact(amount, 3600L);
                    case "d" -> Math.multiplyExact(amount, 86400L);
                    default -> throw new IllegalArgumentException("Invalid duration: " + raw);
                };
            }
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("Duration too long: " + raw);
        }
        if (seconds > InputLimits.MAX_MUTE_SECONDS) {
            throw new IllegalArgumentException("Duration too long: " + raw);
        }
        return (int) seconds;
    }

    /** A rounded-down age such as "45s", "12m", "3h" or "6d", for "how long ago". */
    public static String approximate(long seconds) {
        if (seconds < 60) {
            return Math.max(0, seconds) + "s";
        }
        if (seconds < 3_600) {
            return (seconds / 60) + "m";
        }
        if (seconds < 86_400) {
            return (seconds / 3_600) + "h";
        }
        return (seconds / 86_400) + "d";
    }

    /**
     * Time left, in its two largest units: "9m 55s", "23h 59m", "2d 3h". A mute
     * set for 10 minutes reads "9m 55s" a few seconds later, never "595s".
     */
    public static String remaining(int seconds) {
        if (seconds <= 0) {
            return "0s";
        }
        int[] sizes = {86_400, 3_600, 60, 1};
        String[] units = {"d", "h", "m", "s"};
        for (int unit = 0; unit < sizes.length; unit++) {
            if (seconds >= sizes[unit]) {
                String text = (seconds / sizes[unit]) + units[unit];
                if (unit + 1 < sizes.length) {
                    int rest = (seconds % sizes[unit]) / sizes[unit + 1];
                    if (rest > 0) {
                        text += " " + rest + units[unit + 1];
                    }
                }
                return text;
            }
        }
        return seconds + "s";
    }

    /** A duration as it was set, such as "10m" or "90s". */
    public static String format(int seconds) {
        if (seconds <= 0) {
            return "0s";
        }
        if (seconds % 86400 == 0) {
            return (seconds / 86400) + "d";
        }
        if (seconds % 3600 == 0) {
            return (seconds / 3600) + "h";
        }
        if (seconds % 60 == 0) {
            return (seconds / 60) + "m";
        }
        return seconds + "s";
    }
}
