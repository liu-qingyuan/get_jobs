package com.getjobs.worker.boss;

import org.json.JSONObject;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

/** Exact, expiring, single-use authorization for one reviewed text greeting. */
final class ReviewedApplication {
    static final Path APPROVAL = Path.of("target/boss-reviewed-application.json");
    private final Path claim;
    private ReviewedApplication(Path claim) { this.claim = claim; }

    static ReviewedApplication verify(Path approval, String url, String company, String job, String message) throws Exception {
        if (!Files.isRegularFile(approval)) throw new IllegalStateException("没有已审核的单岗发送记录");
        JSONObject a = new JSONObject(Files.readString(approval));
        if (!a.optBoolean("reviewed", false) || !Instant.now().isBefore(Instant.parse(a.getString("expiresAt"))))
            throw new IllegalStateException("审核记录未确认或已过期");
        if (!url.equals(a.getString("url")) || !company.equals(a.getString("company"))
                || !job.equals(a.getString("job")) || !message.equals(a.getString("message")))
            throw new IllegalStateException("岗位或招呼语与审核记录不一致");
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(Path.of(a.getString("resumePath")))));
        if (!hash.equals(a.getString("resumeSha256"))) throw new IllegalStateException("简历已变化，需要重新核验");
        String key = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(url.getBytes(StandardCharsets.UTF_8)));
        return new ReviewedApplication(approval.toAbsolutePath().getParent().resolve("boss-attempts").resolve(key + ".attempt"));
    }

    static String reviewedTarget(Path approval) throws Exception {
        JSONObject a = new JSONObject(Files.readString(approval));
        String url = a.getString("url");
        if (!url.matches("https://www\\.zhipin\\.com/job_detail/[A-Za-z0-9_-]+\\.html"))
            throw new IllegalStateException("审核岗位链接无效");
        ReviewedApplication verified = verify(approval, url, a.getString("company"),
                a.getString("job"), a.getString("message"));
        if (Files.exists(verified.claim)) throw new IllegalStateException("该岗位已有尝试记录；须先人工核验结果");
        return url;
    }

    /** Mark before clicking: uncertain outcomes are never automatically retried. */
    void claim() throws Exception {
        Files.createDirectories(claim.getParent());
        Files.writeString(claim, Instant.now().toString(), StandardOpenOption.CREATE_NEW);
    }
}
