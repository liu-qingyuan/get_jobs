package com.getjobs.application.workbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import java.nio.file.Path;
import java.util.ArrayList;

@Configuration(proxyBeanMethods=false)
public class WorkbenchConfiguration {
    @Bean WorkbenchService workbenchService(@Value("${workbench.data-dir:.workbench}") String root,
            @Value("${workbench.jobsdb-dir:.jobsdb}") String jobsdb, ObjectMapper json) {
        return new WorkbenchService(Path.of(root), Path.of(jobsdb), json);
    }
    @Bean(destroyMethod="close") WorkbenchRunner workbenchRunner(WorkbenchService service, ObjectMapper json,
            @Value("${workbench.agent-command:${WORKBENCH_AGENT_COMMAND:[]}}") String command,
            @Value("${server.port:9527}") int port,
            @Value("${workbench.agent-timeout-seconds:1800}") long timeout) throws Exception {
        var args = new ArrayList<String>();
        var parsed = json.readTree(command);
        if (!parsed.isArray() || parsed.size() > 40 || timeout < 1) throw new IllegalArgumentException("执行器配置须为参数数组，超时须为正数");
        for (var value : parsed) {
            if (!value.isTextual() || value.asText().length() > 16000) throw new IllegalArgumentException("执行器参数无效");
            args.add(value.asText());
        }
        return new WorkbenchRunner(service, args, "http://127.0.0.1:" + port + "/api/workbench", timeout);
    }
}
