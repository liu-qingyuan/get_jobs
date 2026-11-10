package com.getjobs.application.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.getjobs.application.service.CookieService;
import com.getjobs.worker.dto.JobProgressMessage;
import com.getjobs.worker.manager.PlaywrightManager;
import com.getjobs.worker.service.BossJobService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.catalina.connector.ClientAbortException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Boss 平台控制器（单平台合并版）：进度 SSE 与任务接口
 */
@Slf4j
@RestController
@RequestMapping("/api/boss")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class BossController {
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final BossJobService bossJobService;
    private final PlaywrightManager playwrightManager;
    private final CookieService cookieService;

    private final List<SseEmitter> bossProgressEmitters = new CopyOnWriteArrayList<>();

    /** SSE - Boss投递任务进度推送 */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamBossProgress() {
        SseEmitter emitter = new SseEmitter(0L); // 永不超时
        bossProgressEmitters.add(emitter);

        emitter.onCompletion(() -> {
            log.info("Boss进度SSE连接已完成");
            bossProgressEmitters.remove(emitter);
        });
        emitter.onTimeout(() -> {
            log.info("Boss进度SSE连接超时");
            bossProgressEmitters.remove(emitter);
        });
        emitter.onError(e -> {
            log.error("Boss进度SSE连接错误", e);
            bossProgressEmitters.remove(emitter);
        });

        try {
            emitter.send(SseEmitter.event().name("connected").data(Map.of("message", "已连接到Boss投递进度推送")));
        } catch (IOException e) {
            log.error("发送SSE连接消息失败", e);
        }
        return emitter;
    }

    /** Open the existing managed BOSS page for user login; never submits credentials. */
    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> openBossLogin() {
        if (bossJobService.isRunning()) {
            return ResponseEntity.status(409).body(Map.of("success", false,
                    "message", "请先停止扫描再打开登录页"));
        }
        playwrightManager.ensureReady();
        playwrightManager.runOnPlaywright(() -> {
            var page = playwrightManager.getBossPage();
            page.navigate("https://www.zhipin.com/web/user/?ka=header-login",
                    new com.microsoft.playwright.Page.NavigateOptions()
                            .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED)
                            .setTimeout(30000));
            page.bringToFront();
        });
        return ResponseEntity.ok(Map.of("success", true, "message", "已打开项目BOSS登录页，请在浏览器核验登录"));
    }

    /** Inspect the existing managed page when idle; no cookie or credential export. */
    @GetMapping("/inspection")
    public ResponseEntity<Map<String, Object>> inspectBossPage() {
        if (bossJobService.isRunning()) {
            return ResponseEntity.status(409).body(Map.of("message", "请先停止任务再检查页面"));
        }
        return ResponseEntity.ok(playwrightManager.callOnPlaywright(() -> {
            var page = playwrightManager.getBossPage();
            var image = java.nio.file.Path.of("target", "boss-inspection.png").toAbsolutePath();
            page.screenshot(new com.microsoft.playwright.Page.ScreenshotOptions().setPath(image));
            Map<String, Object> result = new HashMap<>();
            result.put("url", page.url());
            result.put("title", page.title());
            result.put("visibleText", page.locator("body").innerText());
            result.put("screenshot", image.toString());
            return result;
        }));
    }

    /** Read the chat inbox in the existing persistent session; never send messages/files. */
    @PostMapping("/chat-inspection")
    public ResponseEntity<Map<String, Object>> inspectBossChats(
            @RequestParam(defaultValue = "") String contact,
            @RequestParam(defaultValue = "") String company) {
        // New names require a company; selectChatContact verifies a unique existing inbox entry.
        if (!contact.isBlank() && company.isBlank() && !java.util.Set.of("彭先生", "吴女士", "贾淑云", "尹女士", "魏昊明",
                "王先生", "仝瑶", "杨先生", "朱雪娇", "马女士", "李先生", "徐女士", "姜先生",
                "唐女士", "关女士", "魏女士", "孙艳楠", "雷先生", "肖先生", "贾女士", "胡女士", "王女士", "张琦", "王静").contains(contact)) {
            return ResponseEntity.badRequest().body(Map.of("message", "联系人不在已核验列表中"));
        }
        if (bossJobService.isRunning()) {
            return ResponseEntity.status(409).body(Map.of("message", "请先停止任务再检查消息"));
        }
        return ResponseEntity.ok(playwrightManager.callOnPlaywright(() -> {
            var page = playwrightManager.getBossPage();
            page.navigate("https://www.zhipin.com/web/geek/chat",
                    new com.microsoft.playwright.Page.NavigateOptions()
                            .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED)
                            .setTimeout(30000));
            page.waitForTimeout(1500);
            if (!contact.isBlank()) {
                selectChatContact(page, contact, company);
                page.waitForTimeout(1000);
            }
            var image = java.nio.file.Path.of("target", "boss-chat-" +
                    (contact.isBlank() ? "inspection" : contact) + ".png").toAbsolutePath();
            page.screenshot(new com.microsoft.playwright.Page.ScreenshotOptions().setPath(image));
            Map<String, Object> result = new HashMap<>();
            result.put("url", page.url());
            result.put("visibleText", page.locator("body").innerText());
            result.put("screenshot", image.toString());
            if (!contact.isBlank()) {
                result.put("chatControls", page.locator("[contenteditable], textarea, button.btn-send, div.send-message").evaluateAll("els => els.map(el => ({html:el.outerHTML.slice(0,2000),visible:!!(el.offsetWidth||el.offsetHeight||el.getClientRects().length)}))"));
                result.put("openPages", page.context().pages().stream().map(p -> p.url().split("\\?", 2)[0]).toList());
                result.put("resumeControl", page.getByText("发简历",
                        new com.microsoft.playwright.Page.GetByTextOptions().setExact(true))
                        .evaluate("el => ({html: el.outerHTML, parent: el.parentElement.outerHTML.slice(0, 4000)})"));
            }
            return result;
        }));
    }

    static void selectChatContact(com.microsoft.playwright.Page page, String contact, String company) {
        var names = page.getByText(contact, new com.microsoft.playwright.Page.GetByTextOptions().setExact(true));
        names.first().waitFor(new com.microsoft.playwright.Locator.WaitForOptions().setTimeout(5000));
        int selected = -1;
        for (int i = 0; i < names.count(); i++) {
            boolean matches = company.isBlank() || Boolean.TRUE.equals(names.nth(i).evaluate("""
                (el, company) => {
                    for (let p = el.parentElement; p && p.tagName !== 'BODY'; p = p.parentElement) {
                        // Stop before a shared list: never borrow another recipient's company.
                        if (p.querySelectorAll('.name-text').length > 1) return false;
                        if (p.innerText.includes(company)) return true;
                    }
                    return false;
                }
                """, company));
            if (matches) {
                if (selected != -1) throw new IllegalStateException("姓名和公司仍不唯一，停止选择");
                selected = i;
            }
        }
        if (selected == -1) throw new IllegalStateException("没有姓名和公司同时匹配的联系人");
        names.nth(selected).click(new com.microsoft.playwright.Locator.ClickOptions().setTimeout(5000));
    }

    /** Continue an inspected existing conversation; never clicks a new-contact button. */
    @PostMapping("/reviewed-chat-send")
    public ResponseEntity<Map<String, Object>> sendReviewedChat(
            @RequestParam(defaultValue = "true") boolean dryRun,
            jakarta.servlet.http.HttpServletRequest request) {
        if (!java.util.Set.of("127.0.0.1", "0:0:0:0:0:0:0:1", "::1").contains(request.getRemoteAddr()))
            return ResponseEntity.status(403).body(Map.of("status", "local-only"));
        if (bossJobService.isRunning())
            return ResponseEntity.status(409).body(Map.of("status", "stop-task-first"));
        try {
            return ResponseEntity.ok(playwrightManager.callOnPlaywright(() -> {
                var page = playwrightManager.getBossPage();
                var failure = new java.util.concurrent.atomic.AtomicReference<String>();
                java.util.function.Consumer<com.microsoft.playwright.Response> responseGuard = response -> {
                    try {
                        String host = java.net.URI.create(response.url()).getHost();
                        if (host == null || !(host.equals("zhipin.com") || host.endsWith(".zhipin.com"))) return;
                        if (response.status() == 401 || response.status() == 403 || response.status() == 429)
                            failure.compareAndSet(null, "HTTP " + response.status());
                        else if (response.url().contains("/wapi/") && response.url().contains(".json")) {
                            var data = new org.json.JSONObject(response.text());
                            if (data.has("code") && data.optInt("code", -1) != 0)
                                failure.compareAndSet(null, "平台接口异常：code=" + data.optInt("code", -1));
                        }
                    } catch (RuntimeException e) {
                        failure.compareAndSet(null, "平台响应解析异常");
                    }
                };
                page.onResponse(responseGuard);
                try {
                    checkChatAccess(page, failure.get());
                    var approvalPath = java.nio.file.Path.of("target/boss-reviewed-chat.json");
                    var approval = new org.json.JSONObject(java.nio.file.Files.readString(approvalPath));
                    String body = page.locator("body").innerText();
                    var marker = validateChatApproval(approval, body, page.url());
                    var input = page.locator("div#chat-input.chat-input[contenteditable='true'], textarea.input-area");
                    var send = page.locator("button[type='send'].btn-send, div.send-message");
                    if (input.count() != 1 || !input.isVisible() || send.count() != 1)
                        throw new IllegalStateException("聊天控件不唯一或不可见");
                    checkChatAccess(page, failure.get());
                    if (failure.get() != null) throw new IllegalStateException(failure.get());
                    if (dryRun) return Map.<String, Object>of("status", "ready", "sent", false);
                    // A fresh body hash binds the exact selected conversation reviewed by the operator.
                    validateChatApproval(approval, page.locator("body").innerText(), page.url());
                    checkChatAccess(page, failure.get());
                    if (failure.get() != null) throw new IllegalStateException(failure.get());
                    claimChatSend(marker);
                    input.fill(approval.getString("message"));
                    checkChatAccess(page, failure.get());
                    if (failure.get() != null) throw new IllegalStateException(failure.get());
                    send.click();
                    page.waitForTimeout(1500);
                    var evidence = java.nio.file.Path.of("target/boss-attempts", marker.getFileName()+".png");
                    page.screenshot(new com.microsoft.playwright.Page.ScreenshotOptions().setPath(evidence));
                    String visible = page.locator("body").innerText();
                    java.nio.file.Files.writeString(java.nio.file.Path.of(evidence+".txt"),
                            "Time: " + java.time.Instant.now()+"\n"+visible);
                    return Map.<String,Object>of("status", "attempted-needs-platform-verification",
                            "visibleText", visible, "screenshot", evidence.toAbsolutePath().toString());
                } finally { page.offResponse(responseGuard); }
            }));
        } catch (Exception e) {
            // Do not expose page content or credential-bearing exceptions through errors.
            String reason = String.valueOf(e.getMessage());
            boolean accessFailure = reason.matches("HTTP (401|403|429)|平台接口异常：code=-?[0-9]+")
                    || reason.equals("平台响应解析异常") || reason.equals("访问受限、验证或登录异常；停止发送");
            return ResponseEntity.status(409).body(Map.of("status", "not-sent-or-uncertain-check-evidence",
                    "message", accessFailure ? reason + "；勿自动重试"
                            : "审核、页面绑定或发送检查未通过；勿自动重试"));
        }
    }

    static String chatHash(byte[] bytes) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void checkChatAccess(com.microsoft.playwright.Page page, String failure) {
        if (failure != null) throw new IllegalStateException(failure);
        String body = page.locator("body").innerText(new com.microsoft.playwright.Locator.InnerTextOptions().setTimeout(2000));
        var challenge = page.locator(".geetest_panel, .geetest_holder, .captcha-container, iframe[src*='captcha'], iframe[src*='verify']");
        if ((challenge.count() > 0 && challenge.first().isVisible())
                || !"https://www.zhipin.com/web/geek/chat".equals(page.url())
                || body.contains("访问受限") || body.contains("限制访问") || body.contains("异常行为")
                || body.contains("请勿频繁") || body.contains("验证码") || body.contains("安全验证")
                || body.contains("安全校验") || body.contains("请先登录") || body.contains("登录已失效"))
            throw new IllegalStateException("访问受限、验证或登录异常；停止发送");
    }

    static java.nio.file.Path validateChatApproval(org.json.JSONObject a, String body, String url) throws Exception {
        if (!url.equals("https://www.zhipin.com/web/geek/chat"))
            throw new IllegalStateException("不是已有聊天页");
        if (!a.optBoolean("reviewed") || !java.time.Instant.now().isBefore(java.time.Instant.parse(a.getString("expiresAt"))))
            throw new IllegalStateException("审核过期");
        if (body.contains("访问受限") || body.contains("限制访问") || body.contains("验证码")
                || body.contains("安全验证") || body.contains("请先登录"))
            throw new IllegalStateException("页面受限或登录失效");
        String message = a.getString("message");
        if (message.isBlank() || message.length() > 1500 || body.contains(message))
            throw new IllegalStateException("正文为空、过长或已出现");
        if (!chatHash(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)).equals(a.getString("bodySha256")))
            throw new IllegalStateException("当前会话与核验快照不一致");
        if (!chatHash(java.nio.file.Files.readAllBytes(java.nio.file.Path.of(a.getString("resumePath")))).equals(a.getString("resumeSha256")))
            throw new IllegalStateException("简历变化");
        String key = chatHash((a.getString("jobUrl")+"|"+a.getString("contact")).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var marker = java.nio.file.Path.of("target/boss-attempts", key+".custom-attempt");
        if (java.nio.file.Files.exists(marker)) throw new IllegalStateException("已有正文发送尝试");
        return marker;
    }

    static void claimChatSend(java.nio.file.Path marker) throws Exception {
        java.nio.file.Files.createDirectories(marker.toAbsolutePath().getParent());
        java.nio.file.Files.writeString(marker, java.time.Instant.now().toString(), java.nio.file.StandardOpenOption.CREATE_NEW);
    }


    /** POST - 启动Boss投递任务 */
    @PostMapping("/execute")
    public ResponseEntity<Map<String, Object>> executeBoss() {
        if (bossJobService.isRunning()) {
            return ResponseEntity.ok(Map.of(
                    "status", "already_running",
                    "message", "Boss投递任务已在运行中"
            ));
        }

        CompletableFuture.runAsync(() -> bossJobService.executeDelivery(this::sendBossProgress));

        return ResponseEntity.ok(Map.of(
                "status", "started",
                "message", "Boss投递任务已启动"
        ));
    }

    /** POST - 启动Boss投递任务（前端使用的接口）*/
    @PostMapping("/start")
    public ResponseEntity<Map<String, Object>> startBoss() {
        Map<String, Object> response = new HashMap<>();
        try {
            if (!playwrightManager.isLoggedIn("boss")) {
                response.put("success", false);
                response.put("message", "请先登录Boss直聘");
                response.put("status", "not_logged_in");
                return ResponseEntity.badRequest().body(response);
            }
            if (bossJobService.isRunning()) {
                long seconds = bossJobService.runningForMillis() / 1000;
                response.put("success", false);
                response.put("message", String.format(
                        "Boss任务已在运行中（已运行 %d 分 %d 秒）。如果长时间没有进展，先点\"停止投递\"再重新开始",
                        seconds / 60, seconds % 60));
                response.put("status", "running");
                response.put("runningSeconds", seconds);
                return ResponseEntity.badRequest().body(response);
            }
            CompletableFuture.runAsync(() -> bossJobService.executeDelivery(pm -> {
                sendBossProgress(pm);
                log.info("[{}] {}", pm.getPlatform(), pm.getMessage());
            }));
            response.put("success", true);
            response.put("message", "Boss任务启动成功");
            response.put("status", "started");
            log.info("通过API启动Boss任务成功");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("启动Boss任务失败", e);
            response.put("success", false);
            response.put("message", "启动Boss任务失败: " + e.getMessage());
            response.put("error", e.getClass().getSimpleName());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /** POST - 停止Boss投递任务 */
    @PostMapping("/stop")
    public ResponseEntity<Map<String, Object>> stopBoss() {
        Map<String, Object> response = new HashMap<>();
        try {
            if (!bossJobService.isRunning()) {
                response.put("success", false);
                response.put("message", "没有正在运行的Boss任务");
                return ResponseEntity.badRequest().body(response);
            }
            bossJobService.stopDelivery();
            response.put("success", true);
            response.put("message", "Boss任务停止请求已发送");
            log.info("通过API停止Boss任务");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("停止Boss任务失败", e);
            response.put("success", false);
            response.put("message", "停止Boss任务失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /** POST - 退出Boss登录 */
    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logoutBoss() {
        Map<String, Object> response = new HashMap<>();
        try {
            playwrightManager.setLoginStatus("boss", false);
            cookieService.clearCookieByPlatform("boss", "manual logout");
            try { 
                playwrightManager.clearBossCookies(); 
            } catch (Exception e) { 
                log.warn("清理Boss上下文Cookie异常: {}", e.getMessage()); 
            }
            response.put("success", true);
            response.put("message", "Boss已退出登录，数据库Cookie和上下文Cookie均已清理");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("退出登录失败", e);
            response.put("success", false);
            response.put("message", "退出登录失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /** GET - 获取Boss任务状态 */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getBossStatus() {
        return ResponseEntity.ok(bossJobService.getStatus());
    }

    private void sendBossProgress(JobProgressMessage message) {
        List<SseEmitter> deadEmitters = new CopyOnWriteArrayList<>();
        for (SseEmitter emitter : bossProgressEmitters) {
            try {
                emitter.send(SseEmitter.event().name("progress").data(objectMapper.writeValueAsString(message)));
            } catch (Exception e) {
                if (e instanceof AsyncRequestNotUsableException ||
                        e instanceof ClientAbortException ||
                        (e.getCause() instanceof ClientAbortException) ||
                        (e instanceof IOException && String.valueOf(e.getMessage()).contains("中止了一个已建立的连接"))) {
                    log.debug("Boss进度 SSE 客户端已断开，移除连接: {}", e.getMessage());
                    try { emitter.complete(); } catch (Exception ignored) {}
                } else {
                    log.error("发送Boss进度消息失败", e);
                }
                deadEmitters.add(emitter);
            }
        }
        bossProgressEmitters.removeAll(deadEmitters);
    }

    /** 心跳 - Boss进度 SSE */
    @Scheduled(fixedRate = 30000)
    public void heartbeatBossProgress() {
        if (bossProgressEmitters.isEmpty()) return;
        List<SseEmitter> deadEmitters = new CopyOnWriteArrayList<>();
        for (SseEmitter emitter : bossProgressEmitters) {
            try {
                emitter.send(SseEmitter.event().name("ping").data("keep-alive"));
            } catch (Exception e) {
                if (e instanceof AsyncRequestNotUsableException ||
                        e instanceof ClientAbortException ||
                        (e.getCause() instanceof ClientAbortException) ||
                        (e instanceof IOException && String.valueOf(e.getMessage()).contains("中止了一个已建立的连接"))) {
                    log.debug("Boss进度 SSE 客户端已断开（心跳），移除连接: {}", e.getMessage());
                    try { emitter.complete(); } catch (Exception ignored) {}
                } else {
                    log.error("发送Boss进度心跳失败", e);
                }
                deadEmitters.add(emitter);
            }
        }
        bossProgressEmitters.removeAll(deadEmitters);
    }
}