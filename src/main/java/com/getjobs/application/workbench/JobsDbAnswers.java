package com.getjobs.application.workbench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashSet;
import java.util.Set;
import static com.getjobs.application.workbench.WorkbenchService.*;

/** Owns the confirmed question schema and its immutable, scope-bound provenance; never infers answers from facts. */
final class JobsDbAnswers {
    private final ObjectMapper json;
    JobsDbAnswers(ObjectMapper json) { this.json = json; }

    ObjectNode confirm(ObjectNode input, int version) throws Exception {
        require(input.path("confirmed").isBoolean() && input.path("confirmed").asBoolean(), "JobsDB 答案库须由用户明确确认");
        require(input.path("entries").isArray() && input.path("entries").size() <= 100, "JobsDB 答案库 entries 须为最多 100 题的数组");
        var entries = new java.util.TreeMap<String, ObjectNode>();
        for (var item : input.path("entries")) {
            require(item instanceof ObjectNode, "JobsDB 答案库题目须为对象");
            var entry = json.createObjectNode();
            String name = text((ObjectNode) item, "name", 200, true);
            require(name.matches("questionnaire\\.[A-Za-z0-9_.:-]+") && !entries.containsKey(name), "JobsDB 答案库问题名无效或冲突，不能按顺序猜选");
            entry.put("name", name).put("label", text((ObjectNode) item, "label", 2000, true))
                    .put("type", choice((ObjectNode) item, "type", Set.of("select", "radio", "checkbox")));
            require(item.path("options").isArray() && !item.path("options").isEmpty() && item.path("options").size() <= 100, "题目须列明完整精确选项标签");
            var options = entry.putArray("options"); var labels = new HashSet<String>();
            for (var option : item.path("options")) {
                require(option.isTextual() && !option.asText().isBlank() && option.asText().length() <= 2000 && labels.add(option.asText()), "选项标签须唯一且非空，歧义不能批准");
                options.add(option.asText());
            }
            var value = item.path("value");
            if (entry.path("type").asText().equals("checkbox")) {
                require(value.isArray() && !value.isEmpty(), "复选题须批准唯一标签数组");
                var selected = new HashSet<String>();
                for (var label : value) require(label.isTextual() && labels.contains(label.asText()) && selected.add(label.asText()), "复选答案不在选项内或有冲突");
            } else require(value.isTextual() && labels.contains(value.asText()), "批准答案须为唯一精确选项标签");
            entry.set("value", value.deepCopy()); entries.put(name, entry);
        }
        var bank = json.createObjectNode().put("id", "scope:" + version + ":jobsdb").put("scopeVersion", version).put("version", version)
                .put("confirmed", true).put("confirmedAt", now());
        var rows = bank.putArray("entries"); entries.values().forEach(rows::add);
        bank.put("sha256", hash(rows)); return bank;
    }

    ObjectNode current(ObjectNode scope) throws Exception {
        require(scope != null && scope.path("jobsDbAnswerBank") instanceof ObjectNode, "JobsDB 须先在当前范围冻结已确认答案库；不采用旧全局 answers 或普通事实");
        var bank = (ObjectNode) scope.path("jobsDbAnswerBank");
        require(bank.path("confirmed").asBoolean() && bank.path("scopeVersion").equals(scope.path("version"))
                && bank.path("version").equals(scope.path("version")) && !bank.path("confirmedAt").asText().isBlank()
                && hash(bank.path("entries")).equals(bank.path("sha256").asText()), "JobsDB 答案库未确认、来源版本变化或内容冲突，请重新确认范围");
        return bank.deepCopy();
    }

    ObjectNode values(ObjectNode bank) {
        var answers = json.createObjectNode();
        for (var entry : bank.path("entries")) answers.set(entry.path("name").asText(), entry.path("value").deepCopy());
        return answers;
    }

    void checkQuestions(ObjectNode task, JsonNode observed) {
        require(observed.isObject() && observed.path("complete").isBoolean() && observed.path("complete").asBoolean()
                && observed.path("jobId").equals(task.path("jobId")) && observed.path("questions").isArray()
                && observed.path("questions").size() <= 100, "必须只读核对同岗的完整 role-requirements；未知时无 claim/effect");
        var names = new HashSet<String>();
        for (var question : observed.path("questions")) {
            String name = question.path("name").asText();
            require(question.isObject() && names.add(name) && question.path("required").isBoolean()
                    && !question.path("label").asText().isBlank() && Set.of("select", "radio", "checkbox").contains(question.path("type").asText())
                    && question.path("options").isArray() && !question.path("options").isEmpty(), "问题 identity/控件不完整或歧义，不能 claim");
            var options = new HashSet<String>();
            for (var label : question.path("options")) require(label.isTextual() && !label.asText().isBlank() && options.add(label.asText()), "实际问题选项歧义，不能 claim");
            var value = task.path("answers").path(name);
            if (question.path("type").asText().equals("checkbox")) {
                require(value.isArray() && !value.isEmpty(), "缺少已确认复选答案：" + name);
                for (var label : value) require(label.isTextual() && options.contains(label.asText()), "实际问题没有已批准选项：" + name);
            } else require(value.isTextual() && options.contains(value.asText()), "未知必答题或缺少已确认答案：" + name);
            if (task.has("answerBank")) {
                JsonNode approved = null;
                for (var entry : task.path("answerBank").path("entries")) if (entry.path("name").asText().equals(name)) approved = entry;
                var approvedOptions = new HashSet<String>();
                if (approved != null) for (var option : approved.path("options")) approvedOptions.add(option.asText());
                require(approved != null && approved.path("label").equals(question.path("label")) && approved.path("type").equals(question.path("type"))
                        && approvedOptions.equals(options), "实际问题与已确认答案库不匹配：" + name);
            }
        }
        if (task.at("/answersSource/kind").asText().equals("SINGLE_JOB_CONFIRMATION"))
            require(names.size() == task.path("answers").size(), "实际问卷缺少已确认的问题");
    }

    String hash(JsonNode value) throws Exception {
        return WorkbenchService.hash(json.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .writeValueAsBytes(json.convertValue(value, Object.class)));
    }
}
