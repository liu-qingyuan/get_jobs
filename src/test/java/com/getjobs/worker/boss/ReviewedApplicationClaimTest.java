package com.getjobs.worker.boss;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** File-level ReviewedApplication contract: exact review, canonical identity and one atomic claim. */
class ReviewedApplicationClaimTest {
    static final String URL = "https://www.zhipin.com/job_detail/opaque-a.html";
    static final String COMPANY = "Synthetic Corp";
    static final String TITLE = "Python engineer";
    static final String MESSAGE = "您好，我申请这个岗位。";
    @TempDir Path root;

    Path approval(String url, String company, String title, String message, Instant expires) throws Exception {
        Path resume = root.resolve("resume.txt");
        if (!Files.exists(resume)) Files.writeString(resume, "approved resume bytes");
        var a = new JSONObject();
        a.put("reviewed", true);
        a.put("expiresAt", expires.toString());
        a.put("url", url);
        a.put("company", company);
        a.put("job", title);
        a.put("message", message);
        a.put("resumePath", resume.toString());
        a.put("resumeSha256", sha(Files.readAllBytes(resume)));
        Path file = root.resolve("approval-" + Math.abs(url.hashCode()) + ".json");
        Files.writeString(file, a.toString());
        return file;
    }

    @Test void canonicalIdentityIgnoresTrackingQueryAndOneClaimWins() throws Exception {
        Path approval = approval(URL, COMPANY, TITLE, MESSAGE, Instant.now().plusSeconds(300));
        Path attempts = root.resolve("boss-attempts");
        String canonicalWithTracking = BossCandidateReader.detailUrl(URL + "?ka=tracking&securityId=x");
        assertEquals(URL, canonicalWithTracking);
        var verified = ReviewedApplication.verify(approval, attempts, canonicalWithTracking, COMPANY, TITLE, MESSAGE);
        assertFalse(verified.claimed());
        verified.claim();
        assertTrue(verified.claimed());
        assertTrue(Files.exists(attempts.resolve(ReviewedApplication.identityKey(URL) + ".attempt")));
        assertThrows(FileAlreadyExistsException.class, verified::claim);
        var sameJob = ReviewedApplication.verify(approval, attempts, URL, COMPANY, TITLE, MESSAGE);
        assertTrue(sameJob.claimed(), "a second entry must see the same canonical claim");
    }

    @Test void exactCompanyTitleMessageAndResumeHashAreRequired() throws Exception {
        Path approval = approval(URL, COMPANY, TITLE, MESSAGE, Instant.now().plusSeconds(300));
        Path attempts = root.resolve("boss-attempts");
        assertThrows(IllegalStateException.class,
                () -> ReviewedApplication.verify(approval, attempts, URL, COMPANY, "Other title", MESSAGE));
        assertThrows(IllegalStateException.class,
                () -> ReviewedApplication.verify(approval, attempts, URL, COMPANY, TITLE, "different text"));
        assertThrows(IllegalStateException.class,
                () -> ReviewedApplication.verify(approval, attempts, URL, "Other Corp", TITLE, MESSAGE));
        Files.writeString(root.resolve("resume.txt"), "changed after review");
        assertThrows(IllegalStateException.class,
                () -> ReviewedApplication.verify(approval, attempts, URL, COMPANY, TITLE, MESSAGE));
        assertFalse(Files.exists(attempts));
    }

    @Test void absentOrExpiredApprovalCannotClaim() throws Exception {
        Path attempts = root.resolve("boss-attempts");
        assertThrows(IllegalStateException.class,
                () -> ReviewedApplication.verify(root.resolve("missing.json"), attempts, URL, COMPANY, TITLE, MESSAGE));
        Path expired = approval(URL, COMPANY, TITLE, MESSAGE, Instant.now().minusSeconds(5));
        assertThrows(IllegalStateException.class,
                () -> ReviewedApplication.verify(expired, attempts, URL, COMPANY, TITLE, MESSAGE));
        assertFalse(Files.exists(attempts));
    }

    @Test void concurrentClaimHasExactlyOneWinner() throws Exception {
        Path approval = approval(URL, COMPANY, TITLE, MESSAGE, Instant.now().plusSeconds(300));
        Path attempts = root.resolve("boss-attempts");
        int threads = 8;
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(threads);
        var winners = new java.util.concurrent.atomic.AtomicInteger();
        var pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) pool.submit(() -> {
                try {
                    start.await();
                    var verified = ReviewedApplication.verify(approval, attempts, URL, COMPANY, TITLE, MESSAGE);
                    verified.claim();
                    winners.incrementAndGet();
                } catch (FileAlreadyExistsException expected) {
                    // exactly one winner
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    done.countDown();
                }
            });
            start.countDown();
            assertTrue(done.await(10, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, winners.get());
        assertEquals(1, Files.list(attempts).filter(p -> p.getFileName().toString().endsWith(".attempt")).count());
    }

    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
