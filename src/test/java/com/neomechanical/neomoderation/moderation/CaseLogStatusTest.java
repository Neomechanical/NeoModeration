package com.neomechanical.neomoderation.moderation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Optional;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaseLogStatusTest {

    @TempDir
    Path tempDir;

    @Test
    void recordReturnsSequentialPositiveIdsAndDefaultsStatusToOpen() {
        Path dbPath = tempDir.resolve("status-test.db");
        try (CaseLog log = CaseLog.open(dbPath, Logger.getLogger("test"))) {
            assertTrue(log.isAvailable());

            long id1 = log.record(1000L, "uuid-1", "Player1", "chat", "blocked_word:scam", "mute", "enforce", "buy stuff");
            long id2 = log.record(2000L, "uuid-2", "Player2", "sign", "blocked_url:evil", "block", "enforce", "evil.com");

            assertEquals(1L, id1);
            assertEquals(2L, id2);

            Optional<CaseLog.CaseRecord> record1 = log.byId(id1);
            assertTrue(record1.isPresent());
            assertEquals("open", record1.get().status());
            assertEquals("Player1", record1.get().player());

            Optional<CaseLog.CaseRecord> record2 = log.byId(id2);
            assertTrue(record2.isPresent());
            assertEquals("open", record2.get().status());
            assertEquals("Player2", record2.get().player());
        }
    }

    @Test
    void totalCountReturnsPersistedRowCount() {
        Path dbPath = tempDir.resolve("count-test.db");
        try (CaseLog log = CaseLog.open(dbPath, Logger.getLogger("test"))) {
            assertEquals(0L, log.totalCount());

            log.record(1000L, "uuid-1", "Alice", "chat", "word", "none", "monitor", "");
            assertEquals(1L, log.totalCount());

            log.record(2000L, "uuid-2", "Bob", "chat", "word", "none", "monitor", "");
            log.record(3000L, "uuid-3", "Charlie", "sign", "word", "none", "monitor", "");
            assertEquals(3L, log.totalCount());
        }
    }

    @Test
    void updateStatusModifiesStatusAndReturnsTrueForExistingId() {
        Path dbPath = tempDir.resolve("update-test.db");
        try (CaseLog log = CaseLog.open(dbPath, Logger.getLogger("test"))) {
            long id = log.record(1000L, "uuid-1", "Alice", "chat", "blocked_word:bad", "mute", "enforce", "preview");
            assertEquals("open", log.byId(id).orElseThrow().status());

            boolean resolved = log.updateStatus(id, "resolved");
            assertTrue(resolved);
            assertEquals("resolved", log.byId(id).orElseThrow().status());

            boolean dismissed = log.updateStatus(id, "dismissed");
            assertTrue(dismissed);
            assertEquals("dismissed", log.byId(id).orElseThrow().status());
        }
    }

    @Test
    void updateStatusReturnsFalseForNonExistentId() {
        Path dbPath = tempDir.resolve("missing-id-test.db");
        try (CaseLog log = CaseLog.open(dbPath, Logger.getLogger("test"))) {
            assertFalse(log.updateStatus(999L, "resolved"));
        }
    }

    @Test
    void legacyDatabaseWithoutStatusColumnMigratesAutomatically() throws Exception {
        Path legacyDb = tempDir.resolve("legacy-schema.db");

        // Create legacy schema without status column and insert 1 row
        Class.forName("org.sqlite.JDBC");
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + legacyDb);
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("""
                    CREATE TABLE cases (
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      ts INTEGER NOT NULL,
                      uuid TEXT NOT NULL,
                      player TEXT NOT NULL,
                      surface TEXT NOT NULL,
                      reason TEXT NOT NULL,
                      action TEXT NOT NULL,
                      mode TEXT NOT NULL,
                      preview TEXT NOT NULL DEFAULT ''
                    )""");
            stmt.executeUpdate("""
                    INSERT INTO cases (ts, uuid, player, surface, reason, action, mode, preview)
                    VALUES (1000, 'uuid-legacy', 'LegacyPlayer', 'chat', 'blocked_word:old', 'mute', 'enforce', 'bad')
                    """);
        }

        // Open via CaseLog - should auto-migrate via ALTER TABLE
        try (CaseLog log = CaseLog.open(legacyDb, Logger.getLogger("test"))) {
            assertTrue(log.isAvailable());

            // Check existing legacy row: status should default to "open"
            Optional<CaseLog.CaseRecord> legacyRecord = log.byId(1L);
            assertTrue(legacyRecord.isPresent());
            assertEquals("LegacyPlayer", legacyRecord.get().player());
            assertEquals("open", legacyRecord.get().status());

            // Updating status on legacy row works
            assertTrue(log.updateStatus(1L, "resolved"));
            assertEquals("resolved", log.byId(1L).orElseThrow().status());

            // Inserting new record into migrated table works seamlessly
            long newId = log.record(2000L, "uuid-new", "NewPlayer", "chat", "spam", "none", "monitor", "");
            assertEquals(2L, newId);
            assertEquals("open", log.byId(newId).orElseThrow().status());

            assertEquals(2L, log.totalCount());
        }
    }
}
