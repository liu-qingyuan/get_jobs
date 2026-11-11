package com.getjobs.worker.boss;

import com.getjobs.application.entity.AiEntity;
import com.getjobs.application.service.AiService;
import com.getjobs.application.service.BossResumeImageService;
import com.getjobs.application.service.BossService;
import com.getjobs.worker.utils.Job;
import com.getjobs.worker.utils.JobUtils;
import com.getjobs.worker.utils.PlaywrightUtil;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.WaitForSelectorState;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import static com.getjobs.worker.boss.Locators.*;


/**
 * @author loks666
 * 项目链接: <a href=
 * "https://github.com/loks666/get_jobs">https://github.com/loks666/get_jobs</a>
 * Boss直聘自动投递
 */
@Slf4j
@Component
@Scope("prototype")
@RequiredArgsConstructor
public class Boss {

    @Setter
    private Page page;
    @Setter
    private BossConfig config;
    private final BossService bossService;
    private final AiService aiService;
    private final BossResumeImageService bossResumeImageService;
    private Set<String> blackCompanies;
    private Set<String> blackRecruiters;
    private Set<String> blackJobs;
    // 记录 encryptId -> encryptUserId 的映射，用于后续更新投递状态
    private final ConcurrentMap<String, String> encryptIdToUserId = new ConcurrentHashMap<>();
    @Setter
    private ProgressCallback progressCallback;
    @Setter
    private Supplier<Boolean> shouldStopCallback;

    private final List<Job> resultList = new ArrayList<>();

    /** Boss 首页，UI 搜索的入口 */
    private static final String BOSS_HOME_URL = "https://www.zhipin.com";
    /**
     * true=先回首页、在搜索框里做一次真实 UI 搜索再进列表页；false=直接拼 URL 跳转。
     * <p>
     * 默认关掉。实测 Boss 首页在受控标签页里会一直转圈加载不完，navigate 要 60 秒才返回，
     * 期间 evaluate / locator 全部无限期阻塞（这两个 API 没有默认超时）—— 启动慢、投递卡死、
     * 管理页面按钮没反应都出在这。而岗位列表页在同一个浏览器里是秒开的。
     * 想重新试 UI 搜索路径时再打开。
     */
    private static final boolean USE_UI_SEARCH = false;
    /** 等待页面加载状态的超时（毫秒），绝不能不设 —— 见 waitForPageSettled 的说明 */
    private static final double LOAD_STATE_TIMEOUT = 10_000;
    /** 图片选择完成后，等待聊天区出现本人图片消息的最长时间 */
    private static final int IMAGE_SEND_CONFIRM_TIMEOUT_SECONDS = 45;

    /**
     * 进度回调接口
     */
    @FunctionalInterface
    public interface ProgressCallback {
        void accept(String message, Integer current, Integer total);
    }

    // 通过 Lombok @RequiredArgsConstructor 使用构造器注入 bossService 与 aiService

    public void prepare() {
        // 调整 boss_data 表结构：将 encrypt_id、encrypt_user_id 前置
        try { bossService.ensureBossDataColumnOrder(); } catch (Throwable ignore) {}
        // 从数据库加载黑名单
        this.blackCompanies = bossService.getBlackCompanies();
        this.blackRecruiters = bossService.getBlackRecruiters();
        this.blackJobs = bossService.getBlackJobs();

        log.info("黑名单加载完成: 公司({}) 招聘者({}) 职位({})",
                blackCompanies != null ? blackCompanies.size() : 0,
                blackRecruiters != null ? blackRecruiters.size() : 0,
                blackJobs != null ? blackJobs.size() : 0);
        // 不在页面初始化阶段入库，仅用于后续点击卡片时按需入库
    }

    // One task owns the latch. No automatic recovery or restricted-page retry.
    private String stopReason;
    private String reviewedTarget;
    private boolean reviewedProcessed;
    private final Set<String> scannedSearches = new HashSet<>();
    private final Set<String> scannedJobs = new HashSet<>();
    private final Map<String, String> detailBodies = new HashMap<>();

    private IllegalStateException stop(String reason) {
        if (stopReason == null) stopReason = reason;
        return new IllegalStateException(stopReason);
    }

    private void checkAccess(Page current) {
        if (stopReason != null) throw stop(stopReason);
        if (shouldStopCallback != null && Boolean.TRUE.equals(shouldStopCallback.get()))
            throw stop("用户停止：后续访问与发送已终止");
        try {
            String url = current.url();
            if (url != null && url.contains("/403")) throw stop("访问受限：403 页面");
            if (isSecurityVerifyUrl(url)) throw stop("验证码/安全校验：任务已停止，不自动继续");
            if (url != null && (url.contains("/web/user/") || url.contains("/web/passport/")))
                throw stop("登录失效或身份校验：任务已停止");
            String body = current.locator("body").innerText(new Locator.InnerTextOptions().setTimeout(2000));
            if (body.contains("访问受限") || body.contains("限制访问") || body.replace("1.智能体安全核心研发：负责Agent全链路安全能力建设，实现工具调用安全沙箱、动态权限管控、多Agent交互审计、异常行为检测阻断、Agent劫持防护，保障智能体执行安全。", "").replace("4. 负责威胁检测引擎研发，⽀持⼊侵检测、异常行为分析、资产识别及攻击链关联分析；", "").replace("实现行为熔断系统，实时检测异常行为，并以温和、建设性的方式介入——从不阻止交易，但始终在关键时刻提供正确的数据。", "").contains("异常行为")
                    || body.contains("请勿频繁") || body.contains("访问过于频繁"))
                throw stop("访问受限：页面限制提示");
            Locator challenge = current.locator(".geetest_panel, .geetest_holder, .captcha-container, iframe[src*='captcha'], iframe[src*='verify']");
            if ((challenge.count() > 0 && challenge.first().isVisible()) || body.contains("安全验证") || body.contains("安全校验") || body.contains("请完成验证")
                    || body.contains("滑动验证") || body.contains("拖动滑块") || body.contains("验证码"))
                throw stop("验证码/安全校验：任务已停止，不自动继续");
            Locator login = current.locator("li.nav-sign, .sign-form, .login-dialog, .login-layer");
            if ((login.count() > 0 && login.first().isVisible()) || body.contains("登录已失效")
                    || body.contains("登录已过期") || body.contains("请先登录") || body.contains("请登录后使用"))
                throw stop("登录失效：任务已停止");
            if (stopReason != null) throw stop(stopReason); // response events can run during DOM reads
        } catch (RuntimeException e) {
            if (stopReason != null) throw stop(stopReason);
            throw stop("页面解析异常：已停止，不自动重试");
        }
    }

    private void observeResponse(Response response) {
        try {
            String host = java.net.URI.create(response.url()).getHost();
            if (host == null || !(host.equals("zhipin.com") || host.endsWith(".zhipin.com"))) return;
            int status = response.status();
            if (status == 401 || status == 403 || status == 429) {
                stop("HTTP " + status + "：访问受限或登录失效，任务已停止");
            } else if (response.url().contains("/wapi/") && response.url().contains(".json")) {
                JSONObject body = new JSONObject(response.text());
                String responsePath = java.net.URI.create(response.url()).getPath();
                // This optional analyzer explicitly reports that it has never been used.
                // Exact captured response only; never exempt auth/rate limits or send APIs.
                boolean unusedOptionalAnalyzer = status == 200
                        && "/wapi/zpitem/web/competitive/jobDetail.json".equals(responsePath)
                        && body.optInt("code", -1) == 600053
                        && "该职位还未使用竞争力分析器, 请重试".equals(body.optString("message"))
                        && body.optJSONObject("zpData") != null
                        && body.getJSONObject("zpData").isEmpty();
                if (body.has("code") && body.optInt("code", -1) != 0 && !unusedOptionalAnalyzer)
                    stop("平台接口异常：code=" + body.optInt("code", -1)
                            + " path=" + responsePath + "，任务已停止");
                if (response.url().contains("/wapi/zpgeek/job/detail.json") && body.optInt("code", -1) == 0) {
                    JSONObject info = body.getJSONObject("zpData").getJSONObject("jobInfo");
                    detailBodies.put(info.getString("encryptId"), body.toString());
                }
            }
        } catch (RuntimeException e) {
            stop("平台响应解析异常：任务已停止");
        }
    }

    public int execute() {
        if (!Boolean.TRUE.equals(config.getDebugger())) {
            ReviewedApplication.requireSupportedOptions(config);
            try { reviewedTarget = ReviewedApplication.reviewedTarget(ReviewedApplication.APPROVAL); }
            catch (Exception e) { throw stop("单岗审核门禁：" + e.getMessage()); }
        }
        var context = page.context();
        java.util.function.Consumer<Response> listener = this::observeResponse;
        java.util.function.Consumer<com.microsoft.playwright.Route> route = r -> {
            String host = java.net.URI.create(r.request().url()).getHost();
            boolean bossRequest = host != null && (host.equals("zhipin.com") || host.endsWith(".zhipin.com"));
            if (bossRequest && (stopReason != null || (shouldStopCallback != null && Boolean.TRUE.equals(shouldStopCallback.get()))))
                r.abort();
            else r.fallback();
        };
        context.onResponse(listener);
        try {
            context.route("**/*", route);
            checkAccess(page);
            for (String cityCode : config.getCityCode()) {
                checkAccess(page);
                postJobByCity(cityCode);
                if (reviewedProcessed) break;
            }
            checkAccess(page);
            if (reviewedTarget != null && !reviewedProcessed)
                throw stop("未找到已审核岗位：停止，不自动重新扫描");
            return resultList.size();
        } finally {
            context.offResponse(listener);
            context.unroute("**/*", route);
        }
    }

    /**
     * 获取结果列表
     */
    public List<Job> getResultList() {
        return new ArrayList<>(resultList);
    }

