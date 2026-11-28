package com.getjobs.application.workbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.getjobs.worker.boss.Boss;
import com.getjobs.worker.boss.BossCandidateReader;
import com.getjobs.worker.manager.PlaywrightManager;
import com.getjobs.worker.service.BossJobService;
import com.microsoft.playwright.Page;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * Thin workbench adapter for Boss first contact. It supplies the frozen material and the
 * workbench's budget/stage authorization, then delegates the page operation and the only click
 * fence (ReviewedApplication claim) to BossJobService/Boss.firstContact. It never clicks itself.
 */
final class BossContactEffect implements BossSingleJobEffect {
    private static final String ACCOUNT = "li.nav-figure, .nav-figure";

    private final PlaywrightManager browser;
    private final BossJobService legacyBoss;
    private final ObjectMapper json;
    private final Path attemptsDirectory;

    BossContactEffect(PlaywrightManager browser, BossJobService legacyBoss, ObjectMapper json) {
        this(browser, legacyBoss, json, Path.of("target", "boss-attempts"));
    }

    /** Tests pass a temporary attempts root shared with their legacy fixture; production uses target/. */
    BossContactEffect(PlaywrightManager browser, BossJobService legacyBoss, ObjectMapper json, Path attemptsDirectory) {
        this.browser = browser;
        this.legacyBoss = legacyBoss;
        this.json = json;
        this.attemptsDirectory = attemptsDirectory;
    }

    @Override
    public void run(ObjectNode bundle, Function<ObjectNode, ObjectNode> emit, Path directory,
            BooleanSupplier stopped) throws Exception {
        if (stopped.getAsBoolean()) {
            progress(emit, "执行器已停止；未开始 Boss 首击");
            return;
        }
        var task = (ObjectNode) bundle.path("task");
        String canonical = canonical(task);
        String jobId = task.path("platformJobId").asText();
        String company = task.path("company").asText();
        String title = task.path("title").asText();
        String description = task.path("description").asText();
        String message = task.at("/snapshot/initialMessage").asText();
        String initialMessageSha256 = task.at("/snapshot/initialMessageSha256").asText();
        String resumePath = bundle.path("resumePath").asText();
        String resumeSha256 = task.at("/snapshot/resume/sha256").asText();

        if (stopped.getAsBoolean()) {
            needsInput(emit, "执行器已停止；未进入 claim、未点击");
            return;
        }
        WorkbenchService.require(!description.isBlank(), "Boss 缺少核准 JD；不点击");
        WorkbenchService.require(!message.isBlank(), "Boss 缺少核准正文；不点击");
        WorkbenchService.require(initialMessageSha256.equals(
                        WorkbenchService.hash(message.getBytes(StandardCharsets.UTF_8))),
                "Boss 核准正文已变化；不点击");
        Path resume = Path.of(resumePath);
        WorkbenchService.require(Files.isRegularFile(resume)
                        && WorkbenchService.hash(Files.readAllBytes(resume)).equals(resumeSha256),
                "Boss 核准简历已变化；不点击");

        Files.createDirectories(directory);
        Path approval = directory.resolve("boss-first-contact-approval.json");
        long seconds = bundle.path("limits").path("seconds").asLong(60);
        var approvalJson = json.createObjectNode()
                .put("reviewed", true)
                .put("expiresAt", Instant.now().plusSeconds(Math.max(60, Math.min(seconds, 600))).toString())
                .put("url", canonical)
                .put("company", company)
                .put("job", title)
                .put("message", message)
                .put("resumePath", resume.toAbsolutePath().normalize().toString())
                .put("resumeSha256", resumeSha256);
        Files.writeString(approval, json.writeValueAsString(approvalJson));
        try { Files.setPosixFilePermissions(approval, PosixFilePermissions.fromString("rw-------")); }
        catch (UnsupportedOperationException ignored) { }

        // Candidate descriptions may prepend observed conditions; the live detail page exposes the
        // description itself. Both are exact candidates; the shared path never fuzzy-matches JD text.
        var approvedDescriptions = new ArrayList<String>();
        approvedDescriptions.add(description);
        int newline = description.indexOf('\n');
        if (newline >= 0) {
            String tail = description.substring(newline + 1).strip();
            if (!tail.isBlank() && !tail.equals(description)) approvedDescriptions.add(tail);
        }
        var request = new Boss.FirstContactRequest(approval, attemptsDirectory, canonical,
                task.path("url").asText(), company, title, message, List.copyOf(approvedDescriptions),
                Math.min(Math.max(seconds, 1), 120) * 1000L);
        var outcome = legacyBoss.firstContact(request, page -> {
            WorkbenchService.require(!stopped.getAsBoolean(), "执行器已停止；不进入 claim、不点击");
            // Budget/stage authorization only. The click fence is the ReviewedApplication claim,
            // which Boss.firstContact persists after this gate returns.
            String account = account(page);
            WorkbenchService.require(!account.isBlank(), "无法观察 Boss 当前账号身份；不进入 claim、不点击");
            var context = json.createObjectNode()
                    .put("jdSha256", WorkbenchService.hash(description.getBytes(StandardCharsets.UTF_8)))
                    .put("accountHash", accountHash(account))
                    .put("sessionHash", sessionHash(account, jobId))
                    .put("jobId", jobId);
            var submitting = json.createObjectNode().put("type", "SUBMITTING")
                    .put("message", "Boss 首击前登记预算/阶段授权；点击 fence 由 ReviewedApplication 取得")
                    .put("action", "CONTACT");
            submitting.set("context", context);
            var ack = emit.apply(submitting);
            WorkbenchService.require(ack != null && !ack.path("outboundClaim").asText().isEmpty(),
                    "Boss 阶段授权未确认；不进入 ReviewedApplication claim、不点击");
        });

        if (outcome.confirmed()) {
            var receipt = json.createObjectNode().put("jobId", jobId)
                    .put("attemptId", task.path("attemptId").asText())
                    .put("contactMode", outcome.contactMode())
                    .put("platformConfirmation", outcome.evidence());
            if (Boss.CONTACT_MODE_APPROVED_MESSAGE.equals(outcome.contactMode()))
                receipt.put("initialMessageSha256", initialMessageSha256);
            receipt.put("proof", "Boss 同岗页面点击一次立即沟通后的新鲜可见回执；ReviewedApplication claim 先于点击；"
                    + "岗位 " + jobId + "，公司/职位匹配；无附件、无 CV");
            emit.apply(json.createObjectNode().put("type", "CONTACTED")
                    .put("message", "Boss 首次联系已确认；CV 未尝试")
                    .put("evidence", receipt.toString()));
            return;
        }
        if ("duplicate".equals(outcome.attemptDisposition())) {
            progress(emit, "同岗已有 ReviewedApplication 尝试记录；不重投、不点击：" + outcome.reason());
            return;
        }
        if ("claimed".equals(outcome.attemptDisposition())) {
            progress(emit, "Boss 已 claim/点击但未取得可绑定的新鲜回执；保留 UNKNOWN，不重投：" + outcome.reason());
            return;
        }
        progress(emit, "Boss 首击在 claim 前停止：" + outcome.reason());
        needsInput(emit, "Boss 首击在 claim 前停止：" + outcome.reason());
    }

