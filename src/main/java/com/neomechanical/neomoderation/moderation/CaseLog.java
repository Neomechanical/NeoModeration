package com.neomechanical.neomoderation.moderation;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Local SQLite case history. Spigot/Paper bundle the SQLite driver, so nothing
 * is shaded; if the driver is ever missing the log degrades to a no-op with a
 * single warning. All access serializes on one connection — writes are small
 * and callers already invoke {@link #record} off the main thread.
 */
public final class CaseLog implements AutoCloseable {
    public record CaseRecord(
            long id,
            long timestamp,
            String uuid,
            String player,
            String surface,
            String reason,
            String action,
            String mode,
            String preview,
            String status
    ) {
        public CaseRecord(
                long id,
                long timestamp,
                String uuid,
                String player,
                String surface,
                String reason,
                String action,
                String mode,
                String preview
        ) {
            this(id, timestamp, uuid, player, surface, reason, action, mode, preview, "open");
        }
    }

    private static final long DAY_MILLIS = 86_400_000L;
    private static final int PRUNE_EVERY_WRITES = 250;

    private record Retention(int retentionDays, int maxRows) {
        boolean active() {
            return retentionDays > 0 || maxRows > 0;
        }
    }

    private final Object lock = new Object();
    private final Connection connection;
    /** Lifetime rows written so far (from sqlite_sequence / MAX(id)); kept here so no reader waits on SQLite. */
    private final AtomicLong total;
    private volatile Retention retention = new Retention(0, 0);
    private long writesSincePrune;

    private CaseLog(Connection connection, long total) {
        this.connection = connection;
        this.total = new AtomicLong(total);
    }

    public static CaseLog open(Path databasePath, Logger logger) {
        try {
            Class.forName("org.sqlite.JDBC");
            Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS cases (
                          id INTEGER PRIMARY KEY AUTOINCREMENT,
                          ts INTEGER NOT NULL,
                          uuid TEXT NOT NULL,
                          player TEXT NOT NULL,
                          surface TEXT NOT NULL,
                          reason TEXT NOT NULL,
                          action TEXT NOT NULL,
                          mode TEXT NOT NULL,
                          preview TEXT NOT NULL DEFAULT '',
                          status TEXT NOT NULL DEFAULT 'open'
                        )""");
                try {
                    statement.executeUpdate("ALTER TABLE cases ADD COLUMN status TEXT NOT NULL DEFAULT 'open'");
                } catch (SQLException ignored) {
                    // Column already exists on fresh or already-migrated databases.
                }
                statement.executeUpdate(
                        "CREATE INDEX IF NOT EXISTS cases_player ON cases(player COLLATE NOCASE)");
                statement.executeUpdate(
                        "CREATE INDEX IF NOT EXISTS cases_ts ON cases(ts)");
            }
            long total = readLifetimeTotal(connection);
            return new CaseLog(connection, total);
        } catch (ClassNotFoundException | SQLException e) {
            logger.warning("Case history unavailable (" + e.getMessage() + "); detections will not be logged to disk.");
            return new CaseLog(null, 0L);
        }
    }

    private static long readLifetimeTotal(Connection connection) throws SQLException {
        long maxId = 0L;
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT MAX(id) FROM cases")) {
            if (rows.next()) {
                maxId = rows.getLong(1);
            }
        }
        long seq = 0L;
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT seq FROM sqlite_sequence WHERE name = 'cases'")) {
            if (rows.next()) {
                seq = rows.getLong(1);
            }
        } catch (SQLException ignored) {
            // sqlite_sequence is created on first insert into an AUTOINCREMENT table.
        }
        return Math.max(maxId, seq);
    }

    /** Configures automatic pruning bounds (0 disables either bound). */
    public void configureRetention(int retentionDays, int maxRows) {
        this.retention = new Retention(Math.max(0, retentionDays), Math.max(0, maxRows));
    }

    /** Prunes using the currently configured retention bounds. */
    public int prune(long nowMillis) {
        Retention active = this.retention;
        return prune(nowMillis, active.retentionDays(), active.maxRows());
    }

    /**
     * Deletes rows older than {@code retentionDays} and rows beyond the newest {@code maxRows}.
     * Lifetime {@link #totalCount()} is unaffected because AUTOINCREMENT tracks the highest issued id.
     */
    public int prune(long nowMillis, int retentionDays, int maxRows) {
        if (connection == null || (retentionDays <= 0 && maxRows <= 0)) {
            return 0;
        }
        synchronized (lock) {
            return pruneLocked(nowMillis, retentionDays, maxRows);
        }
    }

    private int pruneLocked(long nowMillis, int retentionDays, int maxRows) {
        int deleted = 0;
        if (retentionDays > 0) {
            long cutoff = nowMillis - (long) retentionDays * DAY_MILLIS;
            try (PreparedStatement deleteAge = connection.prepareStatement("DELETE FROM cases WHERE ts < ?")) {
                deleteAge.setLong(1, cutoff);
                deleted += deleteAge.executeUpdate();
            } catch (SQLException ignored) {
                // Retention must never disrupt moderation.
            }
        }
        if (maxRows > 0) {
            try (PreparedStatement deleteExcess = connection.prepareStatement(
                    "DELETE FROM cases WHERE id <= (SELECT id FROM cases ORDER BY id DESC LIMIT 1 OFFSET ?)")) {
                deleteExcess.setInt(1, maxRows);
                deleted += deleteExcess.executeUpdate();
            } catch (SQLException ignored) {
                // Retention must never disrupt moderation.
            }
        }
        return deleted;
    }

    public boolean isAvailable() {
        return connection != null;
    }

    /** The command that lists one player's cases, whatever their name looks like ("1234" too). */
    public static String historyCommand(String label, String player) {
        return "/" + label + " cases " + player + " page 1";
    }

    public long record(long timestamp, String uuid, String player, String surface,
                       String reason, String action, String mode, String preview) {
        if (connection == null) {
            return -1L;
        }
        synchronized (lock) {
            long id = -1L;
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO cases (ts, uuid, player, surface, reason, action, mode, preview, status) VALUES (?,?,?,?,?,?,?,?,'open')",
                    Statement.RETURN_GENERATED_KEYS)) {
                insert.setLong(1, timestamp);
                insert.setString(2, uuid);
                insert.setString(3, player);
                insert.setString(4, surface);
                insert.setString(5, reason);
                insert.setString(6, action);
                insert.setString(7, mode);
                insert.setString(8, preview == null ? "" : preview);
                insert.executeUpdate();
                try (ResultSet keys = insert.getGeneratedKeys()) {
                    if (keys.next()) {
                        id = keys.getLong(1);
                        total.accumulateAndGet(id, Math::max);
                    }
                }
            } catch (SQLException ignored) {
                // A failed audit row must never break moderation.
            }
            if (id >= 0L) {
                Retention active = this.retention;
                if (active.active() && ++writesSincePrune >= PRUNE_EVERY_WRITES) {
                    writesSincePrune = 0;
                    pruneLocked(timestamp, active.retentionDays(), active.maxRows());
                }
            }
            return id;
        }
    }

    public boolean updateStatus(long id, String status) {
        if (connection == null || status == null || status.isBlank()) {
            return false;
        }
        synchronized (lock) {
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE cases SET status = ? WHERE id = ?")) {
                update.setString(1, status.trim().toLowerCase(Locale.ROOT));
                update.setLong(2, id);
                return update.executeUpdate() > 0;
            } catch (SQLException ignored) {
                return false;
            }
        }
    }

    /** How many cloud detections, chat and map art, were logged at or after {@code sinceMillis}; -1 without SQLite. */
    public long countCloudSince(long sinceMillis) {
        if (connection == null) {
            return -1L;
        }
        synchronized (lock) {
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT COUNT(*) FROM cases WHERE ts >= ? AND (reason LIKE 'platform:%' OR reason LIKE 'map_art%')")) {
                query.setLong(1, sinceMillis);
                try (ResultSet rows = query.executeQuery()) {
                    return rows.next() ? rows.getLong(1) : 0L;
                }
            } catch (SQLException ignored) {
                return -1L;
            }
        }
    }

    /** How many cases were ever written. Never touches SQLite, so any thread may ask (bStats asks on the server thread). */
    public long totalCount() {
        return total.get();
    }

    public List<CaseRecord> recent(String playerFilter, int limit) {
        return recent(playerFilter, limit, 0);
    }

    /** Newest first, skipping {@code offset} rows, for paging. */
    public List<CaseRecord> recent(String playerFilter, int limit, int offset) {
        if (connection == null) {
            return List.of();
        }
        String sql = playerFilter == null
                ? "SELECT * FROM cases ORDER BY id DESC LIMIT ? OFFSET ?"
                : "SELECT * FROM cases WHERE player = ? COLLATE NOCASE ORDER BY id DESC LIMIT ? OFFSET ?";
        synchronized (lock) {
            try (PreparedStatement query = connection.prepareStatement(sql)) {
                int index = 1;
                if (playerFilter != null) {
                    query.setString(index++, playerFilter);
                }
                query.setInt(index++, Math.max(1, limit));
                query.setInt(index, Math.max(0, offset));
                try (ResultSet rows = query.executeQuery()) {
                    List<CaseRecord> result = new ArrayList<>();
                    while (rows.next()) {
                        result.add(read(rows));
                    }
                    return result;
                }
            } catch (SQLException ignored) {
                return List.of();
            }
        }
    }

    public Optional<CaseRecord> byId(long id) {
        if (connection == null) {
            return Optional.empty();
        }
        synchronized (lock) {
            try (PreparedStatement query = connection.prepareStatement("SELECT * FROM cases WHERE id = ?")) {
                query.setLong(1, id);
                try (ResultSet rows = query.executeQuery()) {
                    return rows.next() ? Optional.of(read(rows)) : Optional.empty();
                }
            } catch (SQLException ignored) {
                return Optional.empty();
            }
        }
    }

    private static CaseRecord read(ResultSet rows) throws SQLException {
        String status = "open";
        try {
            String rawStatus = rows.getString("status");
            if (rawStatus != null && !rawStatus.isBlank()) {
                status = rawStatus;
            }
        } catch (SQLException ignored) {
        }
        return new CaseRecord(
                rows.getLong("id"),
                rows.getLong("ts"),
                rows.getString("uuid"),
                rows.getString("player"),
                rows.getString("surface"),
                rows.getString("reason"),
                rows.getString("action"),
                rows.getString("mode"),
                rows.getString("preview"),
                status
        );
    }

    @Override
    public void close() {
        if (connection == null) {
            return;
        }
        synchronized (lock) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // Shutdown path; nothing useful to do.
            }
        }
    }
}
