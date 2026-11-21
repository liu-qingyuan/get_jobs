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

@Configuration(proxyBeanMethods=false)
public class WorkbenchConfiguration {
    @Bean WorkbenchService workbenchService(@Value("${workbench.data-dir:.workbench}") String root,
            @Value("${workbench.jobsdb-dir:.jobsdb}") String jobsdb, ObjectMapper json) {
        return new WorkbenchService(Path.of(root), Path.of(jobsdb), json);
    }
    @Bean(destroyMethod="close") WorkbenchRunner workbenchRunner(WorkbenchService service, ObjectMapper json,
            ObjectProvider<PlaywrightManager> browsers, ObjectProvider<BossJobService> bossJobs,
            @Value("${workbench.agent-command:${WORKBENCH_AGENT_COMMAND:[]}}") String command,
            @Value("${workbench.offertoday-command:${WORKBENCH_OFFERTODAY_COMMAND:}}") String offerTodayCommand,
            @Value("${server.port:9527}") int port,
            @Value("${workbench.agent-timeout-seconds:1800}") long timeout) throws Exception {
        if (timeout < 1) throw new IllegalArgumentException("执行器超时须为正数");
        List<String> offerToday;
        if (offerTodayCommand.isBlank()) {
            Path script = Path.of("scripts/offertoday/offertoday.py").toAbsolutePath().normalize();
            offerToday = Files.isRegularFile(script) ? List.of("python3", script.toString()) : List.of();
        } else offerToday = arguments(json, offerTodayCommand);
        var browser = browsers.getIfAvailable(); var legacyBoss = bossJobs.getIfAvailable();
        WorkbenchCandidates.Reader boss = browser == null || legacyBoss == null ? null : bundle -> {
            WorkbenchService.require(!legacyBoss.isRunning(), "Boss 原任务正在运行，不能同时读取；不会停止或接管旧任务");
            long started = System.nanoTime();
            return browser.callOnPlaywright(() -> {
                WorkbenchService.require(!legacyBoss.isRunning() && browser.isInitialized() && browser.isLoggedIn("boss"), "Boss 只读需要本副本已显式登录且空闲的浏览器；不会自动创建会话");
                double remaining = bundle.path("limits").path("seconds").asDouble() - (System.nanoTime() - started) / 1_000_000_000.0;
                WorkbenchService.require(remaining > 0, "Boss 等待浏览器已耗尽只读时间预算；不开始读取");
                var bounded = bundle.deepCopy(); ((ObjectNode) bounded.path("limits")).put("seconds", remaining);
                return BossCandidateReader.read(browser.getBossPage(), bounded, json);
            });
        };
        return new WorkbenchRunner(service, arguments(json, command), offerToday, boss, "http://127.0.0.1:" + port + "/api/workbench", timeout);
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
