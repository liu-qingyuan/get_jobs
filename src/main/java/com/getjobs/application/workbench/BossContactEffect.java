package com.getjobs.application.workbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.getjobs.worker.boss.BossCandidateReader;
import com.getjobs.worker.manager.PlaywrightManager;
import com.getjobs.worker.service.BossJobService;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.WaitUntilState;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * Boss CONTACT-only effect. The platform generates the first greeting when "立即沟通" is clicked;
 * this effect claims once, clicks exactly once, records the platform's own confirmation and never
 * opens an editor, types a message or sends a CV. A later SEND_CV needs a new task/claim after a
 * real recruiter reply.
 */
final class BossContactEffect implements BossSingleJobEffect {
    private static final String START_CHAT = "a.btn-startchat, a.op-btn-chat";
    private static final String AUTO_GREETING = "已向BOSS发送消息";
    private static final String SENT = "已发送";
    private static final String ACCOUNT = "li.nav-figure, .nav-figure";

    private static final class Stop extends RuntimeException {
        Stop(String message) { super(message); }
    }

    private final PlaywrightManager browser;
    private final BossJobService legacyBoss;
    private final ObjectMapper json;

    BossContactEffect(PlaywrightManager browser, BossJobService legacyBoss, ObjectMapper json) {
        this.browser = browser;
        this.legacyBoss = legacyBoss;
        this.json = json;
    }

    @Override
    public void run(ObjectNode bundle, Function<ObjectNode, ObjectNode> emit, Path directory, BooleanSupplier stopped) throws Exception {
        var task = (ObjectNode) bundle.path("task");
        String canonical = canonical(task);
        String jobId = task.path("platformJobId").asText();
        String company = task.path("company").asText();
        String title = task.path("title").asText();
        long deadline = deadline(bundle);

        var claimed = new AtomicBoolean();
        try {
            browser.ensureReady();
            WorkbenchService.require(browser.isInitialized(), "Boss 浏览器会话未由本应用持有；不创建替代会话，不点击");

            browser.callOnPlaywright(() -> {
                try {
                    runOnOwningThread(bundle, task, canonical, jobId, company, title, deadline, stopped, emit, claimed, directory);
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new PlaywrightException("Boss 联系步骤失败：" + safe(e), e);
                }
                return null;
            });
        } catch (RuntimeException e) {
            String message = safe(e);
            if (claimed.get()) progress(emit, "已点击一次但未能核实自动招呼；保留 UNKNOWN，不重试：" + message);
            else needsInput(emit, "Boss 联系在 claim 前停止：" + message);
        }
    }

