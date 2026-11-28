package com.getjobs.application.workbench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Chat-platform delivery contract: CONTACT and SEND_CV are distinct stages.
 *
 * <p>CONTACT can be one approved message or a platform-generated auto greeting. The latter is
 * recorded as a platform fact, never as the approved message hash. A conversation created after
 * contact can be added once for the later SEND_CV stage. SEND_CV always requires a contact
 * conversation and a fresh attachment receipt in that same conversation.</p>
 */
final class ChatDelivery {
    static final String APPROVED_MESSAGE = "APPROVED_MESSAGE";
    static final String PLATFORM_AUTO_GREETING = "PLATFORM_AUTO_GREETING";

    private final WorkbenchService service;

    ChatDelivery(WorkbenchService service) {
        this.service = service;
    }

    static String firstAction(JsonNode task) {
        return Set.of("OFFERTODAY", "BOSS").contains(task.path("channel").asText()) ? "CONTACT" : "APPLY";
    }

    boolean isChat(ObjectNode task) {
        return task.path("channel").asText().equals("OFFERTODAY")
                || task.path("channel").asText().equals("BOSS") && task.has("packageId") && service.hasPlatformEffect("BOSS");
    }

    void bindContext(ObjectNode task, JsonNode context) {
        WorkbenchService.require(context.isObject()
                && context.path("jdSha256").asText().equals(WorkbenchService.hash(task.path("description").asText().getBytes(StandardCharsets.UTF_8))),
                "观察的 JD 已变化或无法核验");
        for (String field : Set.of("accountHash", "sessionHash"))
            WorkbenchService.require(context.path(field).asText().matches("[a-f0-9]{64}"), "缺少已核实账号/会话关联，必须停止");
        if (!isChat(task)) {
            if (task.has("observedExecutionContext"))
                WorkbenchService.require(task.path("observedExecutionContext").equals(context), "JD/account/session 已变化，旧权限不能继续");
            else task.set("observedExecutionContext", context.deepCopy());
            return;
        }

        WorkbenchService.require(context.path("jobId").equals(task.path("platformJobId")), "观察的岗位缺失或错绑");
        String conversation = context.path("conversationId").asText();
        boolean bossFirstContact = task.path("channel").asText().equals("BOSS") && !task.has("contactReceipt");
        if (conversation.isBlank()) WorkbenchService.require(bossFirstContact, "观察的会话缺失或错绑");

        if (task.has("contactReceipt")) {
            String contactConversation = task.path("contactReceipt").path("conversationId").asText();
            WorkbenchService.require(!contactConversation.isBlank(), "自动招呼未提供同会话，不能进入 SEND_CV");
            WorkbenchService.require(conversation.equals(contactConversation), "附件阶段会话已变化");
        }

        if (task.has("observedExecutionContext")) {
            var prior = task.path("observedExecutionContext");
            for (String field : Set.of("jdSha256", "accountHash", "sessionHash", "jobId"))
                WorkbenchService.require(prior.path(field).equals(context.path(field)), "JD/account/session 已变化，旧权限不能继续");
            boolean conversationCreated = task.has("contactReceipt")
                    && prior.path("conversationId").asText().isBlank() && !conversation.isBlank();
            if (!conversationCreated)
                WorkbenchService.require(prior.equals(context), "JD/account/session 已变化，旧权限不能继续");
        }
        task.set("observedExecutionContext", context.deepCopy());
    }

    ObjectNode contactReceipt(ObjectNode task, String evidence) throws Exception {
        WorkbenchService.require(isChat(task) && task.path("kind").asText().equals("APPLICATION"),
                "此回执仅适用于受控聊天平台单岗申请");
        ObjectNode receipt = parse(evidence);
        baseReceipt(task, receipt);
        String mode = receipt.path("contactMode").asText(APPROVED_MESSAGE);

        if (mode.equals(PLATFORM_AUTO_GREETING)) {
            WorkbenchService.require(task.path("channel").asText().equals("BOSS"), "只有 Boss 可记录平台自动招呼");
            WorkbenchService.require(receipt.path("initialMessageSha256").asText().isBlank(),
                    "自动招呼回执不能冒充已批准精确正文");
            if (receipt.has("conversationId")) WorkbenchService.text(receipt, "conversationId", 300, true);
            if (receipt.has("messageId")) WorkbenchService.text(receipt, "messageId", 300, true);
            if (receipt.has("platformConfirmation")) WorkbenchService.text(receipt, "platformConfirmation", 2000, true);
            return receipt;
        }

        WorkbenchService.require(mode.equals(APPROVED_MESSAGE), "未知联系回执模式");
        boolean bossFirstContact = task.path("channel").asText().equals("BOSS") && !task.has("contactReceipt");
        // The exact approved text hash binds the corresponding message for a Boss first contact;
        // no prior conversation or platform message id exists yet. Existing conversations and the
        // OfferToday path still require both identifiers.
        String conversation = WorkbenchService.text(receipt, "conversationId", 300, !bossFirstContact);
        WorkbenchService.text(receipt, "messageId", 300, !bossFirstContact);
        WorkbenchService.require(WorkbenchService.text(receipt, "initialMessageSha256", 64, true)
                        .equals(approvedMessageSha256(task)),
                "联系回执必须匹配已批准的精确消息");
        if (task.has("packageId") && !conversation.isBlank())
            WorkbenchService.require(conversation.equals(task.path("observedExecutionContext").path("conversationId").asText()),
                    "回执不是本次观察/dispatch 的同一会话");
        return receipt;
    }

