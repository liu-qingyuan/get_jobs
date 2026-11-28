package com.getjobs.application.workbench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.getjobs.worker.boss.Boss;
import com.getjobs.worker.boss.BossCandidateReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Draft preview and the immutable exact-message approval for one Boss target.
 *
 * <p>The model path only reads the frozen JD, approved facts and approved resume body. It never
 * writes an approval, claims a job or touches a page. The send path only reads an existing
 * approval and never calls a model. Both sides bind the canonical job, this attempt, the JD hash,
 * the frozen resume/facts hash and the UTF-8 bytes of the complete message.</p>
 */
final class WorkbenchBossMessage {
    static final String RULES = "boss-material-message-v1";
    /** A started/unknown/submitted attempt can never gain a new approval or a retry. */
    private static final Set<String> APPROVABLE_STATES = Set.of("PENDING", "APPROVED", "NEEDS_INPUT", "FAILED");

    @FunctionalInterface
    interface Drafter {
        String draft(String company, String title, String jd, List<Boss.ApprovedFact> facts,
                String resumeContent) throws Exception;
    }

    private record Material(ObjectNode task, String taskId, String attemptId, String canonicalUrl,
            String platformJobId, String company, String title, String jd, String jdSha256,
            List<Boss.ApprovedFact> facts, String factsSha256, String resumeSha256,
            String resumeContentSha256, String resumeContent, String materialVersion,
            String materialsSha256) { }

    private final WorkbenchService service;
    private final WorkbenchStore store;
    private final Drafter drafter;

    WorkbenchBossMessage(WorkbenchService service, Drafter drafter) {
        this.service = service;
        this.store = service.store;
        this.drafter = drafter;
    }

    private ObjectNode object() { return service.json.createObjectNode(); }

    ObjectNode draft(ObjectNode input) {
        String id = WorkbenchService.text(input, "taskId", 80, true);
        var material = store.write(c -> {
            var task = store.get(c, "task", id);
            WorkbenchService.require(APPROVABLE_STATES.contains(task.path("state").asText()),
                    "任务已开始或已经结束，不能再生成草稿；UNKNOWN/SUBMITTED 不允许重投");
            return material(task);
        });
        if (drafter == null)
            throw new WorkbenchException(409, "未配置 Boss 消息模型，不能生成草稿；可手动编写正文后核准。未点击、未发送");
        String message;
        try {
            message = drafter.draft(material.company(), material.title(), material.jd(),
                    material.facts(), material.resumeContent());
        } catch (WorkbenchException e) {
            throw e;
        } catch (Exception e) {
            String reason = e.getMessage() == null || e.getMessage().isBlank()
                    ? "模型不可用或输出无效" : e.getMessage();
            throw new WorkbenchException(409, "Boss 草稿生成失败：" + reason + "；未点击、未发送");
        }
        if (message == null || message.isBlank())
            throw new WorkbenchException(409, "模型未返回有效招呼语；未点击、未发送");
        message = message.strip();
        if (message.length() > 8000)
            throw new WorkbenchException(409, "模型返回的招呼语超出 8000 字符，未生成可用草稿；未点击、未发送");
        var result = bindings(material);
        result.put("message", message)
                .put("messageSha256", WorkbenchService.hash(message.getBytes(StandardCharsets.UTF_8)));
        return result;
    }

