package com.getjobs.application.workbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

class WorkbenchRunnerTest {
    @TempDir Path root;
    final ObjectMapper json = new ObjectMapper();

    @Test void configuredExternalProcessReportsProgressClaimsSubmissionAndSuppliesReceipt() throws Exception {
        var service = new WorkbenchService(root, root.resolve("jobsdb"), json);
        String task = task(service);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/callback", exchange -> {
            try {
                String token = exchange.getRequestHeaders().getFirst("Authorization").substring(7);
                var body = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(exchange.getRequestBody());
                byte[] response = json.writeValueAsBytes(service.agentEvent(task, body, token));
                exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response);
            } catch (Exception e) { exchange.sendResponseHeaders(409, -1); }
            finally { exchange.close(); }
        });
        server.start();
        String script = """
                import json,sys,urllib.request
                b=json.load(open(sys.argv[1]))
                for kind,msg,evidence in [('PROGRESS','reading fake fixture',''),('SUBMITTING','claim before fake click',''),('SUBMITTED','fixture receipt','fixture-application-001')]:
                    payload=json.dumps({'type':kind,'message':msg,'evidence':evidence}).encode()
                    r=urllib.request.Request(b['callbackUrl'],payload,headers={'Content-Type':'application/json','Authorization':'Bearer '+b['token']})
                    urllib.request.urlopen(r,timeout=5).read()
                """;
        try (var runner = new WorkbenchRunner(service, List.of("python3", "-c", script, "{taskFile}"),
                "http://127.0.0.1:" + server.getAddress().getPort() + "/callback", 15)) {
            runner.start(task);
            await().atMost(Duration.ofSeconds(15)).until(() -> !runner.runtime().path("busy").asBoolean());
            var result = service.task(task);
            assertEquals("SUBMITTED", result.path("state").asText());
            assertFalse(result.has("tokenHash"));
            assertTrue(result.path("events").toString().contains("fixture-application-001"));
            assertTrue(result.path("events").toString().contains("\"source\":\"agent\""));
        } finally { server.stop(0); }
    }

    @Test void missingExecutorAndNormalExitNeverPretendToApply() {
        var service = new WorkbenchService(root, root.resolve("jobsdb"), json);
        String task = task(service);
        try (var unconfigured = new WorkbenchRunner(service, List.of(), "http://127.0.0.1:1/callback", 5)) {
            assertThrows(WorkbenchException.class, () -> unconfigured.start(task));
            assertEquals("APPROVED", service.task(task).path("state").asText());
        }
        try (var runner = new WorkbenchRunner(service, List.of("python3", "-c", "print('done')", "{taskFile}"), "http://127.0.0.1:1/callback", 5)) {
            runner.start(task);
            await().atMost(Duration.ofSeconds(10)).until(() -> !runner.runtime().path("busy").asBoolean());
            assertEquals("UNKNOWN", service.task(task).path("state").asText());
        }
    }

    @Test void executableFailureOccursBeforeAnyBrowserAndIsVisible() {
        var service = new WorkbenchService(root, root.resolve("jobsdb"), json);
        String task = task(service);
        try (var runner = new WorkbenchRunner(service, List.of(root.resolve("missing-executable").toString(), "{taskFile}"), "http://127.0.0.1:1/callback", 5)) {
            assertThrows(WorkbenchException.class, () -> runner.start(task));
            assertEquals("FAILED", service.task(task).path("state").asText());
            assertFalse(runner.runtime().path("busy").asBoolean());
        }
    }

    @Test void closedRunnerCannotClaimOrLaunchATask() {
        var service = new WorkbenchService(root, root.resolve("jobsdb"), json);
        String task = task(service);
        var runner = new WorkbenchRunner(service, List.of("python3", "-c", "print('no launch')", "{taskFile}"), "http://127.0.0.1:1/callback", 5);
        runner.close();
        assertThrows(WorkbenchException.class, () -> runner.start(task));
        assertEquals("APPROVED", service.task(task).path("state").asText());
    }

    @Test void secondManagerCannotRecoverOrInvalidateALiveTask() {
        var service = new WorkbenchService(root, root.resolve("jobsdb"), json);
        String task = task(service);
        try (var runner = new WorkbenchRunner(service, List.of("python3", "-c", "import time;time.sleep(30)", "{taskFile}"), "http://127.0.0.1:1/callback", 40)) {
            runner.start(task);
            assertThrows(IllegalStateException.class, () -> new WorkbenchRunner(service, List.of(), "http://127.0.0.1:1/callback", 5));
            assertEquals("RUNNING", service.task(task).path("state").asText());
        }
    }

    private String task(WorkbenchService service) {
        var resume = service.saveResume(json.createObjectNode().put("label", "Fixture resume").put("region", "HK").put("language", "en")
                .put("content", "Only fake data"), null, null);
        service.approveResume(resume.path("id").asText());
        var task = service.createTask(json.createObjectNode().put("kind", "APPLICATION").put("channel", "OFFICIAL")
                .put("region", "HK").put("language", "en").put("direction", "AI_APPLICATION").put("company", "Fixture")
                .put("title", "Test role").put("url", "https://example.invalid/jobs/runner").put("resumeId", resume.path("id").asText()));
        service.approveTask(task.path("id").asText());
        return task.path("id").asText();
    }
}
