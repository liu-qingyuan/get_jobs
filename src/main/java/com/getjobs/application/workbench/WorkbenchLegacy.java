package com.getjobs.application.workbench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.sql.*;
import java.net.URI;
import java.util.Set;

/** Read-only Adapter for known local legacy formats; never starts batch/apply. */
final class WorkbenchLegacy {
    private final Path jobsdb;
    private final ObjectMapper json;
    WorkbenchLegacy(Path jobsdb, ObjectMapper json) { this.jobsdb = jobsdb; this.json = json; }

    ObjectNode preview() {
        var facts = json.createArrayNode();
        var profile = read("application-facts.json");
        var contact = read("contact-preferences.json");
        Set<String> common = Set.of("name", "education", "language_abilities", "database_experience", "additional_skill_experience");
        Set<String> regional = Set.of("availability", "visa", "salary_preferences", "current_salary");
        profile.fields().forEachRemaining(entry -> {
            String key = entry.getKey();
            if (common.contains(key) || regional.contains(key) || (key.equals("application_contact") && contact.isEmpty()))
                add(facts, key, scrub(entry.getValue()), common.contains(key) ? "COMMON" : "HK", "application-facts.json");
        });
        for (String key : Set.of("email", "phone", "mainland_phone", "hong_kong_application_correspondence_address", "notice_period_days"))
            if (contact.hasNonNull(key)) add(facts, key, scrub(contact.get(key)), key.equals("mainland_phone") ? "MAINLAND" : "HK", "contact-preferences.json");
        return json.createObjectNode().set("facts", facts);
    }

    private void add(ArrayNode facts, String key, JsonNode value, String scope, String source) {
        if (value == null || value.isNull() || value.isMissingNode() || (value.isContainerNode() && value.isEmpty())) return;
        facts.add(json.createObjectNode().put("id", "").put("key", key).put("label", key)
                .put("value", value.isTextual() ? value.asText() : value.toString())
                .put("scope", scope).put("confirmed", false).put("source", source + "（导入预览，需本人核对）"));
    }

    private JsonNode scrub(JsonNode value) {
        if (value.isObject()) {
            var result = json.createObjectNode();
            value.fields().forEachRemaining(entry -> {
                if (!entry.getKey().matches("(?i).*(password|cookie|token|secret|api.?key|credential|verification|otp).*"))
                    result.set(entry.getKey(), scrub(entry.getValue()));
            });
            return result;
        }
        if (value.isArray()) { var result = json.createArrayNode(); value.forEach(item -> result.add(scrub(item))); return result; }
        return value.deepCopy();
    }

    private ObjectNode read(String filename) {
        Path file = jobsdb.resolve(filename);
        if (!Files.exists(file)) return json.createObjectNode();
        try {
            if (!Files.isRegularFile(file) || Files.isSymbolicLink(file) || Files.size(file) > 512000)
                throw new IllegalArgumentException();
            var node = json.readTree(Files.readString(file));
            if (!node.isObject()) throw new IllegalArgumentException();
            return (ObjectNode) node;
        } catch (Exception e) { throw new WorkbenchException(400, filename + " 无法预览，请检查文件格式和大小"); }
    }

    ObjectNode platforms() {
        var result = json.createObjectNode().put("bossUrl", "/boss")
                .put("jobsdbAvailable", Files.isExecutable(jobsdb.resolve("venv/bin/python")));
        var history = json.createArrayNode();
        if (Files.isRegularFile(jobsdb.resolve("applications.db"))) {
            try (var c = openLedger(); var q = c.prepareStatement("SELECT job_id,status,updated_at FROM applications ORDER BY updated_at DESC LIMIT 100"); var rows = q.executeQuery()) {
                while (rows.next()) history.add(json.createObjectNode().put("id", rows.getString(1)).put("status", rows.getString(2)).put("updated_at", rows.getString(3)));
            } catch (Exception e) { result.put("jobsdbError", "JobsDB 历史不可读取，请检查原 CLI；未修改原账本"); }
        }
        result.set("jobsdbHistory", history);
        return result;
    }

    boolean attempted(String url) {
        URI uri = URI.create(url);
        if (!"hk.jobsdb.com".equalsIgnoreCase(uri.getHost()) || !uri.getPath().matches("/job/[0-9]+/?") || !Files.exists(jobsdb.resolve("applications.db"))) return false;
        String id = uri.getPath().replaceAll("^/job/|/$", "");
        try (var c = openLedger(); var q = c.prepareStatement("SELECT 1 FROM applications WHERE job_id=?")) {
            q.setString(1, id);
            try (var rows = q.executeQuery()) { return rows.next(); }
        } catch (Exception e) { throw new WorkbenchException(409, "无法核对 JobsDB 原账本，暂停此岗位以免重复投递"); }
    }

    private Connection openLedger() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + jobsdb.resolve("applications.db").toUri() + "?mode=ro");
    }
}
