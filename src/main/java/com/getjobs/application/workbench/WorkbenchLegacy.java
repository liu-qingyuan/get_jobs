package com.getjobs.application.workbench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.net.URI;
import java.util.Set;

/** Read-only Adapter for known local legacy formats; never starts batch/apply. */
final class WorkbenchLegacy {
    private final Path jobsdb;
    private final ObjectMapper json;
    WorkbenchLegacy(Path jobsdb, ObjectMapper json) { this.jobsdb = jobsdb; this.json = json; }

    Path directory() { return jobsdb.toAbsolutePath().normalize(); }

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
        try {
            result.set("jobsdbHistory", history(null));
        } catch (WorkbenchException e) {
            result.putArray("jobsdbHistory");
            result.put("jobsdbError", e.getMessage());
        }
        return result;
    }

    ObjectNode attempt(String jobId) {
        var rows = history(jobId);
        return rows.isEmpty() ? null : (ObjectNode) rows.get(0);
    }

    boolean attempted(String url) {
        URI uri = URI.create(url);
        if (!"hk.jobsdb.com".equalsIgnoreCase(uri.getHost()) || !uri.getPath().matches("/job/[0-9]+/?")) return false;
        return attempt(JobsDbTarget.parse(url).id()) != null;
    }

    private ArrayNode history(String jobId) {
        if (Files.notExists(directory())) return json.createArrayNode();
        // The history command uses only the Python standard library. It can read
        // historical installations even when the browser runtime is unavailable.
        var args = new ArrayList<>(List.of("python3", "scripts/jobsdb/jobsdb.py", "history",
                "--json", "--data-dir", directory().toString()));
        if (jobId != null) args.addAll(List.of("--job-id", jobId));
        try (var reader = Executors.newVirtualThreadPerTaskExecutor()) {
            var child = new ProcessBuilder(args).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            try {
                var output = reader.submit(() -> json.readTree(child.getInputStream()));
                if (!child.waitFor(5, TimeUnit.SECONDS) || child.exitValue() != 0) throw new IllegalStateException();
                var envelope = output.get();
                if (envelope == null || !envelope.path("schemaVersion").isInt() || envelope.path("schemaVersion").intValue() != 1
                        || !(envelope.path("attempts") instanceof ArrayNode rows)) throw new IllegalStateException();
                for (var row : rows) {
                    if (!row.isObject() || !row.path("id").isTextual() || !row.path("status").isTextual()
                            || (jobId != null && !jobId.equals(row.path("id").asText()))) throw new IllegalStateException();
                }
                return rows;
            } finally {
                if (child.isAlive()) child.destroyForcibly();
            }
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new WorkbenchException(409, "JobsDB 历史不可读取，暂停此岗位以免重复投递");
        }
    }
}
