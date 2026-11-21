package com.getjobs.worker.boss;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.WaitUntilState;
import static com.getjobs.worker.boss.Locators.*;

/** Bounded Boss list/detail reads on the browser's owning thread. Never opens chat, logs in, writes legacy DB, or sends. */
public final class BossCandidateReader {
    public static final String OUTBOUND_BLOCK_REASON = "Boss 外发能力待核实：立即沟通可能先发未知自动招呼，不能保证核准精确文本；附件、在线资料、图片及重复联系均未获支持。不会 claim、启动旧批次或回退官网 AI";
    private BossCandidateReader() { }

    /** Uses only new, reader-owned tabs in an already authorized context; caller must serialize access. */
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
        Page list = existing.context().newPage();
        try {
            guard(list); navigate(list, source, deadline); validateSource(list.url());
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
                try {
                    guard(detail);
                    Response response = detail.waitForResponse(r -> r.url().startsWith("https://www.zhipin.com/wapi/zpgeek/job/detail.json")
                                    && r.request().method().equals("GET"),
                            new Page.WaitForResponseOptions().setTimeout(remaining(deadline)), () -> navigate(detail, job.path("url").asText(), deadline));
                    if (response.status() != 200 || !detailUrl(detail.url()).equals(job.path("url").asText())) throw new IllegalStateException("详情访问受限或跳转，不能绑定目标");
                    var body = json.readTree(response.text()); var data = body.path("zpData"); var info = data.path("jobInfo");
                    if (body.path("code").asInt(-1) != 0 || !info.path("encryptId").asText().equals(jobId(job.path("url").asText()))) throw new IllegalStateException("详情身份/schema 不匹配");
                    String title = info.path("jobName").asText(), company = data.path("brandComInfo").path("brandName").asText(), description = info.path("postDescription").asText();
                    if (title.isBlank() || company.isBlank() || description.isBlank()) throw new IllegalStateException("详情缺少公司、职位或完整描述");
                    var conditions = new ArrayList<String>();
                    for (String field : List.of("locationName", "experienceName", "degreeName")) if (!info.path(field).asText().isBlank()) conditions.add(info.path(field).asText());
                    job.put("title", title).put("company", company).put("description", String.join("；", conditions) + "\n" + description).put("descriptionComplete", true);
                } catch (RuntimeException e) {
                    incomplete++; notes.add(e instanceof IllegalStateException ? e.getMessage() : "详情访问或响应超时/异常");
                }
                finally { detail.close(); }
                jobs.add(job);
            }
            result.put("nextUrl", end < selected.size() ? source : "").put("cursor", end < selected.size() ? fingerprint + ":" + end : "");
            result.put("note", incomplete == 0 ? "Boss 仅只读当前已加载列表及观察到的同岗详情响应；不滚动、不猜测接口或翻页，不是联系/投递回执"
                    : "Boss 本页 " + incomplete + " 岗未获可绑定的完整详情响应，保留信息不足；不重试、不打开沟通，不以 DOM 或退出码冒充同岗全文。原因：" + String.join("；", notes));
            return result;
        } finally { list.close(); }
    }
    private static double remaining(long deadline) {
        double milliseconds = (deadline - System.nanoTime()) / 1_000_000.0;
        if (milliseconds <= 0) throw new IllegalStateException("Boss 只读时间预算耗尽；保留断点");
        return milliseconds;
    }
    private static void navigate(Page page, String url, long deadline) {
        page.setDefaultTimeout(remaining(deadline));
        Response response = page.navigate(url, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(remaining(deadline)));
        if (response != null && response.status() >= 400) throw new IllegalStateException("Boss 只读访问受限；不重试或切换登录");
    }
    private static void guard(Page page) {
        page.route("**/*", route -> {
            var request = route.request(); URI uri = URI.create(request.url());
            String path = uri.getPath(), host = uri.getHost();
            boolean read = "www.zhipin.com".equals(host) && (path.matches("/web/geek/jobs/?") || path.matches("/job_detail/[A-Za-z0-9_-]+\\.html")
                    || Set.of("/wapi/zpgeek/job/detail.json", "/wapi/zpgeek/search/joblist.json").contains(path));
            boolean asset = host != null && (host.equals("zhipin.com") || host.endsWith(".zhipin.com")) && path.matches("(?i).*\\.(?:js|css|png|jpg|jpeg|svg|woff2?|ico)");
            if ("GET".equals(request.method()) && "https".equals(uri.getScheme()) && (read || asset)) route.fallback(); else route.abort();
        });
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
