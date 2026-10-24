package com.neomechanical.neomoderation.platform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/** The install_age chart: a new install's first start, and an upgrade that cannot know its age. */
class InstallAgeTest {

    private static final LocalDate DAY = LocalDate.parse("2026-11-02");

    @Test
    void aNewInstallIsDatedOnceAndAgesInBuckets(@TempDir File folder) throws Exception {
        InstallAge.recordFirstStart(folder, true, DAY);
        InstallAge.recordFirstStart(folder, true, DAY.plusDays(9));
        assertEquals("2026-11-02", Files.readString(new File(folder, InstallAge.MARKER).toPath(), StandardCharsets.UTF_8));
        assertEquals("0-1d", InstallAge.bucket(folder, DAY.plusDays(1)));
        assertEquals("2-7d", InstallAge.bucket(folder, DAY.plusDays(7)));
        assertEquals("8-30d", InstallAge.bucket(folder, DAY.plusDays(30)));
        assertEquals("31-90d", InstallAge.bucket(folder, DAY.plusDays(90)));
        assertEquals("90d+", InstallAge.bucket(folder, DAY.plusDays(91)));
    }

    @Test
    void anUpgradeOrAMissingMarkerIsUnknown(@TempDir File folder) {
        assertEquals("unknown", InstallAge.bucket(folder, DAY), "no marker");
        InstallAge.recordFirstStart(folder, false, DAY);
        assertEquals("unknown", InstallAge.bucket(folder, DAY.plusDays(40)), "an upgrade from before 1.8.0");
    }
}
