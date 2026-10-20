package com.neomechanical.neomoderation.moderation;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MonitorStatsTest {
    @Test
    void bucketsReasonsByTheKindOfRule() {
        MonitorStats stats = new MonitorStats();
        stats.record("blocked_word:scam");
        stats.record("blocked_word:badword");
        stats.record("split_blocked_word:badword");
        stats.record("blocked_url:any");
        stats.record("server_ad:play.example.net");
        stats.record("spam:caps");
        stats.record("platform");
        stats.record("map_art:sexual");

        assertEquals(8, stats.total());
        assertEquals(Map.of("word", 2L, "split-word", 1L, "link", 1L, "server-ad", 1L, "spam", 1L,
                "cloud", 1L, "map", 1L), stats.byReason());
        assertEquals(2, stats.cloudTotal());
    }

    @Test
    void handlesNullAndBlankReasons() {
        MonitorStats stats = new MonitorStats();
        stats.record(null);
        stats.record("  ");

        assertEquals(2, stats.total());
        assertEquals(Map.of("other", 2L), stats.byReason());
    }
}