    /**
     * 更新黑名单（从聊天记录中）
     */
    public Map<String, Set<String>> updateBlacklistFromChats() {
        page.navigate("https://www.zhipin.com/web/geek/chat");
        PlaywrightUtil.sleep(3);

        int newBlacklistCount = 0;
        boolean shouldBreak = false;
        while (!shouldBreak) {
            try {
                Locator bottomLocator = page.locator(FINISHED_TEXT);
                if (bottomLocator.count() > 0 && "没有更多了".equals(bottomLocator.textContent())) {
                    shouldBreak = true;
                }
            } catch (Exception ignore) {
            }

            Locator items = page.locator(CHAT_LIST_ITEM);
            int itemCount = items.count();

            for (int i = 0; i < itemCount; i++) {
                try {
                    Locator companyElements = page.locator(COMPANY_NAME_IN_CHAT);
                    Locator messageElements = page.locator(LAST_MESSAGE);

                    if (i >= companyElements.count() || i >= messageElements.count()) {
                        break;
                    }

                    String companyName = null;
                    String message = null;
                    int retryCount = 0;

                    while (true) {
                        try {
                            companyName = companyElements.nth(i).textContent();
                            message = messageElements.nth(i).textContent();
                            break;
                        } catch (Exception e) {
                            retryCount++;
                            if (retryCount >= 2) {
                                log.info("尝试获取元素文本2次失败，放弃本次获取");
                                break;
                            }
                            log.info("页面元素已变更，正在重试第{}次获取元素文本...", retryCount);
                            PlaywrightUtil.sleep(1);
                        }
                    }

                    if (companyName != null && message != null) {
                        boolean match = message.contains("不") || message.contains("感谢") || message.contains("但")
                                || message.contains("遗憾") || message.contains("需要本") || message.contains("对不");
                        boolean nomatch = message.contains("不是") || message.contains("不生");
                        if (match && !nomatch) {
                            if (blackCompanies.stream().anyMatch(companyName::contains)) {
                                continue;
                            }
                            companyName = companyName.replaceAll("\\.{3}", "");
                            if (companyName.matches(".*(\\p{IsHan}{2,}|[a-zA-Z]{4,}).*")) {
                                blackCompanies.add(companyName);
                                // 保存到数据库
                                bossService.addBlacklist("company", companyName);
                                newBlacklistCount++;
                                log.info("黑名单公司：【{}】，信息：【{}】", companyName, message);
                            }
                        }
                    }
                } catch (Exception e) {
                    log.error("寻找黑名单公司异常...", e);
                }
            }

            try {
                Locator scrollElement = page.locator(SCROLL_LOAD_MORE);
                if (scrollElement.count() > 0) {
                    scrollElement.scrollIntoViewIfNeeded();
                } else {
                    page.evaluate("window.scrollTo(0, document.body.scrollHeight);");
                }
            } catch (Exception e) {
                log.error("滚动元素出错", e);
                break;
            }
        }
        log.info("黑名单公司数量：{}，本次新增：{}", (blackCompanies != null ? blackCompanies.size() : 0), newBlacklistCount);

        Map<String, Set<String>> result = new HashMap<>();
        result.put("blackCompanies", new HashSet<>(blackCompanies != null ? blackCompanies : Collections.emptySet()));
        result.put("blackRecruiters", new HashSet<>(blackRecruiters != null ? blackRecruiters : Collections.emptySet()));
        result.put("blackJobs", new HashSet<>(blackJobs != null ? blackJobs : Collections.emptySet()));
        return result;
    }