    /**
     * Freeze the complete operator-reviewed body. Approval is append-only: a re-approval records a
     * new version and the previous version stays in history but can no longer authorize a send.
     */
    ObjectNode approve(ObjectNode input) {
        String id = WorkbenchService.text(input, "taskId", 80, true);
        String message = exactText(input, "message", 8000);
        if (message.isBlank())
            throw new WorkbenchException(409, "请先填写完整的 Boss 招呼语正文再核准；未点击、未发送");
        return store.write(c -> {
            var task = store.get(c, "task", id);
            String state = task.path("state").asText();
            WorkbenchService.require(APPROVABLE_STATES.contains(state),
                    "任务已开始或已经结束，不能再核准招呼语；UNKNOWN/SUBMITTED 不允许重投");
            var material = material(task);
            int version = task.path("bossMessageApprovals").size() + 1;
            var approval = object().put("approvalId", WorkbenchService.id())
                    .put("version", version).put("taskId", material.taskId())
                    .put("attemptId", material.attemptId())
                    .put("packageId", task.path("packageId").asText())
                    .put("materialVersion", material.materialVersion())
                    .put("url", material.canonicalUrl()).put("platformJobId", material.platformJobId())
                    .put("company", material.company()).put("title", material.title())
                    .put("jdSha256", material.jdSha256())
                    .put("resumeSha256", material.resumeSha256())
                    .put("resumeContentSha256", material.resumeContentSha256())
                    .put("factsSha256", material.factsSha256())
                    .put("materialsSha256", material.materialsSha256())
                    .put("message", message)
                    .put("messageSha256", WorkbenchService.hash(message.getBytes(StandardCharsets.UTF_8)))
                    .put("approvedAt", WorkbenchService.now()).put("source", "USER_REVIEW");
            task.withArray("bossMessageApprovals").add(approval.deepCopy());
            task.set("bossMessageApproval", approval);
            // A cycle can stop at NEEDS_INPUT because no greeting had been approved yet. Approval is
            // the missing precondition; only a known-unsent task with current materials may return
            // to APPROVED for the explicit start action.
            if (Set.of("NEEDS_INPUT", "FAILED").contains(state)
                    && !task.has("outboundClaim") && !task.has("contactReceipt") && !task.has("claimedAttemptId")
                    && !task.path("packageId").asText().isBlank()) {
                var record = store.get(c, "preparation", task.path("packageId").asText());
                var decision = service.preparation.check(c, record, "PREPARE", task.path("id").asText());
                if (decision.path("allowed").asBoolean()) task.put("state", "APPROVED");
            }
            append(task, "MESSAGE_APPROVED",
                    "操作者核准 Boss 招呼语 v" + version + "；绑定岗位、attempt、JD、材料与完整正文；发送阶段不再调用模型",
                    "messageSha256=" + approval.path("messageSha256").asText()
                            + "; materialsSha256=" + approval.path("materialsSha256").asText());
            store.save(c, "task", task);
            return service.publicTask(task);
        });
    }

    /** The only body the send path may hand to Boss.firstContact. Never calls a model. */
    ObjectNode requireCurrent(ObjectNode task) throws Exception {
        var approval = task.path("bossMessageApproval");
        WorkbenchService.require(approval instanceof ObjectNode && !approval.path("approvalId").asText().isBlank(),
                "Boss 尚未核准招呼语；不点击、不发送");
        // Only the newest appended version may authorize a send; an older immutable version remains
        // visible in history but is never usable again.
        var history = task.path("bossMessageApprovals");
        WorkbenchService.require(history.isArray() && !history.isEmpty()
                        && history.get(history.size() - 1).equals(approval)
                        && approval.path("version").asInt() == history.size(),
                "Boss 当前核准不是最新版本，旧核准不能用；不点击");
        var material = material(task);
        WorkbenchService.require(approval.path("taskId").asText().equals(material.taskId())
                        && approval.path("attemptId").asText().equals(material.attemptId()),
                "Boss 核准记录不属于本任务/attempt；不点击");
        WorkbenchService.require(approval.path("url").asText().equals(material.canonicalUrl())
                        && approval.path("platformJobId").asText().equals(material.platformJobId())
                        && approval.path("company").asText().equals(material.company())
                        && approval.path("title").asText().equals(material.title()),
                "Boss 核准记录不属于此岗位；不点击");
        WorkbenchService.require(approval.path("jdSha256").asText().equals(material.jdSha256()),
                "Boss 岗位 JD 已变化，旧核准失效；不点击");
        WorkbenchService.require(approval.path("resumeSha256").asText().equals(material.resumeSha256())
                        && approval.path("resumeContentSha256").asText().equals(material.resumeContentSha256()),
                "Boss 核准简历已变化，旧核准失效；不点击");
        WorkbenchService.require(approval.path("factsSha256").asText().equals(material.factsSha256())
                        && approval.path("materialsSha256").asText().equals(material.materialsSha256()),
                "Boss 核准材料已变化，旧核准失效；不点击");
        String message = approval.path("message").asText();
        WorkbenchService.require(!message.isBlank(), "Boss 核准正文为空；不点击");
        WorkbenchService.require(approval.path("messageSha256").asText()
                        .equals(WorkbenchService.hash(message.getBytes(StandardCharsets.UTF_8))),
                "Boss 核准正文已变化；不点击");
        return (ObjectNode) approval;
    }

    /** Read-only view annotation; a corrupt/missing file is visible as invalid rather than trusted. */
    ObjectNode annotate(ObjectNode task) {
        if (!(task.path("bossMessageApproval") instanceof ObjectNode)) return task;
        try {
            requireCurrent(task);
            task.put("bossMessageApprovalValid", true);
            task.remove("bossMessageApprovalReason");
        } catch (Exception e) {
            task.put("bossMessageApprovalValid", false)
                    .put("bossMessageApprovalReason", e.getMessage() == null ? "核准已失效" : e.getMessage());
        }
        return task;
    }

