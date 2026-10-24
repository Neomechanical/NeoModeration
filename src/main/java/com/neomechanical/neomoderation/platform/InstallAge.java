package com.neomechanical.neomoderation.platform;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

/**
 * How long this install has run, for the {@code install_age} chart: the UTC date of its first start, kept in the
 * plugin's folder from 1.8.0 on. An install upgraded from an older version has no such date, so its marker says
 * {@code unknown} rather than counting from the upgrade. Holds a date only, never anything about the server or players.
 */
public final class InstallAge {
    static final String MARKER = ".first-start";
    static final String UNKNOWN = "unknown";

    private InstallAge() {
    }

    /**
     * Writes the marker on the first start that has none: today's date for a new install ({@code freshInstall}: the
     * plugin's folder held no config.yml before this start), else {@code unknown}. An existing marker is kept.
     */
    public static void recordFirstStart(File dataFolder, boolean freshInstall, LocalDate today) {
        File marker = new File(dataFolder, MARKER);
        if (marker.exists()) {
            return;
        }
        try {
            if (!dataFolder.exists()) {
                dataFolder.mkdirs();
            }
            Files.writeString(marker.toPath(), freshInstall ? today.toString() : UNKNOWN, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // Without a marker the chart says unknown, which is what an unreadable age is.
        }
    }

    /** The install's age as a chart bucket: unknown, 0-1d, 2-7d, 8-30d, 31-90d or 90d+. */
    public static String bucket(File dataFolder, LocalDate today) {
        LocalDate first;
        try {
            first = LocalDate.parse(Files.readString(new File(dataFolder, MARKER).toPath(), StandardCharsets.UTF_8).trim());
        } catch (IOException | DateTimeParseException unreadable) {
            return UNKNOWN;
        }
        long days = ChronoUnit.DAYS.between(first, today);
        if (days < 0) {
            return UNKNOWN;
        }
        if (days <= 1) {
            return "0-1d";
        }
        if (days <= 7) {
            return "2-7d";
        }
        if (days <= 30) {
            return "8-30d";
        }
        return days <= 90 ? "31-90d" : "90d+";
    }

    /** Today in UTC, the day the marker counts in. */
    public static LocalDate todayUtc() {
        return LocalDate.now(ZoneOffset.UTC);
    }
}