    private void postJobByCity(String cityCode) {
        String searchUrl = getSearchUrl(cityCode);
        for (String keyword : config.getKeywords()) {
            // 检查是否需要停止
            if (shouldStopCallback.get()) {
                progressCallback.accept("用户取消投递", 0, 0);
                return;
            }

            // 使用 URLEncoder 对关键词进行编码
            String encodedKeyword = URLEncoder.encode(keyword, StandardCharsets.UTF_8);

            String url = searchUrl + (searchUrl.contains("?") ? "&" : "?") + "query=" + encodedKeyword;
            if (reviewedProcessed) return;
            if (!scannedSearches.add(url)) continue;
            checkAccess(page);
            // Any uncertainty terminates the whole task, not just this keyword.
            try {
            // Navigate once; errors terminate the whole task.
            openJobListOnce(keyword, url, cityCode);

            // 1. 基于 footer 出现滚动到底，确保加载全部岗位
            int lastCount = -1;
            int stableTries = 0;
            // 上限 300 轮：原来写的是 5000，而且 stableTries 只触发强制触底、从不退出循环，
            // 一旦 footer 选择器匹配不到就会空转几千轮，每轮一次 evaluate + count，
            // 能把 playwright 线程占死几十分钟 —— 整个应用跟着卡住、投递任务也永远结束不了。
            boolean loadedAll = false;
            for (int i = 0; i < 300; i++) {
                checkAccess(page);
                // The reviewed card is enough; do not preload the rest of the candidate set.
                if (reviewedTarget != null && page.locator("a[href*='" +
                        java.net.URI.create(reviewedTarget).getPath() + "']").count() > 0) {
                    loadedAll = true;
                    break;
                }
                // 停止检查：滚动加载过程中也要及时响应
                if (shouldStopCallback != null && Boolean.TRUE.equals(shouldStopCallback.get())) {
                    progressCallback.accept("用户取消投递", 0, 0);
                    return;
                }
                // A failed DOM read is uncertainty, not an empty candidate set.
                try {
                    // footer 可见不能立刻就当作"加载完了"：窗口最大化时首屏很短，
                    // 第一轮 footer 就是可见的，会导致只拿到首屏 15 个岗位就退出
                    // （实测同样的搜索条件，正常滚完是 300 个）。
                    // 必须先滚一段、并且连着几轮没有新增岗位，footer 才算数。
                    boolean footerVisible = false;
                    Locator footer = page.locator("div#footer, #footer");
                    if (footer.count() > 0 && footer.first().isVisible()) {
                        footerVisible = true;
                    }
                    if (footerVisible && stableTries >= 2) {
                        log.info("【{}】已滚动到底部且连续 {} 轮无新增，判定加载完毕", keyword, stableTries);
                        loadedAll = true;
                        break;
                    }
                    // 按视口高度的90%渐进滚动，触发懒加载
                    page.evaluate("() => window.scrollBy(0, Math.floor(window.innerHeight * 1.5))");

                    // 获取卡片数量变化，判断是否需要强制触底
                    Locator cardsProbe = page.locator("//ul[contains(@class, 'rec-job-list')]//li[contains(@class, 'job-card-box')]");
                    int currentCount = cardsProbe.count();
                    if (currentCount == lastCount) {
                        stableTries++;
                    } else {
                        stableTries = 0;
                    }
                    lastCount = currentCount;

                    if (stableTries >= 3) { // 连续多次无新增，则强制触底一次
                        page.evaluate("() => window.scrollTo(0, document.body.scrollHeight)");
                        // 触底不再等待，继续检测 footer 出现
                    }
                    // 强制触底之后仍然连着好几轮没有新岗位，就认定加载完了。
                    // 不能只靠 footer —— Boss 有些版式根本没有 #footer，只等它就是死循环。
                    if (stableTries >= 8) {
                        log.info("【{}】连续 {} 轮没有新增岗位，判定已加载完毕", keyword, stableTries);
                        loadedAll = true;
                        break;
                    }
                } catch (Exception e) {
                    checkAccess(page);
                    throw stop("列表解析异常：已停止，不把失败当空列表");
                }
            }
            if (!loadedAll) throw stop("列表加载未确认：达到滚动上限，任务已停止");
            // 统计最终岗位数量
            int loadedCount = countJobCards();
            checkAccess(page);
            log.info("【{}】岗位已全部加载，总数:{}", keyword, loadedCount);
            progressCallback.accept("岗位加载完成：" + keyword, 0, loadedCount);

            // 2. 回到页面顶部
            page.evaluate("window.scrollTo(0, 0);");
            PlaywrightUtil.sleep(1);

            // 3. 逐个遍历所有岗位
            Locator cards = page.locator("//ul[contains(@class, 'rec-job-list')]//li[contains(@class, 'job-card-box')]");
            int count = cards.count();
            for (int i = 0; i < count; i++) {
                if (reviewedProcessed) return;
                // 检查是否需要停止
                if (shouldStopCallback != null && Boolean.TRUE.equals(shouldStopCallback.get())) {
                    progressCallback.accept("用户取消投递", i, count);
                    return;
                }

                checkAccess(page);
                cards = page.locator(JOB_LIST_SELECTOR);
                Locator card = cards.nth(i);
                String href = card.locator("a[href*='/job_detail/']").first().getAttribute("href");
                if (href == null || href.isBlank()) throw stop("岗位卡片解析异常：缺少岗位链接");
                String candidate = java.net.URI.create(BOSS_HOME_URL).resolve(href).toString().split("\\?", 2)[0];
                if (!scannedJobs.add(candidate)) continue;
                if (reviewedTarget != null && !reviewedTarget.equals(candidate)) continue;
                // The first card can already be selected by the page. Reuse its
                // observed response only if the visible detail link binds the same job.
                String detailBody = detailBodies.get(extractEncryptId(candidate));
                Locator currentDetail = page.locator("a.more-job-btn");
                String currentHref = currentDetail.count() == 1 ? currentDetail.getAttribute("href") : null;
                boolean selected = currentHref != null && candidate.equals(
                        java.net.URI.create(BOSS_HOME_URL).resolve(currentHref).toString().split("\\?", 2)[0]);
                if (detailBody == null || !selected) {
                    try {
                        checkAccess(page);
                        Response detailResp = page.waitForResponse(r -> {
                            observeResponse(r);
                            return stopReason != null || (r.url().contains("/wapi/zpgeek/job/detail.json")
                                    && "GET".equalsIgnoreCase(r.request().method()));
                        }, new Page.WaitForResponseOptions().setTimeout(5000), card::click);
                        observeResponse(detailResp);
                        checkAccess(page);
                        detailBody = detailResp.text();
                    } catch (Exception e) {
                        checkAccess(page);
                        throw stop("岗位详情响应缺失：任务已停止，不访问下一岗位");
                    }
                }
                checkAccess(page);

                // 统一从请求返回的 JSON 中获取数据并做过滤
                String jobName = null;
                String jobSalary = null;
                java.util.List<String> tags = new java.util.ArrayList<>();
                String jobDesc = null;
                String bossName = null;
                String bossActive = null;
                String bossCompany = null;
                String bossJobTitle = null;

                if (detailBody != null) {
                    try {
                        String body = detailBody;
                        JSONObject responseRoot = new JSONObject(body);
                        if (responseRoot.optInt("code", -1) != 0)
                            throw stop("岗位详情接口异常：code=" + responseRoot.optInt("code", -1));
                        JSONObject data = responseRoot.getJSONObject("zpData");
                        String responseId = data.getJSONObject("jobInfo").getString("encryptId");
                        if (!Objects.equals(extractEncryptId(candidate), responseId))
                            throw stop("岗位详情身份不匹配：任务已停止");
                        // 保存原始 JSON 便于调试
                        appendRawJson(body);
                        // 从 JSON 构建用于投递与过滤的字段
                        org.json.JSONObject root = new org.json.JSONObject(body);
                        org.json.JSONObject zpData = root.optJSONObject("zpData");
                        org.json.JSONObject jobInfo = zpData != null ? zpData.optJSONObject("jobInfo") : null;
                        org.json.JSONObject brand = zpData != null ? zpData.optJSONObject("brandComInfo") : null;
                        org.json.JSONObject boss = zpData != null ? zpData.optJSONObject("bossInfo") : null;

                        if (jobInfo != null) {
                            jobName = jobInfo.optString("jobName", "");
                            jobSalary = jobInfo.optString("salaryDesc", "");
                            String city = jobInfo.optString("locationName", "");
                            String exp = jobInfo.optString("experienceName", "");
                            String deg = jobInfo.optString("degreeName", "");
                            if (!city.isEmpty()) tags.add(city);
                            if (!exp.isEmpty()) tags.add(exp);
                            if (!deg.isEmpty()) tags.add(deg);
                            jobDesc = jobInfo.optString("postDescription", "");
                        }

                        if (boss != null) {
                            bossName = boss.optString("name", "");
                            bossActive = boss.optString("activeTimeDesc", "");
                            bossJobTitle = boss.optString("title", "");
                        }

                        if (brand != null) {
                            bossCompany = brand.optString("brandName", "");
                        }
                    } catch (Exception e) {
                        checkAccess(page);
                        throw stop("岗位详情解析异常：任务已停止，不构造空岗位");
                    }
                }
                if (jobName == null || jobName.isBlank() || bossCompany == null || bossCompany.isBlank()
                        || jobDesc == null || jobDesc.isBlank())
                    throw stop("岗位详情解析异常：缺少名称、公司或描述");
                checkAccess(page);
                // Persist only after required identity/content fields have validated.
                processJobDetailJsonAndInsert(detailBody);
                if (reviewedTarget != null) reviewedProcessed = true;

                // 过滤（全部基于 JSON 字段），并输出过滤原因
                if (jobName != null && blackJobs != null && blackJobs.stream().anyMatch(jobName::contains)) {
                    String term = findMatchedTerm(blackJobs, jobName);
                    log.info("被过滤：职位黑名单命中 | 公司：{} | 岗位：{} | 关键词：{}", bossCompany != null ? bossCompany : "", jobName, term != null ? term : "");
                    continue;
                }
                // HR活跃状态过滤：当开启过滤开关且活跃描述包含“年”时，视为不活跃
                boolean hrInactiveByYear = bossActive != null && bossActive.contains("年");
                if (Boolean.TRUE.equals(config.getFilterDeadHR()) && hrInactiveByYear) {
                    log.info("被过滤：HR活跃状态包含‘年’ | 公司：{} | 岗位：{} | 活跃：{}", bossCompany != null ? bossCompany : "", jobName != null ? jobName : "", bossActive);
                    continue;
                }
                if (bossCompany != null && blackCompanies != null && blackCompanies.stream().anyMatch(bossCompany::contains)) {
                    String term = findMatchedTerm(blackCompanies, bossCompany);
                    log.info("被过滤：公司黑名单命中 | 公司：{} | 岗位：{} | 关键词：{}", bossCompany, jobName != null ? jobName : "", term != null ? term : "");
                    continue;
                }
                if (bossJobTitle != null && blackRecruiters != null && blackRecruiters.stream().anyMatch(bossJobTitle::contains)) {
                    String term = findMatchedTerm(blackRecruiters, bossJobTitle);
                    log.info("被过滤：招聘者黑名单命中 | 公司：{} | 岗位：{} | 招聘者：{} | 关键词：{}", bossCompany != null ? bossCompany : "", jobName != null ? jobName : "", bossJobTitle, term != null ? term : "");
                    continue;
                }

                // 创建Job对象（全部基于 JSON 字段）
                Job job = new Job();
                job.setJobName(jobName != null ? jobName : "");
                job.setSalary(jobSalary != null ? jobSalary : "");
                job.setJobArea(String.join(", ", tags));
                job.setCompanyName(bossCompany != null ? bossCompany : "");
                job.setRecruiter(bossName != null ? bossName : "");
                job.setJobInfo(jobDesc != null ? jobDesc : "");

                // 输出
                progressCallback.accept("正在核验岗位：" + jobName, i + 1, count);
                // Only confirmed receipts enter resultList, the authoritative success count.
                resumeSubmission(keyword, job);
                if (reviewedProcessed) return;

                checkAccess(page);
                // 仅在已核验的页面上滚动。
                try {
                    if (i >= 5) {
                        page.evaluate("window.scrollBy(0, 140);");
                        PlaywrightUtil.sleep(1);
                    }
                } catch (Exception e) {
                    checkAccess(page);
                    throw stop("列表滚动异常：任务已停止");
                }

                // 间隔不是平台允许自动化或免于限制的保证。
                pauseBetweenJobs();

                checkAccess(page);
            }
            log.info("【{}】候选岗位核验结束；点击和日志不计为发送成功", keyword);
            } catch (Exception e) {
                checkAccess(page);
                throw stop("任务异常停止：" + e.getMessage());
            }
        }
    }

    /**
     * 解析岗位详情 JSON 并进行入库与黑名单处理（只在点击卡片时调用）。
     */
    private void processJobDetailJsonAndInsert(String body) {
        if (body == null || body.isEmpty()) return;
        try {
            JSONObject root = new JSONObject(body);
            JSONObject zpData = root.optJSONObject("zpData");
            if (zpData == null) return;

            JSONObject jobInfo = zpData.optJSONObject("jobInfo");
            JSONObject brand = zpData.optJSONObject("brandComInfo");
            JSONObject bossInfo = zpData.optJSONObject("bossInfo");
            if (jobInfo == null) return;

            String encryptId = jobInfo.optString("encryptId", null);
            String encryptUserId = jobInfo.optString("encryptUserId", null);
            if (encryptUserId == null && bossInfo != null) {
                // 兼容部分页面字段落在 bossInfo 内
                encryptUserId = bossInfo.optString("encryptUserId", null);
                if (encryptUserId == null) {
                    // 进一步兼容可能的字段命名
                    encryptUserId = bossInfo.optString("encryptBossId", null);
                }
            }
            if (encryptId != null && encryptUserId != null) {
                encryptIdToUserId.put(encryptId, encryptUserId);
            }

            com.getjobs.application.entity.BossJobDataEntity entity = new com.getjobs.application.entity.BossJobDataEntity();
            entity.setJobName(jobInfo.optString("jobName", null));
            entity.setSalary(jobInfo.optString("salaryDesc", null));
            entity.setLocation(jobInfo.optString("locationName", null));
            entity.setExperience(jobInfo.optString("experienceName", null));
            entity.setDegree(jobInfo.optString("degreeName", null));
            entity.setJobDescription(jobInfo.optString("postDescription", null));
            entity.setRecruitmentStatus(jobInfo.optString("jobStatusDesc", null));
            entity.setCompanyAddress(jobInfo.optString("address", null));
            entity.setEncryptId(encryptId);
            entity.setEncryptUserId(encryptUserId);

            entity.setCompanyName(brand != null ? brand.optString("brandName", null) : null);
            entity.setIndustry(brand != null ? brand.optString("industryName", null) : null);
            entity.setIntroduce(brand != null ? brand.optString("introduce", null) : null);
            entity.setFinancingStage(brand != null ? brand.optString("stageName", null) : null);
            entity.setCompanyScale(brand != null ? brand.optString("scaleName", null) : null);

            entity.setHrName(bossInfo != null ? bossInfo.optString("name", null) : null);
            entity.setHrPosition(bossInfo != null ? bossInfo.optString("title", null) : null);
            entity.setHrActiveStatus(bossInfo != null ? bossInfo.optString("activeTimeDesc", null) : null);

            if (encryptId != null && !encryptId.isEmpty()) {
                entity.setJobUrl("https://www.zhipin.com/job_detail/" + encryptId + ".html");
            }

            // 黑名单处理
            boolean filtered = false;
            String companyName = entity.getCompanyName() != null ? entity.getCompanyName() : "";
            String positionName = entity.getJobName() != null ? entity.getJobName() : "";
            String hrPosition = entity.getHrPosition() != null ? entity.getHrPosition() : "";
            try {
                if (blackCompanies != null && blackCompanies.stream().anyMatch(companyName::contains)) filtered = true;
                if (!filtered && blackJobs != null && blackJobs.stream().anyMatch(positionName::contains)) filtered = true;
                if (!filtered && blackRecruiters != null && blackRecruiters.stream().anyMatch(hrPosition::contains)) filtered = true;
            } catch (Throwable ignore) {}

            // HR活跃状态过滤：开启过滤且活跃描述包含“年”，则标记为已过滤，但仍入库
            if (!filtered && Boolean.TRUE.equals(config.getFilterDeadHR())) {
                String hrActive = entity.getHrActiveStatus();
                if (hrActive != null && hrActive.contains("年")) {
                    filtered = true;
                }
            }

            entity.setDeliveryStatus(filtered ? "已过滤" : "未投递");

            // 入库（若不存在），优先以 encrypt_id + encrypt_user_id 去重；若 userId 缺失，则以 encrypt_id 去重
            if (encryptId != null) {
                try {
                    boolean exists = false;
                    if (encryptUserId != null) {
        exists = bossService.existsBossJob(encryptId, encryptUserId);
                    } else {
        exists = bossService.existsBossJobByEncryptId(encryptId);
                    }
                    if (!exists) {
        bossService.insertBossJob(entity);
                        log.debug("岗位入库：{} | 公司：{} | HR：{} | 状态：{}", entity.getJobName(), entity.getCompanyName(), entity.getHrName(), entity.getDeliveryStatus());
                    }
                } catch (Exception e) {
                    log.warn("岗位入库失败：{}", e.getMessage());
                }
            }
        } catch (Throwable e) {
            log.debug("解析岗位详情 JSON 失败：{}", e.getMessage());
        }
    }

