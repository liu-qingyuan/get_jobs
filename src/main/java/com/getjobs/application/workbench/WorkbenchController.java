package com.getjobs.application.workbench;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
@RequestMapping("/api/workbench")
public class WorkbenchController {
    private final WorkbenchService service;
    private final WorkbenchRunner runner;
    public WorkbenchController(WorkbenchService service, WorkbenchRunner runner) { this.service = service; this.runner = runner; }

    @GetMapping("/state") public ObjectNode state() {
        var state = service.state(); state.set("runtime", runner.runtime()); return state;
    }
    @PostMapping("/facts") public ObjectNode fact(@RequestBody ObjectNode input) { return service.saveFact(input); }
    @DeleteMapping("/facts/{id}") public Map<String, Boolean> delete(@PathVariable String id) { service.deleteFact(id); return Map.of("deleted", true); }
    @PostMapping("/import/preview") public ObjectNode preview() { return service.importPreview(); }
    @PostMapping("/offertoday/discover") public ObjectNode discoverOfferToday() { return runner.discoverOfferToday(); }
    @PostMapping(value="/resumes", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ObjectNode resume(@RequestParam Map<String,String> fields, @RequestParam(required=false) MultipartFile file) throws Exception {
        var input = service.json.createObjectNode(); fields.forEach(input::put);
        return service.saveResume(input, file == null ? null : file.getBytes(), file == null ? null : file.getOriginalFilename());
    }
    @PostMapping("/resumes/{id}/approve") public ObjectNode approveResume(@PathVariable String id) { return service.approveResume(id); }
    @GetMapping("/resumes/{id}/file") public ResponseEntity<FileSystemResource> download(@PathVariable String id) {
        var path = service.resumeFile(id);
        String extension = path.getFileName().toString().substring(path.getFileName().toString().lastIndexOf('.'));
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                .filename("resume-" + id + extension, StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.APPLICATION_OCTET_STREAM).body(new FileSystemResource(path));
    }
    @PostMapping("/tasks") public ObjectNode task(@RequestBody ObjectNode input) { return service.createTask(input); }
    @PostMapping("/tasks/{id}/approve") public ObjectNode approveTask(@PathVariable String id) { return service.approveTask(id); }
    @PostMapping("/tasks/{id}/start") public ObjectNode start(@PathVariable String id) { return runner.start(id); }
    @PostMapping("/tasks/{id}/check-status") public ObjectNode checkStatus(@PathVariable String id) { return runner.checkStatus(id); }
    @GetMapping("/tasks/{id}/context") public ObjectNode context(@PathVariable String id) { return service.context(id); }
    @PostMapping("/tasks/{id}/events") public ObjectNode event(@PathVariable String id, @RequestBody ObjectNode input) { return service.recordEvent(id, input); }
    @PostMapping("/tasks/{id}/agent-event") public ObjectNode agentEvent(@PathVariable String id, @RequestBody ObjectNode input,
            @RequestHeader(value="Authorization",required=false) String authorization) {
        String token = authorization != null && authorization.startsWith("Bearer ") ? authorization.substring(7) : null;
        return service.agentEvent(id, input, token);
    }
    @ExceptionHandler(WorkbenchException.class) public ResponseEntity<Map<String,String>> known(WorkbenchException e) {
        return ResponseEntity.status(e.status).body(Map.of("error", e.getMessage()));
    }
    @ExceptionHandler({HttpMessageNotReadableException.class, IllegalArgumentException.class, MaxUploadSizeExceededException.class})
    public ResponseEntity<Map<String,String>> invalid(Exception e) { return ResponseEntity.badRequest().body(Map.of("error", "请求格式或附件大小无效")); }
    @ExceptionHandler(Exception.class) public ResponseEntity<Map<String,String>> unavailable(Exception e) {
        return ResponseEntity.status(503).body(Map.of("error", "工作台暂不可用，请检查本机存储或执行器；未确认的结果不会记为成功"));
    }
}
