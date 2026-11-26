package com.getjobs.application.workbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import com.getjobs.worker.boss.BossCandidateReader;
import com.getjobs.worker.manager.PlaywrightManager;
import com.getjobs.worker.service.BossJobService;
import org.springframework.context.annotation.*;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static com.getjobs.application.workbench.WorkbenchConnections.Status.*;

@Configuration(proxyBeanMethods=false)
public class WorkbenchConfiguration {
    @Bean WorkbenchService workbenchService(@Value("${workbench.data-dir:.workbench}") String root,
            @Value("${workbench.jobsdb-dir:.jobsdb}") String jobsdb, ObjectMapper json,
            @Value("${workbench.projects-root:}") String projectsRoot) {
        return new WorkbenchService(Path.of(root), Path.of(jobsdb), json, projectsRoot.isBlank() ? null : Path.of(projectsRoot));
    }
    @Bean(destroyMethod="close") WorkbenchRunner workbenchRunner(WorkbenchService service, ObjectMapper json,
            ObjectProvider<PlaywrightManager> browsers, ObjectProvider<BossJobService> bossJobs,
            @Value("${workbench.agent-command:${WORKBENCH_AGENT_COMMAND:[]}}") String command,
            @Value("${workbench.project-command:${WORKBENCH_PROJECT_COMMAND:[]}}") String projectCommand,
            @Value("${workbench.project-verifier-command:${WORKBENCH_PROJECT_VERIFIER_COMMAND:[]}}") String verifierCommand,
            @Value("${workbench.offertoday-command:${WORKBENCH_OFFERTODAY_COMMAND:}}") String offerTodayCommand,
            @Value("${workbench.jobsdb-command:${WORKBENCH_JOBSDB_COMMAND:}}") String jobsDbCommand,
            @Value("${server.port:9527}") int port,
            @Value("${workbench.agent-timeout-seconds:1800}") long timeout) throws Exception {
        if (timeout < 1) throw new IllegalArgumentException("执行器超时须为正数");
        List<String> offerToday;
        if (offerTodayCommand.isBlank()) {
            Path script = Path.of("scripts/offertoday/offertoday.py").toAbsolutePath().normalize();
            offerToday = Files.isRegularFile(script) ? List.of("python3", script.toString()) : List.of();
        } else offerToday = arguments(json, offerTodayCommand);
        List<String> jobsDb;
        boolean managedJobsDb = jobsDbCommand.isBlank();
        if (managedJobsDb) {
            Path script = Path.of("scripts/jobsdb/jobsdb.py").toAbsolutePath().normalize();
            Path python = service.jobsDbDirectory().resolve("venv/bin/python");
            jobsDb = Files.isRegularFile(script) && Files.isExecutable(python) ? List.of(python.toString(), script.toString()) : List.of();
        } else jobsDb = arguments(json, jobsDbCommand);
        var browser = browsers.getIfAvailable(); var legacyBoss = bossJobs.getIfAvailable();
        WorkbenchCandidates.Reader boss = browser == null || legacyBoss == null ? null : new WorkbenchCandidates.Reader() {
            private WorkbenchCandidates.UserActionRequired checkpoint(ObjectNode bundle, boolean preserveSeconds) {
                Integer remaining = preserveSeconds ? bundle.path("limits").path("seconds").asInt() : null;
                return new WorkbenchCandidates.UserActionRequired("boss-" + UUID.randomUUID(),
                        bundle.path("source").path("url").asText(), "Boss 要求用户在可见浏览器完成登录或安全验证", remaining);
            }
            @Override public ObjectNode read(ObjectNode bundle) throws Exception {
                WorkbenchService.require(!legacyBoss.isRunning(), "Boss 原任务正在运行，不能同时读取；不会停止或接管旧任务");
                if (!browser.callOnPlaywright(browser::isInitialized)) throw checkpoint(bundle, true);
                boolean ready = browser.callOnPlaywright(() -> BossCandidateReader.accessReady(browser.getBossPage(), bundle.path("source").path("url").asText()));
                if (!ready) throw checkpoint(bundle, true);
                long started = System.nanoTime();
                try {
                    return browser.callOnPlaywright(() -> {
                        WorkbenchService.require(!legacyBoss.isRunning() && browser.isInitialized(), "Boss 已有浏览器不可用；保留断点，不创建替代会话");
                        double remaining = bundle.path("limits").path("seconds").asDouble() - (System.nanoTime() - started) / 1_000_000_000.0;
                        WorkbenchService.require(remaining > 0, "Boss 等待浏览器已耗尽只读时间预算；不开始读取");
                        var bounded = bundle.deepCopy(); ((ObjectNode) bounded.path("limits")).put("seconds", remaining);
                        return BossCandidateReader.read(browser.getBossPage(), bounded, json);
                    });
                } catch (BossCandidateReader.UserActionRequired e) {
                    throw checkpoint(bundle, false);
                }
            }
            @Override public boolean awaitUserAction(WorkbenchCandidates.UserAction action) throws Exception {
                if (!action.channel().equals("BOSS") || legacyBoss.isRunning()) return false;
                long deadline = System.nanoTime() + 120_000_000_000L;
                if (!browser.showExistingBossPageForUserAction()) return false;
                browser.callOnPlaywright(() -> {
                    var page = browser.getBossPage();
                    WorkbenchService.require(page != null && !page.isClosed(), "Boss 可见浏览器不可用；保留断点");
                    page.bringToFront();
                    BossCandidateReader.restoreAccess(page, action.sourceUrl(), true);
                    page.bringToFront();
                    return null;
                });
                long nextReturnToSource = 0;
                while (!Thread.currentThread().isInterrupted()) {
                    // Never hold the shared slot forever: an unfinished visible step becomes CHECKPOINT,
                    // keeps the pendingRead, and can be resumed after the user completes it.
                    if (System.nanoTime() >= deadline) return false;
                    if (legacyBoss.isRunning() || !browser.callOnPlaywright(browser::isInitialized)) return false;
                    long now = System.nanoTime();
                    boolean returnToSource = now >= nextReturnToSource;
                    if (browser.callOnPlaywright(() -> BossCandidateReader.restoreAccess(browser.getBossPage(), action.sourceUrl(), returnToSource))) return true;
                    if (returnToSource) nextReturnToSource = now + 1_000_000_000L;
                    try { Thread.sleep(250); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
                }
                return false;
            }
        };
        var connections = new WorkbenchConnections(() -> {
            if (boss == null) return NOT_CONFIGURED;
            if (legacyBoss.isRunning()) return BUSY;
            var profile = WorkbenchConnections.profile(browser.profileDirectory());
            // Live page checks stay on the owning thread below the read Seam; cached logout becomes a visible user checkpoint.
            return profile != CHECK_AT_READ || browser.isLoggedIn("boss") ? profile : USER_ACTION_REQUIRED;
        }, () -> {
            if (jobsDb.isEmpty()) return NOT_CONFIGURED;
            return managedJobsDb ? WorkbenchConnections.jobsDb(service.jobsDbDirectory().resolve("scrapling-profile")) : CHECK_AT_READ;
        });
        return new WorkbenchRunner(service, arguments(json, command), offerToday, jobsDb, boss, arguments(json, projectCommand), arguments(json, verifierCommand), "http://127.0.0.1:" + port + "/api/workbench", timeout, connections);
    }

    private static List<String> arguments(ObjectMapper json, String command) throws Exception {
        var args = new ArrayList<String>();
        var parsed = json.readTree(command);
        if (parsed == null || !parsed.isArray() || parsed.size() > 40) throw new IllegalArgumentException("执行器配置须为参数数组");
        for (var value : parsed) {
            if (!value.isTextual() || value.asText().length() > 16000) throw new IllegalArgumentException("执行器参数无效");
            args.add(value.asText());
        }
        return args;
    }
}
