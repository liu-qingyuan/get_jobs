package com.getjobs.application.workbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ChatDeliveryTest {
    @TempDir Path root;
    final ObjectMapper json = new ObjectMapper();
    static final String JD = "Design and build AI application systems with Python and React.";
    static final String ACCOUNT = "a".repeat(64);
    static final String SESSION = "b".repeat(64);
    static final String RESUME = "c".repeat(64);
    static final String INITIAL = "Hello, I would like to discuss this AI Engineer role.";

    WorkbenchService service() {
        return new WorkbenchService(root.resolve("state"), root.resolve("legacy"), json, null,
                Map.of("BOSS", List.of("boss-effect")));
    }

    ObjectNode task() {
        var task = json.createObjectNode().put("channel", "BOSS").put("kind", "APPLICATION")
                .put("packageId", "pkg").put("attemptId", "attempt").put("claimedAttemptId", "attempt")
                .put("platformJobId", "opaque-job").put("description", JD).put("state", "UNKNOWN");
        var snapshot = json.createObjectNode().put("initialMessageSha256", WorkbenchService.hash(INITIAL.getBytes(StandardCharsets.UTF_8)));
        snapshot.set("resume", json.createObjectNode().put("sha256", RESUME));
        task.set("snapshot", snapshot);
        task.set("bossMessageApproval", json.createObjectNode().put("message", INITIAL)
                .put("messageSha256", WorkbenchService.hash(INITIAL.getBytes(StandardCharsets.UTF_8))));
        return task;
    }

    ObjectNode context(String conversation) {
        var context = json.createObjectNode()
                .put("jdSha256", WorkbenchService.hash(JD.getBytes(StandardCharsets.UTF_8)))
                .put("accountHash", ACCOUNT).put("sessionHash", SESSION).put("jobId", "opaque-job");
        if (!conversation.isBlank()) context.put("conversationId", conversation);
        return context;
    }

    ObjectNode autoGreeting(String conversation) {
        var receipt = json.createObjectNode().put("jobId", "opaque-job").put("attemptId", "attempt")
                .put("proof", "platform confirmation visible").put("contactMode", ChatDelivery.PLATFORM_AUTO_GREETING)
                .put("platformConfirmation", "已向BOSS发送消息");
        if (!conversation.isBlank()) receipt.put("conversationId", conversation);
        return receipt;
    }

    @Test void platformAutoGreetingIsAcceptedButCannotStartCvWithoutConversation() throws Exception {
        var service = service();
        var task = task();
        var chat = service.chat;
        chat.bindContext(task, context(""));
        task.set("contactReceipt", chat.contactReceipt(task, autoGreeting("").toString()));

        var error = assertThrows(WorkbenchException.class, () -> chat.bindContext(task, context("")));
        assertTrue(error.getMessage().contains("会话"), error.getMessage());
    }

    @Test void autoGreetingConversationCanBeAddedOnceForSeparateCvReceipt() throws Exception {
        var service = service();
        var task = task();
        var chat = service.chat;
        chat.bindContext(task, context(""));
        task.set("contactReceipt", chat.contactReceipt(task, autoGreeting("conversation-1").toString()));
        chat.bindContext(task, context("conversation-1"));

        var cv = json.createObjectNode().put("jobId", "opaque-job").put("attemptId", "attempt")
                .put("conversationId", "conversation-1").put("messageId", "message-cv")
                .put("resumeSha256", RESUME).put("proof", "attachment message visible");
        var accepted = chat.cvReceipt(task, cv.toString());
        assertEquals("message-cv", accepted.path("messageId").asText());
        assertEquals(RESUME, accepted.path("resumeSha256").asText());
    }

    @Test void approvedMessageStillRequiresExactHashAndSameConversation() throws Exception {
        var service = service();
        var task = task();
        var chat = service.chat;
        chat.bindContext(task, context("conversation-1"));

        var mismatch = json.createObjectNode().put("jobId", "opaque-job").put("attemptId", "attempt")
                .put("conversationId", "conversation-1").put("messageId", "message-1")
                .put("initialMessageSha256", "d".repeat(64)).put("proof", "visible");
        assertThrows(WorkbenchException.class, () -> chat.contactReceipt(task, mismatch.toString()));

        var contact = json.createObjectNode().put("jobId", "opaque-job").put("attemptId", "attempt")
                .put("conversationId", "conversation-1").put("messageId", "message-1")
                .put("initialMessageSha256", WorkbenchService.hash(INITIAL.getBytes(StandardCharsets.UTF_8))).put("proof", "visible");
        task.set("contactReceipt", chat.contactReceipt(task, contact.toString()));

        var cv = json.createObjectNode().put("jobId", "opaque-job").put("attemptId", "attempt")
                .put("conversationId", "conversation-1").put("messageId", "message-1")
                .put("resumeSha256", RESUME).put("proof", "attachment visible");
        assertThrows(WorkbenchException.class, () -> chat.cvReceipt(task, cv.toString()));
    }
}
