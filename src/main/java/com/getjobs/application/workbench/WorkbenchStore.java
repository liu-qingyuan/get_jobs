package com.getjobs.application.workbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.*;

/** Owns the isolated local store. Writes serialize across processes before reading state. */
final class WorkbenchStore {
    interface Operation<T> { T run(Connection connection) throws Exception; }
    final Path root;
    final ObjectMapper json;
    private final String url;

    WorkbenchStore(Path root, ObjectMapper json) {
        this.root = root.toAbsolutePath().normalize();
        this.json = json;
        try {
            Files.createDirectories(this.root);
            try { Files.setPosixFilePermissions(this.root, PosixFilePermissions.fromString("rwx------")); }
            catch (UnsupportedOperationException ignored) { /* Windows ACLs are managed by the user. */ }
            url = "jdbc:sqlite:" + this.root.resolve("workbench.db");
            write(c -> {
                c.createStatement().execute("CREATE TABLE IF NOT EXISTS workbench_record (kind TEXT NOT NULL, id TEXT NOT NULL, data TEXT NOT NULL, PRIMARY KEY(kind,id))");
                return null;
            });
        } catch (Exception e) { throw new IllegalStateException("无法初始化工作台本地存储", e); }
    }

    <T> T write(Operation<T> operation) {
        try (var c = DriverManager.getConnection(url)) {
            c.createStatement().execute("PRAGMA busy_timeout=5000");
            c.createStatement().execute("BEGIN IMMEDIATE");
            try {
                T result = operation.run(c);
                c.createStatement().execute("COMMIT");
                return result;
            } catch (Exception e) {
                c.createStatement().execute("ROLLBACK");
                if (e instanceof RuntimeException runtime) throw runtime;
                throw new IllegalStateException("工作台存储操作失败", e);
            }
        } catch (RuntimeException e) { throw e; }
        catch (Exception e) { throw new IllegalStateException("工作台存储暂不可用", e); }
    }

    ObjectNode get(Connection c, String kind, String id) throws Exception {
        try (var q = c.prepareStatement("SELECT data FROM workbench_record WHERE kind=? AND id=?")) {
            q.setString(1, kind); q.setString(2, id);
            try (var rows = q.executeQuery()) {
                if (!rows.next()) throw new WorkbenchException(404, "记录不存在");
                return (ObjectNode) json.readTree(rows.getString(1));
            }
        }
    }

    ArrayNode list(Connection c, String kind) throws Exception {
        var output = json.createArrayNode();
        try (var q = c.prepareStatement("SELECT data FROM workbench_record WHERE kind=? ORDER BY rowid")) {
            q.setString(1, kind);
            try (var rows = q.executeQuery()) {
                while (rows.next()) output.add(json.readTree(rows.getString(1)));
            }
        }
        return output;
    }

    void save(Connection c, String kind, ObjectNode record) throws Exception {
        try (var q = c.prepareStatement("INSERT INTO workbench_record(kind,id,data) VALUES(?,?,?) ON CONFLICT(kind,id) DO UPDATE SET data=excluded.data")) {
            q.setString(1, kind); q.setString(2, record.path("id").asText()); q.setString(3, json.writeValueAsString(record)); q.executeUpdate();
        }
    }

    void delete(Connection c, String kind, String id) throws Exception {
        get(c, kind, id);
        try (var q = c.prepareStatement("DELETE FROM workbench_record WHERE kind=? AND id=?")) {
            q.setString(1, kind); q.setString(2, id); q.executeUpdate();
        }
    }
}
