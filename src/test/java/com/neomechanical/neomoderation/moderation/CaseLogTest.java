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

    @Test
    void prunesOldAndExcessRowsWhileKeepingLifetimeCountAcrossReopen() throws Exception {
        Path db = tempDir.resolve("retention.db");
        long day = 86_400_000L;
        long now = 100 * day;
        try (CaseLog log = CaseLog.open(db, Logger.getLogger("test"))) {
            log.record(now - 120 * day, "uuid-1", "OldPlayer", "chat", "blocked_word:a", "blocked", "enforce", "");
            log.record(now - 95 * day, "uuid-2", "OldPlayer", "chat", "blocked_word:b", "blocked", "enforce", "");
            log.record(now - 10 * day, "uuid-3", "RecentA", "chat", "blocked_word:c", "blocked", "enforce", "");
            log.record(now - 5 * day, "uuid-4", "RecentB", "chat", "blocked_word:d", "blocked", "enforce", "");
            log.record(now - 1 * day, "uuid-5", "RecentC", "chat", "blocked_word:e", "blocked", "enforce", "");

            int removed = log.prune(now, 90, 2);
            assertEquals(3, removed, "two expired by age and one excess by maxRows");
            List<CaseLog.CaseRecord> kept = log.recent(null, 10);
            assertEquals(2, kept.size());
            assertEquals("RecentC", kept.get(0).player());
            assertEquals("RecentB", kept.get(1).player());
            assertEquals(5L, log.totalCount(), "lifetime count must not shrink after pruning");
        }
        try (CaseLog reopened = CaseLog.open(db, Logger.getLogger("test"))) {
            assertEquals(5L, reopened.totalCount(), "lifetime count survives reopen via sqlite_sequence");
            assertEquals(2, reopened.recent(null, 10).size());
        }
        try (var conn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + db);
             var st = conn.createStatement();
             var rs = st.executeQuery("SELECT name FROM sqlite_master WHERE type='index' AND name='cases_ts'")) {
            assertTrue(rs.next(), "cases_ts index must exist for fast time-range queries and pruning");
        }
    }

    @Test
    void automaticRetentionBoundsBusyServerWrites() {
        Path db = tempDir.resolve("busy.db");
        long now = 1_700_000_000_000L;
        try (CaseLog log = CaseLog.open(db, Logger.getLogger("test"))) {
            log.configureRetention(90, 100);
            for (int i = 0; i < 600; i++) {
                log.record(now + i * 1000L, "uuid-" + i, "Player" + (i % 10), "chat",
                        "blocked_word:spam", "blocked", "enforce", "");
            }
            assertEquals(600L, log.totalCount());
            List<CaseLog.CaseRecord> remaining = log.recent(null, 500);
            assertTrue(remaining.size() <= 200,
                    "periodic pruning keeps stored rows near maxRows cap, got " + remaining.size());
            assertEquals("Player9", remaining.get(0).player());
        }
    }
}
