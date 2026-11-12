package com.getjobs.application.workbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

class OfferTodayRunnerTest {
    @TempDir Path root;
    final ObjectMapper json = new ObjectMapper();

    @Test void offerTodayRunsWithoutTheOfficialAiCommandAndExitIsNotDelivery() throws Exception {
        var service = new WorkbenchService(root, root.resolve("legacy"), json);
        String id = approved(service);
        Path marker = root.resolve("script-route.txt");
        String script = "import json,sys,pathlib;assert sys.argv[1]=='run';b=json.load(open(sys.argv[2]));"
                + "pathlib.Path(" + json.writeValueAsString(marker.toString()) + ").write_text(b['task']['channel'])";
        try (var runner = new WorkbenchRunner(service, List.of(), List.of("python3", "-c", script),
                "http://127.0.0.1:1/callback", 10)) {
            assertFalse(runner.runtime().path("agentConfigured").asBoolean());
            assertTrue(runner.runtime().path("offerTodayConfigured").asBoolean());
            runner.start(id);
            await().atMost(Duration.ofSeconds(10)).until(() -> !runner.runtime().path("busy").asBoolean());
            assertEquals("OFFERTODAY", Files.readString(marker));
            assertEquals("UNKNOWN", service.task(id).path("state").asText());
            assertFalse(service.task(id).path("attemptId").asText().isBlank());
            assertThrows(WorkbenchException.class, () -> runner.start(id));
        }
    }

    @Test void scriptReportsSeparateContactAndCvReceiptsThroughTheRealCallbackContract() throws Exception {
        var service = new WorkbenchService(root, root.resolve("legacy"), json);
        String id = approved(service);
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/callback", exchange -> {
            try {
                String token = exchange.getRequestHeaders().getFirst("Authorization").substring(7);
                var event = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(exchange.getRequestBody());
                byte[] response = json.writeValueAsBytes(service.agentEvent(id, event, token));
                exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response);
            } catch (Exception e) { exchange.sendResponseHeaders(409, -1); }
            finally { exchange.close(); }
        });
        server.start();
        String script = """
                import json,sys,urllib.request
                assert sys.argv[1]=='run'
                b=json.load(open(sys.argv[2]));t=b['task'];s=t['snapshot']
                opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
                common={'jobId':t['platformJobId'],'attemptId':t['attemptId'],'conversationId':'fixture-conversation','proof':'Fresh controlled fixture acknowledgement'}
                contact=dict(common,messageId='fixture-contact',initialMessageSha256=s['initialMessageSha256'])
                cv=dict(common,messageId='fixture-cv',resumeSha256=s['resume']['sha256'])
                for kind,proof in [('PROGRESS',None),('SUBMITTING',None),('CONTACTED',contact),('CV_DELIVERED',cv)]:
                    body=json.dumps({'type':kind,'message':'Fixture '+kind,'evidence':json.dumps(proof) if proof else ''}).encode()
                    request=urllib.request.Request(b['callbackUrl'],body,headers={'Content-Type':'application/json','Authorization':'Bearer '+b['token']})
                    with opener.open(request,timeout=5) as response:assert response.status==200
                """;
        try (var runner = new WorkbenchRunner(service, List.of("python3", "-c", "raise Exception('Official AI must not run')", "{taskFile}"),
                List.of("python3", "-c", script), "http://127.0.0.1:" + server.getAddress().getPort() + "/callback", 15)) {
            runner.start(id);
            await().atMost(Duration.ofSeconds(15)).until(() -> !runner.runtime().path("busy").asBoolean());
            var result = service.task(id);
            assertEquals("SUBMITTED", result.path("state").asText());
            assertTrue(result.has("contactReceipt"));
            assertTrue(result.has("cvReceipt"));
            assertTrue(result.path("events").toString().contains("CV_DELIVERED"));
            assertFalse(result.has("tokenHash"));
        } finally { server.stop(0); }
    }

    private String approved(WorkbenchService service) {
        var resume = service.saveResume(json.createObjectNode().put("label", "HK attachment")
                .put("region", "HK").put("language", "en"), "%PDF-fixture".getBytes(StandardCharsets.UTF_8), "fixture.pdf");
        service.approveResume(resume.path("id").asText());
        var task = service.createTask(json.createObjectNode().put("kind", "APPLICATION").put("channel", "OFFERTODAY")
                .put("region", "HK").put("language", "en").put("direction", "ML")
                .put("company", "Fixture").put("title", "Fixture role")
                .put("url", "https://www.offertoday.com/hk/job/AbCdEfGhIjKlMnOpQrStUv==")
                .put("resumeId", resume.path("id").asText()).put("initialMessage", "Reviewed fixture greeting")
                .put("contactVerified", true));
        service.approveTask(task.path("id").asText());
        return task.path("id").asText();
    }
}
