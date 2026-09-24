package com.getjobs.jobsdb;

import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Separate SQLite ledger; UNKNOWN claims survive crashes and prevent duplicate submission. */
public final class JobsDbStore implements AutoCloseable {
    private final Connection connection;
    public JobsDbStore(Path database) throws Exception {
        Files.createDirectories(database.toAbsolutePath().getParent());
        connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
        try (Statement s = connection.createStatement()) {
            s.execute("PRAGMA busy_timeout=5000");
            s.execute("CREATE TABLE IF NOT EXISTS applications (job_id TEXT PRIMARY KEY, status TEXT NOT NULL, updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)");
        }
    }
    public boolean contains(String id) throws SQLException {
        try (PreparedStatement s = connection.prepareStatement("SELECT 1 FROM applications WHERE job_id=?")) {
            s.setString(1, id);
            try (ResultSet r = s.executeQuery()) { return r.next(); }
        }
    }
    public boolean claim(String id) throws SQLException {
        try (PreparedStatement s = connection.prepareStatement("INSERT OR IGNORE INTO applications(job_id,status) VALUES(?,'UNKNOWN')")) {
            s.setString(1, id);
            return s.executeUpdate() == 1;
        }
    }
    public void finish(String id, JobsDbFlow.State status) throws SQLException {
        if (status != JobsDbFlow.State.SUBMITTED && status != JobsDbFlow.State.UNKNOWN)
            throw new IllegalArgumentException("Only confirmed or unknown submission outcomes belong in the ledger");
        try (PreparedStatement s = connection.prepareStatement("UPDATE applications SET status=?,updated_at=CURRENT_TIMESTAMP WHERE job_id=?")) {
            s.setString(1, status.name()); s.setString(2, id);
            if (s.executeUpdate() != 1) throw new IllegalStateException("Missing prior submission claim");
        }
    }
    public List<String> history() throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Statement s = connection.createStatement(); ResultSet r = s.executeQuery("SELECT job_id,status,updated_at FROM applications ORDER BY updated_at DESC")) {
            while (r.next()) rows.add(r.getString(1) + "\t" + r.getString(2) + "\t" + r.getString(3));
        }
        return rows;
    }
    @Override public void close() throws SQLException { connection.close(); }
}