    boolean valid(ObjectNode task) {
        if (!(task.path("bossMessageApproval") instanceof ObjectNode)) return false;
        try {
            requireCurrent(task);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private Material material(ObjectNode task) throws Exception {
        WorkbenchService.require(task.path("channel").asText().equals("BOSS")
                        && task.path("kind").asText().equals("APPLICATION"),
                "只处理 Boss 单岗岗位申请；未点击、未发送");
        String taskId = WorkbenchService.text(task, "id", 80, true);
        String attemptId = WorkbenchService.text(task, "attemptId", 100, true);
        String jd = WorkbenchService.text(task, "description", 30000, true);
        String canonical;
        try {
            canonical = BossCandidateReader.detailUrl(task.path("url").asText());
        } catch (IllegalArgumentException e) {
            throw new WorkbenchException(409, "Boss 岗位链接不规范，不能核准或发送招呼语");
        }
        String platformJobId = BossCandidateReader.jobId(canonical);
        WorkbenchService.require(task.path("platformJobId").asText().equals(platformJobId),
                "Boss 岗位身份与核准目标不一致；未点击、未发送");
        WorkbenchService.require(task.path("snapshot").isObject() && task.at("/snapshot/resume").isObject(),
                "Boss 任务缺少已批准的材料快照；请先批准岗位与材料，未生成草稿、未发送");
        // WorkbenchPreparation owns the byte/hash checks for the frozen attachment.
        var approved = service.preparation.approvedMaterial(task);
        String resumeContent = approved.resumeContent() == null ? "" : approved.resumeContent();
        WorkbenchService.require(!resumeContent.isBlank(),
                "核准简历缺少可读正文，不能起草或核准 Boss 招呼语；未点击、未发送");
        WorkbenchService.require(WorkbenchService.hash(resumeContent.getBytes(StandardCharsets.UTF_8))
                        .equals(task.at("/snapshot/resume/contentSha256").asText()),
                "核准简历正文与冻结快照不一致；未点击、未发送");
        var facts = new ArrayList<Boss.ApprovedFact>();
        for (JsonNode fact : approved.facts()) facts.add(new Boss.ApprovedFact(
                fact.path("label").asText(), fact.path("value").asText()));
        String jdSha256 = WorkbenchService.hash(jd.getBytes(StandardCharsets.UTF_8));
        String resumeContentSha256 = WorkbenchService.hash(resumeContent.getBytes(StandardCharsets.UTF_8));
        String factsSha256 = factsHash(approved.facts());
        String version = task.path("packageId").asText();
        if (version.isBlank()) version = "task:" + taskId;
        var material = object().put("materialVersion", version)
                .put("resumeSha256", approved.resumeSha256())
                .put("resumeContentSha256", resumeContentSha256)
                .put("factsSha256", factsSha256);
        String materialsSha256 = WorkbenchService.hash(
                material.toString().getBytes(StandardCharsets.UTF_8));
        return new Material(task, taskId, attemptId, canonical, platformJobId,
                task.path("company").asText(), task.path("title").asText(), jd, jdSha256,
                List.copyOf(facts), factsSha256, approved.resumeSha256(),
                resumeContentSha256, resumeContent, version, materialsSha256);
    }

    private String factsHash(JsonNode facts) {
        ArrayNode canonical = service.json.createArrayNode();
        for (JsonNode fact : facts) canonical.addObject()
                .put("id", fact.path("id").asText()).put("key", fact.path("key").asText())
                .put("label", fact.path("label").asText()).put("value", fact.path("value").asText())
                .put("version", fact.path("version").asInt(1));
        return WorkbenchService.hash(canonical.toString().getBytes(StandardCharsets.UTF_8));
    }

    private ObjectNode bindings(Material material) {
        return object().put("rulesVersion", RULES).put("taskId", material.taskId())
                .put("attemptId", material.attemptId()).put("url", material.canonicalUrl())
                .put("platformJobId", material.platformJobId())
                .put("packageId", material.task().path("packageId").asText())
                .put("materialVersion", material.materialVersion())
                .put("jdSha256", material.jdSha256())
                .put("resumeSha256", material.resumeSha256())
                .put("resumeContentSha256", material.resumeContentSha256())
                .put("factsSha256", material.factsSha256())
                .put("materialsSha256", material.materialsSha256());
    }

    private void append(ObjectNode task, String type, String message, String evidence) {
        task.withArray("events").add(object().put("id", WorkbenchService.id()).put("type", type)
                .put("message", message).put("evidence", evidence).put("source", "user")
                .put("at", WorkbenchService.now()));
        task.put("updatedAt", WorkbenchService.now());
    }

    /** Preserve the exact body: no trim, normalization or newline folding. */
    private static String exactText(ObjectNode node, String field, int max) {
        var value = node.get(field);
        if (value == null || !value.isTextual())
            throw new WorkbenchException(400, field + " 必须是完整文本正文");
        if (value.asText().length() > max)
            throw new WorkbenchException(400, field + " 超出长度限制");
        return value.asText();
    }
}