    ObjectNode cvReceipt(ObjectNode task, String evidence) throws Exception {
        WorkbenchService.require(isChat(task) && task.path("kind").asText().equals("APPLICATION"),
                "此回执仅适用于受控聊天平台单岗申请");
        WorkbenchService.require(task.has("contactReceipt"), "简历送达必须在已登记的同岗联系之后核实");
        ObjectNode contact = (ObjectNode) task.path("contactReceipt");
        String contactConversation = WorkbenchService.text(contact, "conversationId", 300, true);
        ObjectNode receipt = parse(evidence);
        baseReceipt(task, receipt);
        String conversation = WorkbenchService.text(receipt, "conversationId", 300, true);
        String messageId = WorkbenchService.text(receipt, "messageId", 300, true);
        WorkbenchService.require(conversation.equals(contactConversation), "简历送达回执必须属于本次联系的同一会话");
        WorkbenchService.require(!messageId.equals(contact.path("messageId").asText()),
                "OfferToday 文字与简历分开发送，不能复用文字消息编号冒充附件回执");
        WorkbenchService.require(WorkbenchService.text(receipt, "resumeSha256", 64, true)
                        .equals(task.path("snapshot").path("resume").path("sha256").asText()),
                "简历送达回执必须匹配冻结附件哈希");
        if (task.has("packageId"))
            WorkbenchService.require(conversation.equals(task.path("observedExecutionContext").path("conversationId").asText()),
                    "回执不是本次观察/dispatch 的同一会话");
        return receipt;
    }

    /**
     * Boss sends the operator's separately approved body, not the generic preparation placeholder.
     * The hash must come from that immutable approval and still match its exact UTF-8 message.
     */
    private static String approvedMessageSha256(ObjectNode task) {
        if (task.path("channel").asText().equals("BOSS") && task.path("bossMessageApproval").isObject()) {
            var approval = task.path("bossMessageApproval");
            String message = approval.path("message").asText();
            String expected = approval.path("messageSha256").asText();
            WorkbenchService.require(!message.isBlank()
                            && expected.equals(WorkbenchService.hash(message.getBytes(StandardCharsets.UTF_8))),
                    "Boss 核准正文已变化，不能登记联系回执");
            return expected;
        }
        // Legacy/fake platform adapters that never had a text approval still bind the frozen snapshot.
        // The production Boss effect refuses to click without bossMessageApproval before this receipt.
        return task.path("snapshot").path("initialMessageSha256").asText();
    }

    private ObjectNode parse(String evidence) throws Exception {
        try {
            var parsed = service.json.readTree(evidence);
            if (!(parsed instanceof ObjectNode object)) throw new IllegalArgumentException();
            return object.deepCopy();
        } catch (Exception e) {
            throw new WorkbenchException(400, "聊天回执须为含岗位、尝试、会话/消息和证明的 JSON");
        }
    }

    private void baseReceipt(ObjectNode task, ObjectNode receipt) {
        WorkbenchService.require(WorkbenchService.text(receipt, "jobId", 150, true)
                        .equals(task.path("platformJobId").asText()), "回执岗位与批准岗位不符");
        String attempt = task.path("attemptId").asText();
        WorkbenchService.require(!attempt.isEmpty() && WorkbenchService.text(receipt, "attemptId", 100, true).equals(attempt),
                "回执不属于本次执行");
        WorkbenchService.require(attempt.equals(task.path("claimedAttemptId").asText()),
                "本次执行尚未成功登记提交前占位；进程退出导致的 UNKNOWN 不能代替占位");
        WorkbenchService.text(receipt, "proof", 3000, true);
    }
}
