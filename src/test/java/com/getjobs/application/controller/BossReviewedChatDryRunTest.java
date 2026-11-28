package com.getjobs.application.controller;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Existing-conversation dry run keeps its own approval and marker. It is never the first-contact
 * authorization, and validation alone must not consume the marker.
 */
class BossReviewedChatDryRunTest {
    @TempDir Path root;

    @Test void dryRunValidationReadsTheExactConversationAndDoesNotConsumeTheMarker() throws Exception {
        Path resume = root.resolve("resume.txt");
        Files.writeString(resume, "approved chat resume");
        String body = "Existing conversation with Synthetic recruiter.\n" + "x".repeat(100);
        String jobUrl = "https://www.zhipin.com/job_detail/unique-" + java.util.UUID.randomUUID() + ".html";
        String contact = "fixture-contact-" + java.util.UUID.randomUUID();
        var approval = new JSONObject()
                .put("reviewed", true)
                .put("expiresAt", Instant.now().plusSeconds(300).toString())
                .put("message", "您好，这是已有会话的核准正文。")
                .put("bodySha256", sha(body.getBytes(StandardCharsets.UTF_8)))
                .put("jobUrl", jobUrl)
                .put("contact", contact)
                .put("resumePath", resume.toString())
                .put("resumeSha256", sha(Files.readAllBytes(resume)));

        Path marker = BossController.validateChatApproval(approval, body,
                "https://www.zhipin.com/web/geek/chat");

        assertFalse(Files.exists(marker), "dry-run validation must not create or consume the send marker");
        assertFalse(Files.exists(Path.of("target", "boss-attempts").resolve(marker.getFileName())),
                "the first-contact claim must not act as this conversation's send authorization");

        var changed = new JSONObject(approval.toString()).put("bodySha256", "0".repeat(64));
        assertThrows(IllegalStateException.class, () -> BossController.validateChatApproval(changed, body,
                "https://www.zhipin.com/web/geek/chat"));
    }

    @Test void dryRunRejectsTheWrongConversationOrAnAlreadyVisibleMessage() throws Exception {
        Path resume = root.resolve("resume.txt");
        Files.writeString(resume, "approved chat resume");
        String body = "Existing conversation body";
        var approval = new JSONObject()
                .put("reviewed", true)
                .put("expiresAt", Instant.now().plusSeconds(300).toString())
                .put("message", "您好，这是已有会话的核准正文。")
                .put("bodySha256", sha(body.getBytes(StandardCharsets.UTF_8)))
                .put("jobUrl", "https://www.zhipin.com/job_detail/unique-chat.html")
                .put("contact", "fixture-contact")
                .put("resumePath", resume.toString())
                .put("resumeSha256", sha(Files.readAllBytes(resume)));

        assertThrows(IllegalStateException.class, () -> BossController.validateChatApproval(approval,
                body, "https://www.zhipin.com/web/geek/jobs"));
        assertThrows(IllegalStateException.class, () -> BossController.validateChatApproval(approval,
                body + "\n" + approval.getString("message"), "https://www.zhipin.com/web/geek/chat"));
    }

    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