    public String decodeSalary(String text) {
        Map<Character, Character> fontMap = new HashMap<>();
        fontMap.put('\uE8F0', '0');
        fontMap.put('\uE8F1', '1');
        fontMap.put('\uE8F2', '2');
        fontMap.put('\uE8F3', '3');
        fontMap.put('\uE8F4', '4');
        fontMap.put('\uE8F5', '5');
        fontMap.put('\uE8F6', '6');
        fontMap.put('\uE8F7', '7');
        fontMap.put('\uE8F8', '8');
        fontMap.put('\uE8F9', '9');
        StringBuilder result = new StringBuilder();
        for (char c : text.toCharArray()) {
            result.append(fontMap.getOrDefault(c, c));
        }
        return result.toString();
    }

    // 安全获取单个文本内容
    public String safeText(Locator root, String selector) {
        Locator node = root.locator(selector);
        try {
            if (node.count() > 0 && node.innerText() != null) {
                return node.innerText().trim();
            }
        } catch (Exception e) {
            // ignore
        }
        return "";
    }

    // 安全获取多个文本内容
    public List<String> safeAllText(Locator root, String selector) {
        try {
            return root.locator(selector).allInnerTexts();
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    // Boss姓名+活跃状态拆分
    public String[] splitBossName(String raw) {
        String[] bossParts = raw.trim().split("\\s+");
        String bossName = bossParts[0];
        String bossActive = bossParts.length > 1 ? String.join(" ", Arrays.copyOfRange(bossParts, 1, bossParts.length)) : "";
        return new String[]{bossName, bossActive};
    }

    // Boss公司+职位拆分
    public String[] splitBossTitle(String raw) {
        String[] parts = raw.trim().split(" · ");
        String company = parts[0];
        String job = parts.length > 1 ? parts[1] : "";
        return new String[]{company, job};
    }

    // 匹配命中词条（用于日志输出过滤原因）
    private String findMatchedTerm(java.util.Collection<String> patterns, String text) {
        if (patterns == null || text == null) return null;
        try {
            for (String p : patterns) {
                if (p != null && !p.isEmpty() && text.contains(p)) {
                    return p;
                }
            }
        } catch (Exception ignore) {
        }
        return null;
    }

    public static String buildSearchUrl(BossConfig config, String cityCode) {
        String baseUrl = "https://www.zhipin.com/web/geek/jobs";
        if (config == null) {
            return baseUrl;
        }
        List<String> params = new ArrayList<>();
        addParam(params, JobUtils.appendParam("city", cityCode));
        addParam(params, JobUtils.appendParam("jobType", config.getJobType()));
        addParam(params, JobUtils.appendListParam("salary", config.getSalary()));
        addParam(params, JobUtils.appendListParam("experience", config.getExperience()));
        addParam(params, JobUtils.appendListParam("degree", config.getDegree()));
        addParam(params, JobUtils.appendListParam("scale", config.getScale()));
        addParam(params, JobUtils.appendListParam("industry", config.getIndustry()));
        addParam(params, JobUtils.appendListParam("stage", config.getStage()));
        if (params.isEmpty()) {
            return baseUrl;
        }
        return baseUrl + "?" + String.join("&", params);
    }

    private static void addParam(List<String> params, String param) {
        if (param == null || param.isEmpty()) {
            return;
        }
        params.add(param.startsWith("&") ? param.substring(1) : param);
    }

    private String getSearchUrl(String cityCode) {
        return buildSearchUrl(config, cityCode);
    }

    /**
     * 进入岗位列表页。
     * <p>
     * 优先模拟真人路径：回首页 -> 在搜索框里逐字输入关键词 -> 点搜索按钮，
     * 而不是冷启动直接把拼好的列表页 URL 丢给浏览器。
     * <p>
     * UI 搜索只能带上关键词和首页当前城市，config 里的城市/薪资/经验等筛选项仍然只能靠 URL，
     * 所以落地之后如果条件对不上，会再做一次同标签页跳转 —— 此时已经是热会话 + 同源 referer，
     * 和冷启动直闯不是一回事。UI 搜索任何一步失败都会退回原来的直接跳转，不影响主流程。
     */
    private void openJobList(String keyword, String targetUrl, String cityCode) {
        boolean uiSearchDone = USE_UI_SEARCH && searchFromHomePage(keyword);
        if (!uiSearchDone) {
            navigateToJobList(targetUrl);
        } else if (needsUrlFilters(targetUrl, cityCode)) {
            log.info("【{}】UI搜索已落地，补充筛选条件跳转：{}", keyword, targetUrl);
            navigateToJobList(targetUrl);
        }
        warnIfSecurityCheck(keyword);
    }

    /**
     * 在 Boss 首页搜索框里做一次真实的 UI 搜索。
     *
     * @return 是否成功落到岗位列表页
     */
    private boolean searchFromHomePage(String keyword) {
        try {
            String current = page.url();
            if (current == null || !current.startsWith(BOSS_HOME_URL) || current.contains("/web/geek/")) {
                navigateTo(BOSS_HOME_URL);
            }
            // 首页落地后还会自己跳一次（/shanghai/?seoRefer=index 之类），
            // 不等它跳完就找搜索框，会卡在 "waiting for navigation to finish" 直到超时
            waitForPageSettled();

            Locator input = page.locator(HOME_SEARCH_INPUT).first();
            input.waitFor(new Locator.WaitForOptions().setTimeout(10_000));
            input.click();
            input.fill("");
            // 逐字输入，模拟真人打字节奏；一次性 fill 在输入行为层面太干净了
            input.pressSequentially(keyword, new Locator.PressSequentiallyOptions().setDelay(140));
            PlaywrightUtil.sleep(1);

            Locator searchBtn = page.locator(HOME_SEARCH_BUTTON).first();
            if (searchBtn.count() > 0) {
                searchBtn.click();
            } else {
                input.press("Enter");
            }
            // 表单提交是当前标签页跳转；若 Boss 改成新开标签页，这里会超时并退回直接跳转
            page.waitForURL("**/web/geek/jobs**", new Page.WaitForURLOptions().setTimeout(15_000));
            log.info("【{}】已通过首页搜索框进入岗位列表：{}", keyword, page.url());
            return true;
        } catch (Exception e) {
            log.warn("【{}】首页UI搜索失败，退回直接跳转：{}", keyword, e.getMessage());
            return false;
        }
    }

    /**
     * UI 搜索落地后，判断还需不需要用 URL 把筛选条件补上。
     */
    private boolean needsUrlFilters(String targetUrl, String cityCode) {
        String landed = page.url();
        if (landed == null) {
            return true;
        }
        // 首页搜索用的是首页当前城市，不一定等于配置里的城市
        if (cityCode != null && !cityCode.isEmpty() && !landed.contains("city=" + cityCode)) {
            return true;
        }
        // 除 city/query 外还有别的筛选项（薪资、经验、学历……），UI 搜索带不上
        int queryStart = targetUrl.indexOf('?');
        if (queryStart < 0) {
            return false;
        }
        for (String param : targetUrl.substring(queryStart + 1).split("&")) {
            int eq = param.indexOf('=');
            String name = eq < 0 ? param : param.substring(0, eq);
            String value = eq < 0 ? "" : param.substring(eq + 1);
            if (value.isEmpty() || "city".equals(name) || "query".equals(name)) {
                continue;
            }
            return true;
        }
        return false;
    }

    private void navigateToJobList(String url) {
        navigateTo(url);
    }

    /**
     * Navigate once. A failed navigation is terminal, including ERR_ABORTED.
     */
    private void navigateTo(String url) {
        checkAccess(page);
        try {
            Response response = page.navigate(url, new Page.NavigateOptions()
                    .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED)
                    .setTimeout(15_000));
            if (response != null) observeResponse(response);
            checkAccess(page);
        } catch (Exception e) {
            checkAccess(page);
            throw stop("导航失败：任务已停止，不自动重试");
        }
    }

    /**
     * 判断页面是否已经落在目标地址上（只比较路径，查询参数会被 Boss 改写）。
     */
    private boolean landedOn(String targetUrl) {
        try {
            String current = page.url();
            if (current == null) {
                return false;
            }
            String targetPath = targetUrl.split("\\?")[0];
            return current.startsWith(targetPath);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 等页面真正稳定下来再继续。
     * <p>
     * DOMCONTENTLOADED 返回之后 Boss 还会自己做客户端跳转，此时 frame 被替换，
     * 后续任何 locator 调用都会报 "Object doesn't exist: frame@..."。
     */
    private void settleAfterNavigation() {
        waitForPageSettled();
    }

    /**
     * 等页面进入 domcontentloaded，最多等 {@value #LOAD_STATE_TIMEOUT} 毫秒。
     * <p>
     * 两个坑都踩过了：
     * 一是不能等 LOAD/NETWORKIDLE —— Boss 是 SPA + WebSocket 长连接，这两个状态可能永远不到；
     * 二是必须显式给超时 —— 不给超时就是无限期挂起，而"卡住"不是异常，
     * 外面包 try/catch 完全没用（实测把线程挂了两分钟以上还在等）。
     */
    private void waitForPageSettled() {
        try {
            page.waitForLoadState(com.microsoft.playwright.options.LoadState.DOMCONTENTLOADED,
                    new Page.WaitForLoadStateOptions().setTimeout(LOAD_STATE_TIMEOUT));
        } catch (Exception ignore) {
            // 等不到就算了，后面的 locator 调用自己有超时
        }
        PlaywrightUtil.sleep(2);
    }

    /**
     * 判断是不是 Playwright 的"对象已失效"异常。
     * <p>
     * 页面频繁增删 frame 时，Playwright Java 在派发事件时会引用到已经销毁的对象，
     * 抛 "Object doesn't exist: frame@..."。这个异常和当时在飞的那个调用没有因果关系，
     * 连接本身仍然可用，重试即可。
     */
    private static boolean isStaleObjectError(Throwable e) {
        String message = e.getMessage();
        if (message == null) {
            return false;
        }
        // 两类都是"页面在动"导致的瞬时异常，跟调用本身没有因果关系，重试就好：
        // - Object doesn't exist: frame@/request@  事件派发时引用到已销毁的对象
        // - Execution context was destroyed         求值期间页面发生了导航
        return message.contains("Object doesn't exist")
                || message.contains("Execution context was destroyed");
    }

    /**
     * Enter one candidate set without navigation retries.
     */
    private void openJobListOnce(String keyword, String targetUrl, String cityCode) {
        openJobList(keyword, targetUrl, cityCode);
        waitForJobList();
    }

    private int countJobCards() {
        checkAccess(page);
        try { return page.locator(JOB_LIST_SELECTOR).count(); }
        catch (Exception e) { throw stop("列表解析异常：计数失败，不按零岗位处理"); }
    }

    private void waitForJobList() {
        checkAccess(page);
        try {
            page.waitForCondition(() -> {
                checkAccess(page);
                return page.locator("ul.rec-job-list, ul.job-list-box, .job-list-box, .search-job-result").count() > 0;
            }, new Page.WaitForConditionOptions().setTimeout(15_000));
            checkAccess(page);
            if (countJobCards() == 0 && page.getByText("暂无相关职位").count() == 0)
                throw stop("列表解析异常：未确认空结果");
        } catch (Exception e) {
            checkAccess(page);
            throw stop("列表解析异常：容器未出现，不重新导航");
        }
    }

    private String safeUrl() {
        try {
            return page.url();
        } catch (Exception e) {
            return "(取不到URL)";
        }
    }

    /**
     * 列表容器一个都没命中时，把页面上的候选列表结构打出来，方便修选择器。
     */
    private void dumpListPageStructure() {
        try {
            Object info = page.evaluate("""
                    () => {
                      const uls = Array.from(document.querySelectorAll('ul,div'))
                        .filter(el => el.className && typeof el.className === 'string'
                                   && /job|list|rec/i.test(el.className))
                        .slice(0, 15)
                        .map(el => el.tagName.toLowerCase() + '.' + el.className.trim().replace(/\\s+/g, '.')
                                 + ' (children=' + el.childElementCount + ')');
                      return { title: document.title, url: location.href, candidates: uls };
                    }""");
            log.warn("列表页结构快照: {}", info);
        } catch (Exception e) {
            log.warn("抓取列表页结构失败: {}", e.getMessage());
        }
    }

    /**
     * 落地后检查是否被弹到风控/验证页，日志里能直接看出是哪一步触发的。
     */
    private void warnIfSecurityCheck(String keyword) {
        checkAccess(page);
    }

    /**
     * 备注：目前Boss无法通过新标签页打开立即沟通按钮，所以只能点击更多详情，然后从更多详情里打开聊天按钮
     */
    @SneakyThrows
    private boolean resumeSubmission(String keyword, Job job) {
        if (!Boolean.TRUE.equals(config.getDebugger())) ReviewedApplication.requireSupportedOptions(config);
        checkAccess(page);
        // 若收到停止指令，直接短路返回
        if (shouldStopCallback != null && Boolean.TRUE.equals(shouldStopCallback.get())) {
            log.info("停止指令已触发，跳过投递 | 公司：{} | 岗位：{}", job.getCompanyName(), job.getJobName());
            return false;
        }
        // 调试模式：仅遍历不投递
        if (Boolean.TRUE.equals(config.getDebugger())) {
            log.info("调试模式：仅遍历岗位，不投递 | 公司：{} | 岗位：{}", job.getCompanyName(), job.getJobName());
            return false;
        }

        // Resolve text before any external interaction: authentication/model failures
        // must never open a conversation or silently fall back to an old greeting.
        String message;
        if (Boolean.TRUE.equals(config.getEnableAI())) {
            if (job.getJobInfo() == null || job.getJobInfo().isBlank()) {
                throw new IllegalStateException("岗位详情为空，已停止发送");
            }
            AiMessageDecision decision = generateAiMessage(keyword, job.getJobName(), job.getJobInfo());
            if (decision.skipCurrentJob()) {
                log.info("AI 明确拒绝，跳过当前岗位且不联系：{}", job.getJobName());
                return false;
            }
            message = decision.message();
        } else {
            message = config.getSayHi();
        }
        if (message == null || message.isBlank()) {
            throw new IllegalStateException("招呼语为空，已停止发送");
        }

        // 1. 查找"查看更多信息"按钮（必须存在且新开页）
        Locator moreInfoBtn = page.locator("a.more-job-btn");
        if (moreInfoBtn.count() == 0) {
            throw stop("岗位详情解析异常：缺少查看更多信息链接");
        }
        // 强制用js新开tab
        String href = moreInfoBtn.first().getAttribute("href");
        if (href == null || !href.startsWith("/job_detail/")) {
            throw stop("岗位详情解析异常：链接无效");
        }
        String detailUrl = "https://www.zhipin.com" + href;
        // Only one exact reviewed job/text may leave the application. Query parameters
        // are per-visit tracking tokens; approval binds the canonical job URL.
        String canonicalUrl = detailUrl.split("\\?", 2)[0];
        ReviewedApplication approval;
        try {
            approval = ReviewedApplication.verify(ReviewedApplication.APPROVAL,
                    canonicalUrl, job.getCompanyName(), job.getJobName(), message);
        } catch (Exception e) {
            throw stop("单岗审核失效或岗位/正文不匹配：已停止发送");
        }
        checkAccess(page);
        Page detailPage = page.context().newPage();
        boolean attempted = false;
        boolean confirmed = false;
        try {
        checkAccess(page);
        Response detailNavigation = detailPage.navigate(detailUrl, new Page.NavigateOptions()
                .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED).setTimeout(15_000));
        if (detailNavigation != null) observeResponse(detailNavigation);
        checkAccess(detailPage);
        PlaywrightUtil.sleep(1);

        // 3. 查找"立即沟通"按钮
        Locator chatBtn = detailPage.locator("a.btn-startchat, a.op-btn-chat");
        boolean foundChatBtn = false;
        for (int i = 0; i < 5; i++) {
            checkAccess(detailPage);
            if (shouldStopCallback != null && Boolean.TRUE.equals(shouldStopCallback.get())) {
                log.info("停止指令已触发，结束查找聊天按钮 | 公司：{} | 岗位：{}", job.getCompanyName(), job.getJobName());
                try { detailPage.close(); } catch (Exception ignore) {}
                return false;
            }
            if (chatBtn.count() > 0 && (chatBtn.first().textContent().contains("立即沟通"))) {
                foundChatBtn = true;
                break;
            }
            PlaywrightUtil.sleep(1);
        }
        if (!foundChatBtn) {
            throw stop("岗位详情解析异常：立即沟通按钮未出现");
        }
        checkAccess(detailPage);
        try {
            // Revalidate expiry/revocation after navigation and immediately before claim.
            approval = ReviewedApplication.verify(ReviewedApplication.APPROVAL,
                    canonicalUrl, job.getCompanyName(), job.getJobName(), message);
            approval.claim();
        } catch (Exception e) {
            throw stop("发送审核或尝试记录检查失败：停止且不自动重试");
        }
        // A pre-existing confirmation is not evidence of this attempt.
        Locator automaticConfirmation = detailPage.getByText("已向BOSS发送消息",
                new Page.GetByTextOptions().setExact(true));
        boolean hadAutomaticConfirmation = automaticConfirmation.count() > 0
                && automaticConfirmation.first().isVisible();
        attempted = true;
        checkAccess(detailPage);
        chatBtn.first().click();
        PlaywrightUtil.sleep(1);

        // 4. 等待聊天输入框
        Locator inputLocator = detailPage.locator("div#chat-input.chat-input[contenteditable='true'], textarea.input-area");
        boolean inputReady = false;
        for (int i = 0; i < 10; i++) {
            checkAccess(detailPage);
            if (shouldStopCallback != null && Boolean.TRUE.equals(shouldStopCallback.get())) {
                log.info("停止指令已触发，结束等待聊天输入框 | 公司：{} | 岗位：{}", job.getCompanyName(), job.getJobName());
                // An attempted contact remains open until its outcome is verified.
                return false;
            }
            // BOSS may send its configured greeting directly, without a chat editor.
            // Confirm the exact platform receipt + identity, save evidence, then stop
            // this single reviewed job without sending a second custom message.
            if (!hadAutomaticConfirmation && automaticConfirmation.count() == 1
                    && automaticConfirmation.first().isVisible()
                    && canonicalUrl.equals(detailPage.url().split("\\?", 2)[0])
                    && chatBtn.count() > 0
                    && chatBtn.first().textContent().contains("继续沟通")) {
                String visible = detailPage.locator("body").innerText();
                if (!visible.contains(job.getCompanyName()) || !visible.contains(job.getJobName()))
                    throw stop("平台确认身份不匹配：停止并保留尝试记录");
                checkAccess(detailPage);
                try {
                    var evidenceDir = java.nio.file.Path.of("target", "boss-attempts");
                    var evidenceId = extractEncryptId(canonicalUrl);
                    java.nio.file.Files.createDirectories(evidenceDir);
                    detailPage.screenshot(new Page.ScreenshotOptions()
                            .setPath(evidenceDir.resolve(evidenceId + ".png")));
                    java.nio.file.Files.writeString(evidenceDir.resolve(evidenceId + ".txt"),
                            "Time: " + java.time.Instant.now() + "\nURL: " + canonicalUrl
                                    + "\nCompany: " + job.getCompanyName() + "\nJob: " + job.getJobName()
                                    + "\nType: platform_automatic_greeting\nVisible platform text:\n" + visible);
                } catch (Exception e) {
                    throw stop("平台确认留证失败：停止并保留尝试记录，不自动重试");
                }
                checkAccess(detailPage);
                resultList.add(job);
                confirmed = true;
                log.info("平台已确认首次联系（自动招呼，无附件）：{} | {}", job.getCompanyName(), job.getJobName());
                return true;
            }
            if (inputLocator.count() > 0 && inputLocator.first().isVisible()) {
                inputReady = true;
                break;
            }
            PlaywrightUtil.sleep(1);
        }
        if (!inputReady) throw stop("沟通结果未知：已保留尝试标记，停止且不自动重试");

        String sentReceipt = "已发送\n\n" + message;
        boolean hadSentReceipt = detailPage.locator("body").innerText().contains(sentReceipt);

        // 6. 输入打招呼语
        Locator input = inputLocator.first();
        checkAccess(detailPage);
        input.click();
        checkAccess(detailPage);
        Object tagObj = input.evaluate("el => el.tagName.toLowerCase()");
        if (tagObj instanceof String && ((String) tagObj).equals("textarea")) {
            input.fill(message);
        } else {
            // 对 contenteditable 节点写入文本并派发 input 事件
            input.evaluate("(el, msg) => { el.innerText = msg; el.dispatchEvent(new Event('input')); }", message);
        }

        // 7. 点击发送按钮（div.send-message 或 button.btn-send）
        Locator sendText = detailPage.locator("div.send-message, button[type='send'].btn-send, button.btn-send");
        if (sendText.count() > 0) {
            checkAccess(detailPage);
            sendText.first().click();
            checkAccess(detailPage);
            PlaywrightUtil.sleep(1);
            // Preserve the visible platform outcome for independent verification.
            // A click is only an attempt, never proof of delivery.
            try {
                String visible = detailPage.locator("body").innerText();
                // The platform may first render "发送中". Observe only; never resend.
                for (int receiptPoll = 0; receiptPoll < 20
                        && !hadSentReceipt && !visible.contains(sentReceipt); receiptPoll++) {
                    checkAccess(detailPage);
                    detailPage.waitForTimeout(500);
                    checkAccess(detailPage);
                    visible = detailPage.locator("body").innerText();
                }
                boolean freshSentReceipt = !hadSentReceipt && visible.contains(sentReceipt)
                        && canonicalUrl.equals(detailPage.url().split("\\?", 2)[0])
                        && visible.contains(job.getCompanyName()) && visible.contains(job.getJobName());
                String evidenceId = extractEncryptId(detailUrl);
                java.nio.file.Path evidenceDir = java.nio.file.Path.of("target", "boss-attempts");
                java.nio.file.Files.createDirectories(evidenceDir);
                detailPage.screenshot(new Page.ScreenshotOptions()
                        .setPath(evidenceDir.resolve(evidenceId + ".png")));
                java.nio.file.Files.writeString(evidenceDir.resolve(evidenceId + ".txt"),
                        "Time: " + java.time.Instant.now() + "\nURL: " + canonicalUrl
                                + "\nCompany: " + job.getCompanyName() + "\nJob: " + job.getJobName()
                                + "\nVisible platform text:\n" + visible);
                checkAccess(detailPage);
                if (freshSentReceipt) {
                    resultList.add(job);
                    confirmed = true;
                    log.info("平台已确认首次联系（审核正文，无附件）：{} | {}", job.getCompanyName(), job.getJobName());
                    return true;
                }
            } catch (Exception e) {
                log.warn("平台结果留证失败，发送结果仍待核验");
            }
        } else {
            log.warn("未找到发送按钮，发送结果仍待核验：{}", job.getJobName());
        }

        // No authoritative platform receipt exists here. Never increment success or
        // overwrite durable history with failure for an ambiguous attempt.
        throw stop("发送结果待平台核验：已保留尝试标记，任务停止且不自动重试");
        } finally {
            // Close only this detail tab after durable platform proof; retain ambiguous attempts.
            if (!attempted || confirmed) {
                try { detailPage.close(); } catch (Exception ignored) { }
            }
        }
    }


    /**
     * 注册页面响应监听：拦截 /wapi/zpgeek/job/detail.json 请求并解析写库
     */
    private void attachJobDetailResponseListener() {
        if (page == null) return;
        page.onResponse(resp -> {
            try {
                String url = resp.url();
                if (url == null) return;
                // 仅处理 Boss 岗位详情接口（GET）
                if (url.contains("/wapi/zpgeek/job/detail.json") &&
                        "GET".equalsIgnoreCase(resp.request().method())) {
                    String body = null;
                    try {
                        body = resp.text();
                    } catch (Throwable ignore) {
                        // 某些情况下可能拿不到文本，忽略
                    }
                    if (body == null || body.isEmpty()) return;

                    // 保存原始 JSON 到 target/job.txt
                    appendRawJson(body);

                    // 仅记录映射与原始 JSON；入库逻辑已移动到点击卡片时
                    JSONObject root = new JSONObject(body);
                    JSONObject zpData = root.optJSONObject("zpData");
                    if (zpData == null) return;
                    JSONObject jobInfo = zpData.optJSONObject("jobInfo");
                    if (jobInfo == null) return;
                    String encryptId = jobInfo.optString("encryptId", null);
                    String encryptUserId = jobInfo.optString("encryptUserId", null);
                    if (encryptId != null && encryptUserId != null) {
                        encryptIdToUserId.put(encryptId, encryptUserId);
                    }
                }
            } catch (Throwable e) {
                log.debug("监听岗位详情响应处理异常：{}", e.getMessage());
            }
        });
    }


    /**
     * 追加保存原始 JSON 到 target/job.txt
     */
    private void appendRawJson(String body) {
        try {
            java.io.File dir = new java.io.File("target");
            if (!dir.exists()) dir.mkdirs();
            java.io.File file = new java.io.File(dir, "job.txt");
            try (java.io.FileWriter fw = new java.io.FileWriter(file, true)) {
                fw.write(body);
                fw.write(System.lineSeparator());
                fw.write("\n");
            }
        } catch (Exception e) {
            log.debug("写入 target/job.txt 失败：{}", e.getMessage());
        }
    }

    /**
     * 从详情页 URL 中提取 encrypt_id
     */
    private String extractEncryptId(String detailUrl) {
        try {
            if (detailUrl == null) return null;
            String key = "/job_detail/";
            int idx = detailUrl.indexOf(key);
            if (idx < 0) return null;
            int start = idx + key.length();
            int end = detailUrl.indexOf(".html", start);
            if (end < 0) end = detailUrl.length();
            return detailUrl.substring(start, end);
        } catch (Exception e) {
            return null;
        }
    }

    public boolean isValidString(String str) {
        return str != null && !str.isEmpty();
    }

    private boolean sendImageResume(Page page) {
        try {
            Optional<java.nio.file.Path> resumeImage = bossResumeImageService.getCurrentImagePath();
            if (resumeImage.isEmpty()) {
                log.warn("未上传图片简历，跳过图片发送；请先在 Boss 管理页面上传");
                return false;
            }
            java.nio.file.Path imagePath = resumeImage.get();

            Locator imageInput = prepareImageUploadInput(page);
            if (imageInput == null) {
                log.error("发送图片简历失败：当前聊天层和重新进入的完整聊天页中均未找到图片上传控件，页面：{}",
                        safePageUrl(page));
                return false;
            }

            // 必须在完整聊天页就绪后再记录基线；否则重新进入聊天时加载出的历史图片会被误判为本次发送。
            JSONObject beforeState = readOwnImageMessageState(page);
            int imageMessageCountBefore = beforeState.optInt("imageCount", 0);

            imageInput.setInputFiles(imagePath);
            log.info("已选择图片简历，等待 Boss 确认发送：{}", imagePath.getFileName());

            // 必须等到聊天记录中出现新的本人图片消息，并且上传/发送状态已经结束。
            // 这一步完成前不能关闭详情页，否则大图片或网络较慢时上传会被中断。
            return waitForImageMessageSent(page, imageMessageCountBefore, imagePath.getFileName().toString());
        } catch (Throwable e) {
            log.error("发送图片简历失败：{}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * 获取可用的图片上传控件。
     * <p>
     * Boss 第一次“立即沟通”有时只打开简化打招呼层，发完文字后该层不会提供图片按钮。
     * 此时关闭简化层并重新点击“继续沟通”，等待完整聊天 DOM；不等待 URL，避免导航期间
     * Patchright 持有旧 frame 而出现 Object doesn't exist: frame@...。
     */
    private Locator prepareImageUploadInput(Page page) {
        Locator currentInput = waitForImageUploadInput(page, 2);
        if (currentInput != null) {
            log.info("当前聊天层已找到图片上传控件");
            return currentInput;
        }

        log.info("当前打招呼层没有图片上传控件，尝试重新进入完整聊天页面");
        closeLightweightChatPanel(page);

        Locator chatButton = findVisibleChatButton(page);
        if (chatButton == null) {
            log.warn("重新进入完整聊天失败：未找到【继续沟通/立即沟通】按钮，页面：{}", safePageUrl(page));
            return null;
        }

        try {
            String buttonText = Optional.ofNullable(chatButton.textContent()).orElse("").trim();
            chatButton.click(new Locator.ClickOptions().setTimeout(10_000));
            log.info("已点击【{}】，等待完整聊天页面的图片控件", buttonText.isEmpty() ? "继续沟通" : buttonText);
        } catch (Throwable clickError) {
            // 点击可能已经触发导航，只是旧 frame 在回包时被销毁；继续通过新 Locator 探测 DOM。
            log.debug("点击沟通按钮时页面发生切换，将继续检查新聊天页面：{}", clickError.getMessage());
        }

        Locator reopenedInput = waitForImageUploadInput(page, 15);
        if (reopenedInput != null) {
            log.info("重新进入完整聊天页面后已找到图片上传控件");
        }
        return reopenedInput;
    }

    private void closeLightweightChatPanel(Page page) {
        try {
            Locator closeButtons = page.locator("i.icon-close");
            int count = closeButtons.count();
            for (int i = 0; i < count; i++) {
                Locator closeButton = closeButtons.nth(i);
                if (closeButton.isVisible()) {
                    closeButton.click(new Locator.ClickOptions().setTimeout(5_000));
                    PlaywrightUtil.sleep(1);
                    log.debug("已关闭不含图片控件的简化打招呼层");
                    return;
                }
            }
        } catch (Throwable closeError) {
            log.debug("关闭简化打招呼层失败，将直接尝试重新进入聊天：{}", closeError.getMessage());
        }
    }

    private Locator findVisibleChatButton(Page page) {
        try {
            Locator buttons = page.locator("a.btn-startchat, a.op-btn-chat");
            int count = buttons.count();
            for (int i = 0; i < count; i++) {
                Locator button = buttons.nth(i);
                if (button.isVisible()) {
                    return button;
                }
            }
        } catch (Throwable findError) {
            log.debug("查找沟通按钮失败：{}", findError.getMessage());
        }
        return null;
    }

    /** 每轮都重新创建 Locator，以适应点击沟通按钮后发生的主 frame 替换。 */
    private Locator waitForImageUploadInput(Page page, int timeoutSeconds) {
        for (int second = 0; second < timeoutSeconds; second++) {
            if (page.isClosed()) {
                return null;
            }
            Locator input = findAttachedImageUploadInput(page);
            if (input != null) {
                return input;
            }
            PlaywrightUtil.sleep(1);
        }
        return null;
    }

    private Locator findAttachedImageUploadInput(Page page) {
        List<String> selectors = List.of(
                "div.btn-sendimg[aria-label='发送图片'] input[type='file']",
                "div[aria-label='发送图片'].btn-sendimg input[type='file']",
                "[aria-label='发送图片'] input[type='file']",
                "input[type='file'][accept*='image/gif'][accept*='image/jpeg']"
        );

        try {
            // 完整聊天页在不同版本中可能位于主 frame 或子 frame，因此逐个检查。
            for (Frame frame : page.frames()) {
                if (frame.isDetached()) {
                    continue;
                }
                for (String selector : selectors) {
                    try {
                        Locator candidate = frame.locator(selector).first();
                        if (candidate.count() > 0) {
                            // 文件输入框通常隐藏，只需确认已挂载，不能要求 visible。
                            candidate.waitFor(new Locator.WaitForOptions()
                                    .setState(WaitForSelectorState.ATTACHED)
                                    .setTimeout(1_000));
                            return candidate;
                        }
                    } catch (Throwable staleFrame) {
                        // 页面切换中的旧 frame 会失效，下一轮会重新获取 page.frames()。
                    }
                }
            }
        } catch (Throwable frameListError) {
            log.debug("检查图片上传控件时页面正在切换：{}", frameListError.getMessage());
        }
        return null;
    }

    private String safePageUrl(Page page) {
        try {
            return page.url();
        } catch (Throwable ignore) {
            return "无法读取";
        }
    }

    /**
     * 等待图片真正进入本人消息列表，而不是仅确认文件已选择。
     */
    private boolean waitForImageMessageSent(Page page, int imageMessageCountBefore, String filename) {
        JSONObject lastState = null;
        int stableSeconds = 0;
        for (int second = 1; second <= IMAGE_SEND_CONFIRM_TIMEOUT_SECONDS; second++) {
            if (page.isClosed()) {
                log.error("图片简历发送确认失败：聊天页面已关闭，文件：{}", filename);
                return false;
            }

            try {
                lastState = readOwnImageMessageState(page);
                int currentCount = lastState.optInt("imageCount", 0);
                boolean failed = lastState.optBoolean("failed", false);
                boolean sending = lastState.optBoolean("sending", false);
                String status = lastState.optString("status", "");

                if (currentCount > imageMessageCountBefore && failed) {
                    log.error("图片简历发送失败，聊天消息显示失败状态，文件：{}，状态：{}", filename, status);
                    return false;
                }
                if (currentCount > imageMessageCountBefore && !sending) {
                    stableSeconds++;
                    // 新图片消息连续稳定三秒后再关闭页面，避免刚插入的临时消息仍在后台上传。
                    if (stableSeconds >= 3) {
                        log.info("图片简历已出现在本人聊天记录中，确认发送完成，文件：{}，状态：{}",
                                filename, status);
                        return true;
                    }
                } else {
                    stableSeconds = 0;
                }
            } catch (Throwable probeError) {
                stableSeconds = 0;
                log.debug("等待图片简历发送确认时读取聊天状态失败（第{}秒）：{}", second, probeError.getMessage());
            }
            PlaywrightUtil.sleep(1);
        }

        log.error("图片简历已选择，但{}秒内没有确认出现在本人聊天记录中，按发送失败处理。文件：{}，最后状态：{}",
                IMAGE_SEND_CONFIRM_TIMEOUT_SECONDS,
                filename,
                lastState != null ? lastState.toString() : "无法读取");
        return false;
    }

    /**
     * 读取 Boss 聊天区本人图片消息的数量及最后一条图片消息状态。
     * item-myself 是本人消息，item-friend 是招聘者消息；只检查 message-content，避免把头像误判为图片消息。
     */
    private JSONObject readOwnImageMessageState(Page page) {
        String probeScript = """
                () => {
                  const root = document.querySelector('.chat-message .im-list')
                    || document.querySelector('.chat-record');
                  if (!root) {
                    return JSON.stringify({ imageCount: 0, sending: false, failed: false, status: '', rootFound: false });
                  }

                  const ownMessages = Array.from(root.querySelectorAll('li.message-item.item-myself'));
                  const imageMessages = ownMessages.filter(item => {
                    const content = item.querySelector('.message-content');
                    if (!content) return false;
                    return Boolean(content.querySelector(
                      'img, [class*="image-content"], [class*="message-image"], [class*="message-img"], ' +
                      '[class*="picture"], [style*="background-image"]'
                    ));
                  });

                  const last = imageMessages.length > 0 ? imageMessages[imageMessages.length - 1] : null;
                  if (!last) {
                    return JSON.stringify({ imageCount: 0, sending: false, failed: false, status: '', rootFound: true });
                  }

                  const statusEl = last.querySelector('.message-status');
                  const status = statusEl ? (statusEl.textContent || '').trim() : '';
                  const classText = [last, ...last.querySelectorAll('*')]
                    .map(el => typeof el.className === 'string' ? el.className : '')
                    .join(' ')
                    .toLowerCase();
                  const messageText = (last.textContent || '').trim();
                  const sending = /uploading|sending|upload-progress|send-progress/.test(classText)
                    || /上传中|发送中/.test(messageText + status);
                  const failed = /upload-fail|upload-error|send-fail|send-error|message-fail/.test(classText)
                    || /上传失败|发送失败|重新发送|点击重试/.test(messageText + status);

                  return JSON.stringify({
                    imageCount: imageMessages.length,
                    sending,
                    failed,
                    status,
                    rootFound: true
                  });
                }
                """;

        try {
            for (Frame frame : page.frames()) {
                if (frame.isDetached()) {
                    continue;
                }
                try {
                    Object stateJson = frame.evaluate(probeScript);
                    JSONObject state = new JSONObject(String.valueOf(stateJson));
                    if (state.optBoolean("rootFound", false)) {
                        return state;
                    }
                } catch (Throwable staleFrame) {
                    // frame 正在替换时忽略，本轮按未找到聊天记录处理。
                }
            }
        } catch (Throwable frameListError) {
            log.debug("读取聊天图片状态时页面正在切换：{}", frameListError.getMessage());
        }
        return new JSONObject()
                .put("imageCount", 0)
                .put("sending", false)
                .put("failed", false)
                .put("status", "")
                .put("rootFound", false);
    }

    /**
     * 检查岗位薪资是否符合预期
     *
     * @return boolean
     * true 不符合预期
     * false 符合预期
     * 期望的最低薪资如果比岗位最高薪资还小，则不符合（薪资给的太少）
     * 期望的最高薪资如果比岗位最低薪资还小，则不符合(要求太高满足不了)
     */
    private boolean isSalaryNotExpected(String salary) {
        try {
            // 1. 如果没有期望薪资范围，直接返回 false，表示"薪资并非不符合预期"
            List<Integer> expectedSalary = config.getExpectedSalary();
            if (!hasExpectedSalary(expectedSalary)) {
                return false;
            }

            // 2. 清理薪资文本（比如去掉 "·15薪"）
            salary = removeYearBonusText(salary);

            // 3. 如果薪资格式不符合预期（如缺少 "K" / "k"），直接返回 true，表示"薪资不符合预期"
            if (!isSalaryInExpectedFormat(salary)) {
                return true;
            }

            // 4. 进一步清理薪资文本，比如去除 "K"、"k"、"·" 等
            salary = cleanSalaryText(salary);

            // 5. 判断是 "月薪" 还是 "日薪"
            String jobType = detectJobType(salary);
            salary = removeDayUnitIfNeeded(salary); // 如果是按天，则去除 "元/天"

            // 6. 解析薪资范围并检查是否超出预期
            Integer[] jobSalaryRange = parseSalaryRange(salary);
            return isSalaryOutOfRange(jobSalaryRange,
                    getMinimumSalary(expectedSalary),
                    getMaximumSalary(expectedSalary),
                    jobType);

        } catch (Exception e) {
            log.error("岗位薪资获取异常！薪资文本【{}】,异常信息【{}】", salary, e.getMessage(), e);
            // 出错时，您可根据业务需求决定返回 true 或 false
            // 这里假设出错时无法判断，视为不满足预期 => 返回 true
            return true;
        }
    }

    /**
     * 是否存在有效的期望薪资范围
     */
    private boolean hasExpectedSalary(List<Integer> expectedSalary) {
        return expectedSalary != null && !expectedSalary.isEmpty();
    }

    /**
     * 去掉年终奖信息，如 "·15薪"、"·13薪"。
     */
    private String removeYearBonusText(String salary) {
        if (salary.contains("薪")) {
            // 使用正则去除 "·任意数字薪"
            return salary.replaceAll("·\\d+薪", "");
        }
        return salary;
    }

    /**
     * 判断是否是按天计薪，如发现 "元/天" 则认为是日薪
     */
    private String detectJobType(String salary) {
        if (salary.contains("元/天")) {
            return "day";
        }
        return "mouth";
    }

    /**
     * 如果是日薪，则去除 "元/天"
     */
    private String removeDayUnitIfNeeded(String salary) {
        if (salary.contains("元/天")) {
            return salary.replaceAll("元/天", "");
        }
        return salary;
    }

    private Integer getMinimumSalary(List<Integer> expectedSalary) {
        return expectedSalary != null && !expectedSalary.isEmpty() ? expectedSalary.get(0) : null;
    }

    private Integer getMaximumSalary(List<Integer> expectedSalary) {
        return expectedSalary != null && expectedSalary.size() > 1 ? expectedSalary.get(1) : null;
    }

    private boolean isSalaryInExpectedFormat(String salaryText) {
        return salaryText.contains("K") || salaryText.contains("k") || salaryText.contains("元/天");
    }

    private String cleanSalaryText(String salaryText) {
        salaryText = salaryText.replace("K", "").replace("k", "");
        int dotIndex = salaryText.indexOf('·');
        if (dotIndex != -1) {
            salaryText = salaryText.substring(0, dotIndex);
        }
        return salaryText;
    }

    private boolean isSalaryOutOfRange(Integer[] jobSalary, Integer miniSalary, Integer maxSalary,
                                       String jobType) {
        if (jobSalary == null) {
            return true;
        }
        if (miniSalary == null) {
            return false;
        }
        if (Objects.equals("day", jobType)) {
            // 期望薪资转为平均每日的工资
            maxSalary = BigDecimal.valueOf(maxSalary).multiply(BigDecimal.valueOf(1000))
                    .divide(BigDecimal.valueOf(21.75), 0, RoundingMode.HALF_UP).intValue();
            miniSalary = BigDecimal.valueOf(miniSalary).multiply(BigDecimal.valueOf(1000))
                    .divide(BigDecimal.valueOf(21.75), 0, RoundingMode.HALF_UP).intValue();
        }
        // 如果职位薪资下限低于期望的最低薪资，返回不符合
        if (jobSalary[1] < miniSalary) {
            return true;
        }
        // 如果职位薪资上限高于期望的最高薪资，返回不符合
        return maxSalary != null && jobSalary[0] > maxSalary;
    }

    public boolean containsDeadStatus(String activeTimeText, List<String> deadStatus) {
        for (String status : deadStatus) {
            if (activeTimeText.contains(status)) {
                return true;// 一旦找到包含的值，立即返回 true
            }
        }
        return false;// 如果没有找到，返回 false
    }

    /** AI 生成结果：skipCurrentJob=true 表示只跳过当前岗位，不影响同企业的其他岗位。 */
    private record AiMessageDecision(boolean skipCurrentJob, String message) {}

    private AiMessageDecision generateAiMessage(String keyword, String jobName, String jd) {
        AiEntity aiConfig = aiService.getAiConfig();
        String introduce = (aiConfig != null && aiConfig.getIntroduce() != null) ? aiConfig.getIntroduce() : "";
        String prompt = (aiConfig != null) ? aiConfig.getPrompt() : null;

        String requestMessage = (prompt != null)
                ? String.format(prompt, introduce, keyword, jobName, jd, config.getSayHi())
                : buildDefaultPrompt(introduce, keyword, jobName, jd);

        try {
            String result = aiService.sendRequest(requestMessage);
            if (result == null || result.isBlank()) {
                throw new IllegalStateException("AI未返回有效招呼语，已停止发送");
            }
            if (isAiRejectResponse(result)) return new AiMessageDecision(true, null);
            return new AiMessageDecision(false, result);
        } catch (Exception e) {
            log.warn("AI请求失败，已停止发送；未使用备用招呼语");
            throw new IllegalStateException("AI请求失败，请检查认证、模型权限或额度；未发送", e);
        }
    }

    /**
     * 只把独立的 false（允许引号、反引号和常见句末标点）识别为拒绝，
     * 避免正常招呼语中偶然出现 false 字样时误跳过企业。
     */
    private boolean isAiRejectResponse(String result) {
        return result != null
                && result.trim().matches("(?i)^[*`'\"\\s]*false[*`'\"。.!！\\s]*$");
    }

    private String buildDefaultPrompt(String introduce, String keyword, String jobName, String jd) {
        return "请基于以下信息生成简洁友好的中文打招呼语，不超过60字：\n" +
                "个人介绍：" + introduce + "\n" +
                "关键词：" + keyword + "\n" +
                "职位名称：" + jobName + "\n" +
                "职位描述：" + jd + "\n" +
                "参考语：" + config.getSayHi();
    }

    private Integer[] parseSalaryRange(String salaryText) {
        try {
            return Arrays.stream(salaryText.split("-")).map(s -> s.replaceAll("[^0-9]", "")) // 去除非数字字符
                    .map(Integer::parseInt) // 转换为Integer
                    .toArray(Integer[]::new); // 转换为Integer数组
        } catch (Exception e) {
            log.error("薪资解析异常！{}", e.getMessage(), e);
        }
        return null;
    }

    /** wait_time 没配或配得不合法时用的秒数 */
    private static final int DEFAULT_WAIT_TIME_SECONDS = 10;

    /**
     * 取配置里的 wait_time（秒），非法值一律退回默认值。
     */
    private int resolveWaitTimeSeconds() {
        try {
            String raw = config == null ? null : config.getWaitTime();
            if (raw != null && !raw.isBlank()) {
                int parsed = Integer.parseInt(raw.trim());
                if (parsed > 0) {
                    return parsed;
                }
            }
        } catch (NumberFormatException ignored) {
            // 配置里塞了非数字，用默认值
        }
        return DEFAULT_WAIT_TIME_SECONDS;
    }

    /**
     * 每处理完一个岗位后的停顿。
     * <p>
     * 上一轮实测 7 分钟连刷 297 个岗位详情，直接把 Boss 风控触发了，
     * 后续关键词全部被弹到安全验证页。这里按 wait_time 降速：
     * 正常模式在 [wait_time/2, wait_time] 之间随机，避免固定节奏本身成为特征；
     * 调试模式固定用最大值 wait_time，方便观察。
     */
    private void pauseBetweenJobs() {
        int waitTime = resolveWaitTimeSeconds();
        int seconds;
        if (Boolean.TRUE.equals(config.getDebugger())) {
            seconds = waitTime;
        } else {
            int min = Math.max(1, waitTime / 2);
            seconds = min >= waitTime ? waitTime
                    : ThreadLocalRandom.current().nextInt(min, waitTime + 1);
        }
        log.debug("岗位间停顿 {} 秒（wait_time={}，debugger={}）", seconds, waitTime, config.getDebugger());
        for (int i = 0; i < seconds; i++) {
            checkAccess(page);
            page.waitForTimeout(1000); // pump response events; do not hide restrictions during sleep
        }
        checkAccess(page);
    }

    /**
     * 判断是不是 Boss 的安全验证页。
     * <p>
     * 实测密集遍历后会被弹到 /web/passport/zp/verify.html（极验滑块，页面上是
     * div.geetest_success_correct 那一套），而原来的判断只认 verify-slider，
     * 导致真正遇到验证时程序把关键词当失败跳过，人也不知道要去过验证。
     */
    private static boolean isSecurityVerifyUrl(String url) {
        if (url == null) {
            return false;
        }
        return url.contains("/web/user/safe/verify-slider")
                || url.contains("/web/passport/zp/verify")
                || url.contains("/web/passport/zp/security")
                || url.contains("security-check");
    }

}
