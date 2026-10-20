package com.neomechanical.neomoderation.commands;

import com.neomechanical.neomoderation.config.InputLimits;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DurationParserTest {
    @Test
    void parsesCommonDurations() {
        assertEquals(30, DurationParser.parseSeconds("30", 300));
        assertEquals(30, DurationParser.parseSeconds("30s", 300));
        assertEquals(300, DurationParser.parseSeconds("5m", 60));
        assertEquals(3600, DurationParser.parseSeconds("1h", 60));
        assertEquals(86400, DurationParser.parseSeconds("1d", 60));
        assertEquals(300, DurationParser.parseSeconds("", 300));
    }

    @Test
    void formatsDurations() {
        assertEquals("30s", DurationParser.format(30));
        assertEquals("5m", DurationParser.format(300));
        assertEquals("1h", DurationParser.format(3600));
        assertEquals("1d", DurationParser.format(86400));
    }

    @Test
    void rejectsInvalidAndOverflowDurations() {
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parseSeconds("nope", 300));
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parseSeconds("0m", 300));
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parseSeconds("999999999d", 300));
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parseSeconds("31d", 300));
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parseSeconds("106751991167301d", 300));
        assertEquals(InputLimits.MAX_MUTE_SECONDS, DurationParser.parseSeconds("30d", 300));
    }

    @Test
    void timeLeftReadsInItsTwoLargestUnits() {
        assertEquals("9m 55s", DurationParser.remaining(595));
        assertEquals("10m", DurationParser.remaining(600));
        assertEquals("23h 59m", DurationParser.remaining(86_340));
        assertEquals("2d 3h", DurationParser.remaining(2 * 86_400 + 3 * 3_600 + 59));
        assertEquals("45s", DurationParser.remaining(45));
        assertEquals("0s", DurationParser.remaining(0));
    }
}