    private void runOnOwningThread(ObjectNode bundle, ObjectNode task, String canonical, String jobId,
            String company, String title, long deadline, BooleanSupplier stopped,
            Function<ObjectNode, ObjectNode> emit, AtomicBoolean claimed, Path directory) throws Exception {
        if (stopped.getAsBoolean()) throw new Stop("执行器已停止；未开始联系");
        WorkbenchService.require(!legacyBoss.isRunning(), "Boss 原任务正在运行，不能同时联系；不会停止或接管旧任务");
        Page page = browser.getBossPage();
        WorkbenchService.require(page != null && !page.isClosed(), "Boss 浏览器页面不可用；不创建替代会话");
        page.bringToFront();
        boolean preexistingConfirmation = false;
        page.navigate(canonical, new Page.NavigateOptions()
                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(15_000));
        if (BossCandidateReader.requiresUserAction(page)) throw new Stop("Boss 要求登录或安全验证；未 claim、未点击");
        WorkbenchService.require(sameDetail(page.url(), canonical), "Boss 岗位页面被重定向，不能绑定目标");
        String body = page.locator("body").innerText();
        WorkbenchService.require(body.contains(company) && body.contains(title),
                "Boss 岗位页面的公司/职位与已批准目标不符");
        String account = account(page);
        WorkbenchService.require(!account.isBlank(), "无法观察 Boss 当前账号身份；不能 claim");
        String greeting = platformGreeting(title);
        WorkbenchService.require(!body.contains(greeting), "页面已显示此岗位的既有自动招呼，不能重复联系");

        Locator confirmation = confirmation(page);
        preexistingConfirmation = visible(confirmation);
        WorkbenchService.require(!preexistingConfirmation, "页面已显示既有招呼，不能作为本次新鲜回执");

        Locator chat = firstVisibleStart(page);
        WorkbenchService.require(chat != null, "未找到可核实的首次立即沟通按钮；不点击");
        if (stopped.getAsBoolean()) throw new Stop("执行器已停止；未 claim、未点击");

        ObjectNode context = json.createObjectNode()
                .put("jdSha256", WorkbenchService.hash(task.path("description").asText().getBytes(StandardCharsets.UTF_8)))
                .put("accountHash", accountHash(account))
                .put("sessionHash", sessionHash(account, jobId))
                .put("jobId", jobId);
        var submitting = json.createObjectNode().put("type", "SUBMITTING")
                .put("message", "Boss 平台自动招呼；首击前登记 contact claim，不发送自拟正文")
                .put("action", "CONTACT");
        submitting.set("context", context);
        var ack = emit.apply(submitting);
        WorkbenchService.require(ack != null && !ack.path("outboundClaim").asText().isEmpty(),
                "contact claim 未确认；不点击");
        claimed.set(true);

        chat.click(new Locator.ClickOptions().setTimeout(5_000));
        while (System.nanoTime() < deadline) {
            if (stopped.getAsBoolean()) throw new Stop("执行器已停止；已点击一次，保留 UNKNOWN");
            if (BossCandidateReader.requiresUserAction(page))
                throw new Stop("点击后 Boss 要求登录或安全验证；保留 UNKNOWN");
            String current;
            try { current = page.locator("body").innerText(); }
            catch (PlaywrightException navigating) { page.waitForTimeout(250); continue; }
            String confirmationText = null;
            if (sameDetail(page.url(), canonical) && current.contains(company) && current.contains(title)) {
                if (!preexistingConfirmation && visible(confirmation(page))) confirmationText = AUTO_GREETING;
                else if (sentPlatformGreeting(current, title)) confirmationText = SENT + "：" + greeting;
            }
            if (confirmationText != null) {
                Path evidence = writeEvidence(directory, page, canonical, jobId, company, title, account, confirmationText);
                var receipt = json.createObjectNode().put("jobId", jobId)
                        .put("attemptId", task.path("attemptId").asText())
                        .put("contactMode", ChatDelivery.PLATFORM_AUTO_GREETING)
                        .put("platformConfirmation", confirmationText)
                        .put("proof", "Boss 同岗页面一次性点击立即沟通后出现平台确认「" + confirmationText
                                + "」；岗位 " + jobId + "、公司/职位匹配；截图 boss-contact.png；无自拟正文、无附件");
                emit.apply(json.createObjectNode().put("type", "CONTACTED")
                        .put("message", "Boss 平台自动招呼已确认；CV 留待招聘者回复后的独立阶段")
                        .put("evidence", receipt.toString()));
                return;
            }
            page.waitForTimeout(250);
        }
        throw new Stop("已点击一次但未在时间预算内看到平台自动招呼确认；保留 UNKNOWN，不重试");
        // Leave the page where the platform put it. Never close the user's browser.
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

    private static long deadline(ObjectNode bundle) {
        double seconds = bundle.path("limits").path("seconds").asDouble(60);
        if (!Double.isFinite(seconds) || seconds <= 0) seconds = 60;
        return System.nanoTime() + (long) (Math.min(seconds, 120) * 1_000_000_000L);
    }

    private static boolean sameDetail(String current, String canonical) {
        try { return BossCandidateReader.detailUrl(current).equals(canonical); }
        catch (IllegalArgumentException e) { return false; }
    }

    private static Locator confirmation(Page page) {
        return page.getByText(AUTO_GREETING, new Page.GetByTextOptions().setExact(true)).first();
    }

    private static String platformGreeting(String title) {
        return "您好，我对" + title + "很感兴趣，希望和您聊聊，谢谢！";
    }

    /** The real Boss UI opens the chat panel and reports the platform greeting as 已发送. */
    static boolean sentPlatformGreeting(String visible, String title) {
        return visible != null && title != null && !title.isBlank()
                && visible.contains(SENT) && visible.contains(platformGreeting(title));
    }

    private static boolean visible(Locator locator) {
        try { return locator.count() > 0 && locator.isVisible(); }
        catch (PlaywrightException e) { return false; }
    }

    private static String text(Locator locator) {
        try { return locator.textContent(); }
        catch (PlaywrightException e) { return ""; }
    }

    private static Locator firstVisibleStart(Page page) {
        var buttons = page.locator(START_CHAT);
        for (int i = 0; i < buttons.count(); i++) {
            var button = buttons.nth(i);
            if (visible(button) && text(button).contains("立即沟通")) return button;
        }
        return null;
    }

    private static String account(Page page) {
        try {
            var figures = page.locator(ACCOUNT);
            for (int i = 0; i < figures.count(); i++) {
                var figure = figures.nth(i);
                if (!visible(figure)) continue;
                String value = figure.innerText();
                if (value == null || value.isBlank()) value = figure.getAttribute("title");
                if (value == null || value.isBlank()) value = figure.getAttribute("aria-label");
                if (value != null && !value.isBlank()) return value.trim().replaceAll("\\s+", " ");
            }
            return "";
        } catch (PlaywrightException e) { return ""; }
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

    private Path writeEvidence(Path directory, Page page, String canonical, String jobId,
            String company, String title, String account, String confirmation) throws Exception {
        Files.createDirectories(directory);
        Path screenshot = directory.resolve("boss-contact.png");
        page.screenshot(new Page.ScreenshotOptions().setPath(screenshot));
        var evidence = json.createObjectNode().put("jobId", jobId).put("url", canonical)
                .put("company", company).put("title", title).put("accountHash", accountHash(account))
                .put("contactMode", ChatDelivery.PLATFORM_AUTO_GREETING)
                .put("platformConfirmation", confirmation).put("observedAt", Instant.now().toString())
                .put("screenshot", screenshot.getFileName().toString());
        Files.writeString(directory.resolve("boss-contact.json"), json.writeValueAsString(evidence));
        return screenshot;
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

    private static String safe(Throwable error) {
        String message = error == null ? "" : error.getMessage();
        if (message == null || message.isBlank()) return "Boss 联系步骤失败";
        String firstLine = message.lines().findFirst().orElse(message).trim();
        return firstLine.length() > 500 ? firstLine.substring(0, 500) : firstLine;
    }
}
