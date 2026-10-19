package com.neomechanical.neomoderation.moderation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaseLogTest {
    @TempDir
    Path tempDir;

    @Test
    void recordsAndQueriesCases() {
        try (CaseLog log = CaseLog.open(tempDir.resolve("cases.db"), Logger.getLogger("test"))) {
            assertTrue(log.isAvailable());

            log.record(1000L, "uuid-a", "Alice", "chat", "blocked_word:scam", "clear, mute 5m", "enforce", "buy my ****");
            log.record(2000L, "uuid-b", "Bob", "sign", "spam:caps", "censored", "enforce", "");
            log.record(3000L, "uuid-a", "Alice", "chat", "platform", "clear", "monitor", "");

            List<CaseLog.CaseRecord> all = log.recent(null, 10);
            assertEquals(3, all.size());
            assertEquals("platform", all.get(0).reason(), "newest first");

            List<CaseLog.CaseRecord> alice = log.recent("alice", 10);
            assertEquals(2, alice.size(), "player filter is case-insensitive");

            CaseLog.CaseRecord second = log.byId(all.get(1).id()).orElseThrow();
            assertEquals("sign", second.surface());
            assertEquals("spam:caps", second.reason());
            assertTrue(log.byId(9999).isEmpty());
        }
    }

    @Test
    void countsCloudDetectionsOfChatAndMapsSinceAMoment() {
        try (CaseLog log = CaseLog.open(tempDir.resolve("cloud.db"), Logger.getLogger("test"))) {
            log.record(1000L, "uuid-a", "Alice", "chat", "platform:harassment", "blocked", "monitor", "");
            log.record(5000L, "uuid-a", "Alice", "chat", "platform:scam", "blocked", "monitor", "");
            log.record(6000L, "uuid-b", "Bob", "map_art", "map_art:sexual", "blocked", "monitor", "#7");
            log.record(7000L, "uuid-b", "Bob", "chat", "blocked_word:badword", "blocked", "enforce", "");

            assertEquals(2L, log.countCloudSince(5000L), "only cloud detections since the moment count");
            assertEquals(3L, log.countCloudSince(0L));
        }
    }
}
