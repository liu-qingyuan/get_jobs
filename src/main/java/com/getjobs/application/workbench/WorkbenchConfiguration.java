package com.getjobs.application.workbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
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
        return new WorkbenchRunner(service, arguments(json, command), offerToday, "http://127.0.0.1:" + port + "/api/workbench", timeout);
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
