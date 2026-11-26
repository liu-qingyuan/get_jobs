package com.getjobs.worker.boss;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.WaitUntilState;
import static com.getjobs.worker.boss.Locators.*;

/** Bounded Boss list/detail reads on the browser's owning thread. Reuses the authorized managed list when ready; never opens chat, logs in, writes legacy DB, or sends. */
public final class BossCandidateReader {
    public static final String OUTBOUND_BLOCK_REASON = "Boss 外发能力待核实：立即沟通可能先发未知自动招呼，不能保证核准精确文本；附件、在线资料、图片及重复联系均未获支持。不会 claim、启动旧批次或回退官网 AI";
    public static final class UserActionRequired extends RuntimeException {
        public UserActionRequired() { super("Boss 要求用户在可见浏览器完成登录或安全验证"); }
    }
    private BossCandidateReader() { }

    /** Reuses the authorized managed list page when it is ready; otherwise opens a new reader-owned page. Caller must serialize access. */
    public static ObjectNode read(Page existing, ObjectNode bundle, ObjectMapper json) throws Exception {
        String source = bundle.path("source").path("url").asText(); validateSource(source);
        double seconds = bundle.path("limits").path("seconds").asDouble();
        int slots = bundle.path("limits").path("candidates").asInt();
        if (!Double.isFinite(seconds) || seconds <= 0 || seconds > 120 || slots < 1 || slots > 40) throw new IllegalArgumentException("Boss 读取额度无效");
        if (existing == null || existing.isClosed()) throw new IllegalStateException("Boss 尚无本副本已授权会话；不会自动登录或创建浏览器");
        long deadline = System.nanoTime() + (long) (seconds * 1_000_000_000L);
        var result = json.createObjectNode(); var jobs = result.putArray("jobs");
        var links = new LinkedHashMap<String, ObjectNode>(); int incomplete = 0;
        var notes = new LinkedHashSet<String>();
        Page list = existing;
        boolean ownedList = false;
        ReadGuard listGuard = null;
        try {
            if (usableList(existing, source)) {
                listGuard = guard(list);
            } else {
                list = existing.context().newPage();
                ownedList = true;
                listGuard = guard(list);
                try {
                    navigate(list, source, deadline);
                } catch (RuntimeException e) {
                    if (listGuard.userAction.get() || requiresUserAction(list)) throw new UserActionRequired();
                    throw e;
                }
            }
            if (listGuard.userAction.get() || requiresUserAction(list)) throw new UserActionRequired();
            validateSource(list.url());
            list.locator(JOB_CARD_BOX).first().waitFor(new Locator.WaitForOptions().setTimeout(remaining(deadline)));
            var cards = list.locator(JOB_CARD_BOX); int count = cards.count();
            if (count > 400) throw new IllegalStateException("Boss 当前已加载列表过大；停止，不隐式扩展读取上限");
            for (int i = 0; i < count; i++) {
                var card = cards.nth(i); var anchor = card.locator("a[href*='/job_detail/']").first();
                String href = anchor.getAttribute("href", new Locator.GetAttributeOptions().setTimeout(remaining(deadline)));
                String link = detailUrl(URI.create(source).resolve(href).toString());
                if (links.containsKey(link)) continue;
                String title = card.locator(JOB_NAME).count() > 0 ? card.locator(JOB_NAME).first().innerText() : anchor.innerText();
                var company = card.locator(".company-name, " + COMPANY_NAME);
                if (title.isBlank() || company.count() == 0) throw new IllegalStateException("Boss 卡片结构未识别；不猜测公司/职位");
                links.put(link, json.createObjectNode().put("url", link).put("title", title).put("company", company.first().innerText())
                        .put("description", "").put("descriptionComplete", false));
            }
            String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(String.join("\n", links.keySet()).getBytes(StandardCharsets.UTF_8)));
            String cursor = bundle.path("source").path("cursor").asText(); int offset = 0;
            if (!cursor.isEmpty()) {
                String[] parts = cursor.split(":", -1);
                if (parts.length != 2 || !parts[0].equals(fingerprint) || !parts[1].matches("[0-9]{1,3}")) throw new IllegalStateException("Boss 已加载列表变化或游标无效；请显式启动新只读轮次");
                offset = Integer.parseInt(parts[1]);
                if (offset > links.size()) throw new IllegalStateException("Boss 游标超出已加载列表");
            }
            var selected = new ArrayList<>(links.values()); int end = Math.min(offset + slots, selected.size());
            for (int i = offset; i < end; i++) {
                remaining(deadline);
                var job = selected.get(i); Page detail = existing.context().newPage();
                boolean domComplete = false;
                try {
                    var observed = new AtomicReference<Response>();
                    detail.onResponse(response -> {
                        if (response.url().startsWith("https://www.zhipin.com/wapi/zpgeek/job/detail.json")
                                && response.request().method().equals("GET")) observed.compareAndSet(null, response);
                    });
                    var detailGuard = guard(detail);
                    try {
                        navigate(detail, job.path("url").asText(), deadline);
                    } catch (RuntimeException e) {
                        if (detailGuard.userAction.get() || requiresUserAction(detail)) throw new UserActionRequired();
                        throw e;
                    }
                    long domGrace = System.nanoTime() + 2_000_000_000L;
                    while (observed.get() == null) {
                        if (requiresUserAction(detail)) throw new UserActionRequired();
                        if (System.nanoTime() >= domGrace) {
                            String dom = domDescription(detail, job.path("url").asText());
                            if (dom != null) {
                                job.put("description", dom).put("descriptionComplete", true);
                                domComplete = true;
                                break;
                            }
                        }
                        detail.waitForTimeout(Math.min(100, remaining(deadline)));
                    }
                    if (!domComplete) {
                        Response response = observed.get();
                        if (requiresUserAction(detail) || response.status() == 401 || response.status() == 403) throw new UserActionRequired();
                        if (response.status() != 200 || !detailUrl(detail.url()).equals(job.path("url").asText())) throw new IllegalStateException("详情访问受限或跳转，不能绑定目标");
                        var body = json.readTree(response.text()); var data = body.path("zpData"); var info = data.path("jobInfo");
                        if (body.path("code").asInt(-1) != 0 || !info.path("encryptId").asText().equals(jobId(job.path("url").asText()))) throw new IllegalStateException("详情身份/schema 不匹配");
                        String title = info.path("jobName").asText(), company = data.path("brandComInfo").path("brandName").asText(), description = info.path("postDescription").asText();
                        if (title.isBlank() || company.isBlank() || description.isBlank()) throw new IllegalStateException("详情缺少公司、职位或完整描述");
                        var conditions = new ArrayList<String>();
                        for (String field : List.of("locationName", "experienceName", "degreeName")) if (!info.path(field).asText().isBlank()) conditions.add(info.path(field).asText());
                        job.put("title", title).put("company", company).put("description", String.join("；", conditions) + "\n" + description).put("descriptionComplete", true);
                    }
                } catch (UserActionRequired e) {
                    throw e;
                } catch (RuntimeException e) {
                    if (requiresUserAction(detail)) throw new UserActionRequired();
                    incomplete++; notes.add(e instanceof IllegalStateException ? e.getMessage() : "详情访问或响应超时/异常");
                }
                finally { detail.close(); }
                jobs.add(job);
            }
            result.put("nextUrl", end < selected.size() ? source : "").put("cursor", end < selected.size() ? fingerprint + ":" + end : "");
            result.put("note", incomplete == 0 ? "Boss 仅只读当前已加载列表及必要详情；登录后可用精确岗位页全文或观察到的同岗响应，不滚动、不猜测接口或翻页，不是联系/投递回执"
                    : "Boss 本页 " + incomplete + " 岗未获可绑定的完整详情，保留信息不足；不重试、不打开沟通，不以摘要或退出码冒充同岗全文。原因：" + String.join("；", notes));
            return result;
        } finally {
            if (listGuard != null && listGuard.handler != null) {
                try { list.unroute("**/*", listGuard.handler); } catch (RuntimeException ignored) { }
            }
            if (ownedList) list.close();
        }
    }

    /** The managed Boss page may already be the authorized list; use it instead of a risky new tab. */
    private static boolean usableList(Page page, String source) {
        try {
            if (page == null || page.isClosed() || !isHost(page.url())) return false;
            if (requiresUserAction(page)) return false;
            validateSource(page.url());
            return page.locator(JOB_CARD_BOX).count() > 0;
        } catch (RuntimeException e) {
            return false;
        }
    }
    private static double remaining(long deadline) {
        double milliseconds = (deadline - System.nanoTime()) / 1_000_000.0;
        if (milliseconds <= 0) throw new IllegalStateException("Boss 只读时间预算耗尽；保留断点");
        return milliseconds;
    }
    private static void navigate(Page page, String url, long deadline) {
        page.setDefaultTimeout(remaining(deadline));
        Response response = page.navigate(url, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(remaining(deadline)));
        if (response != null && (response.status() == 401 || response.status() == 403)) throw new UserActionRequired();
        if (response != null && response.status() >= 400) throw new IllegalStateException("Boss 只读访问受限；不重试或切换登录");
    }

    /** Exact job-detail page + full visible JD after login; still bound to the exact job URL. */
    private static String domDescription(Page detail, String expectedUrl) {
        try {
            if (detail == null || detail.isClosed()) return null;
            if (!detailUrl(detail.url()).equals(expectedUrl)) return null;
            if (requiresUserAction(detail)) return null;
            var sections = detail.locator(".job-sec-text");
            String best = null;
            for (int i = 0; i < sections.count(); i++) {
                String text = sections.nth(i).innerText();
                if (text == null || text.length() < 120 || text.contains("登录查看完整内容")) continue;
                if (best == null || text.length() > best.length()) best = text;
            }
            return best;
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static boolean requiresUserAction(Page page) {
        if (page == null || page.isClosed()) return true;
        try {
            String title = page.title().toLowerCase(Locale.ROOT);
            if (title.contains("请稍候") || title.contains("請稍候") || title.contains("安全验证")
                    || title.contains("security verification") || title.contains("just a moment")) return true;
        } catch (RuntimeException ignored) {
            return true;
        }
        try {
            URI current = URI.create(page.url());
            String path = current.getPath();
            if (path != null && (path.contains("/web/user") || path.contains("/user/login") || path.contains("/login"))) return true;
        } catch (IllegalArgumentException ignored) {
            return true;
        }
        try {
            if (page.locator("iframe[src*='challenge'], iframe[src*='captcha'], iframe[src*='verify']").first().isVisible()) return true;
            var prompt = page.getByText(Pattern.compile("^(安全验证|请完成验证|请稍候|Verify you are human|Security verification)$", Pattern.CASE_INSENSITIVE)).first();
            if (prompt.isVisible()) return true;
        } catch (RuntimeException ignored) { }
        try {
            // A public list still shows the top-nav "登录/注册" link; that is not a login wall.
            // Only a login redirect (URL check above), a real login dialog/form, or an explicit
            // "请登录" prompt blocks the read. This keeps anonymous public job lists readable.
            var login = page.locator("li.nav-sign a, .login-dialog, .sign-form, .login-layer, .login-box, [class*='login-dialog']").first();
            if (login.isVisible()) {
                String text = login.textContent(new Locator.TextContentOptions().setTimeout(300));
                if (text != null && text.contains("登录")) return true;
            }
        } catch (RuntimeException ignored) { }
        return false;
    }

    public static boolean accessReady(Page page, String source) {
        if (requiresUserAction(page)) return false;
        try { validateSource(page.url()); }
        catch (IllegalArgumentException e) { return false; }
        try {
            // The logged-in top-nav user icon is not proof that the job list is ready.
            // Require an actual card or an explicit empty-list marker.
            return page.locator(JOB_CARD_BOX).count() > 0
                    || page.locator("ul.rec-job-list [class*='empty'], ul.rec-job-list [class*='no-data']").count() > 0;
        } catch (RuntimeException e) { return false; }
    }

    /** One owning-thread poll: after login returns to a normal Boss page, navigate back to the exact authorized source. */
    public static boolean restoreAccess(Page page, String source, boolean returnToSource) {
        if (accessReady(page, source)) return true;
        if (!returnToSource || requiresUserAction(page) || !isHost(page.url())) return false;
        try {
            page.navigate(source, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(60000));
        } catch (RuntimeException e) {
            if (!requiresUserAction(page)) throw e;
        }
        return accessReady(page, source);
    }
    private static final class ReadGuard {
        private final java.util.concurrent.atomic.AtomicBoolean userAction = new java.util.concurrent.atomic.AtomicBoolean();
        private java.util.function.Consumer<Route> handler;
    }
    private static ReadGuard guard(Page page) {
        var guard = new ReadGuard();
        java.util.function.Consumer<Route> handler = route -> {
            var request = route.request(); URI uri = URI.create(request.url());
            String path = uri.getPath(), host = uri.getHost();
            boolean read = "www.zhipin.com".equals(host) && (path.matches("/web/geek/jobs/?") || path.matches("/job_detail/[A-Za-z0-9_-]+\\.html")
                    || Set.of("/wapi/zpgeek/job/detail.json", "/wapi/zpgeek/search/joblist.json").contains(path));
            boolean asset = host != null && (host.equals("zhipin.com") || host.endsWith(".zhipin.com")) && path.matches("(?i).*\\.(?:js|css|png|jpg|jpeg|svg|woff2?|ico)");
            boolean userAction = "GET".equals(request.method()) && request.isNavigationRequest() && isHost(request.url())
                    && (path.contains("/login") || path.contains("/web/user") || path.contains("/captcha") || path.contains("/verify") || path.contains("/challenge"));
            if (userAction) guard.userAction.set(true);
            // The logged-in list renders from a read-only search POST; allow only this exact
            // same-origin search endpoint. Every other write/contact/apply request remains blocked.
            boolean searchPost = "POST".equals(request.method()) && "https".equals(uri.getScheme())
                    && "www.zhipin.com".equals(host) && "/wapi/zpgeek/search/joblist.json".equals(path)
                    && ("xhr".equals(request.resourceType()) || "fetch".equals(request.resourceType()));
            if (("GET".equals(request.method()) && (read || asset)) || searchPost) route.fallback(); else route.abort();
        };
        guard.handler = handler;
        page.route("**/*", handler);
        return guard;
    }

    public static boolean isHost(String value) {
        try { String host = URI.create(value).getHost(); return host != null && (host.equalsIgnoreCase("zhipin.com") || host.toLowerCase(java.util.Locale.ROOT).endsWith(".zhipin.com")); }
        catch (IllegalArgumentException e) { return false; }
    }
    private static URI uri(String value) {
        URI uri = URI.create(value);
        if (!"https".equals(uri.getScheme()) || !"www.zhipin.com".equals(uri.getHost()) || uri.getUserInfo() != null || uri.getFragment() != null || uri.getPort() != -1 && uri.getPort() != 443)
            throw new IllegalArgumentException("Boss 仅接受 www.zhipin.com 的 HTTPS 标准来源");
        return uri;
    }
    public static String detailUrl(String value) {
        URI uri = uri(value);
        if (!uri.getPath().matches("/job_detail/[A-Za-z0-9_-]{1,150}\\.html")) throw new IllegalArgumentException("Boss 岗位须为精确 job_detail 身份链接");
        return "https://www.zhipin.com" + uri.getPath();
    }
    public static String jobId(String value) { return detailUrl(value).split("/job_detail/", 2)[1].replace(".html", ""); }
    public static void validateSource(String value) {
        if (!uri(value).getPath().matches("/web/geek/jobs/?")) throw new IllegalArgumentException("Boss 只读来源须为 web/geek/jobs 列表，不接受聊天、登录或旧批次入口");
    }
}