    private static String canonical(ObjectNode task) {
        WorkbenchService.require(task.path("channel").asText().equals("BOSS")
                        && task.path("kind").asText().equals("APPLICATION") && task.has("packageId"),
                "Boss 单岗联系仅处理受控 APPLICATION 任务");
        try {
            String canonical = BossCandidateReader.detailUrl(task.path("url").asText());
            WorkbenchService.require(task.path("platformJobId").asText().equals(BossCandidateReader.jobId(canonical)),
                    "Boss 岗位身份与已批准目标不一致");
            WorkbenchService.require(!task.path("company").asText().isBlank()
                    && !task.path("title").asText().isBlank(), "Boss 目标缺少公司或职位");
            return canonical;
        } catch (IllegalArgumentException e) {
            throw new WorkbenchException(409, "Boss 岗位链接不规范；不联系");
        }
    }

    private static String account(Page page) {
        try {
            var figures = page.locator(ACCOUNT);
            for (int i = 0; i < figures.count(); i++) {
                var figure = figures.nth(i);
                if (!figure.isVisible()) continue;
                String value = figure.innerText();
                if (value == null || value.isBlank()) value = figure.getAttribute("title");
                if (value == null || value.isBlank()) value = figure.getAttribute("aria-label");
                if (value != null && !value.isBlank()) return value.trim().replaceAll("\\s+", " ");
            }
            return "";
        } catch (RuntimeException e) {
            return "";
        }
    }

    private String accountHash(String account) {
        return WorkbenchService.hash(("boss:" + account).getBytes(StandardCharsets.UTF_8));
    }

    private String sessionHash(String account, String jobId) {
        // Same account + exact job + this app-owned profile is the observed binding. No cookie,
        // token or credential is read, stored or exported.
        String profile = browser.profileDirectory().toAbsolutePath().normalize().toString();
        return WorkbenchService.hash((account + "|" + jobId + "|" + profile).getBytes(StandardCharsets.UTF_8));
    }

    private void needsInput(Function<ObjectNode, ObjectNode> emit, String message) {
        try {
            emit.apply(json.createObjectNode().put("type", "NEEDS_INPUT").put("message", message).put("evidence", ""));
        } catch (RuntimeException ignored) { }
    }

    private void progress(Function<ObjectNode, ObjectNode> emit, String message) {
        try {
            emit.apply(json.createObjectNode().put("type", "PROGRESS").put("message", message).put("evidence", ""));
        } catch (RuntimeException ignored) { }
    }

    /** Kept for the existing DOM-shape regression; the shared observation uses Boss.sentPlatformGreeting. */
    static boolean sentPlatformGreeting(String visible, String title) {
        return Boss.sentPlatformGreeting(visible, title);
    }
}
