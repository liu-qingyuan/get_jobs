package com.getjobs.application.workbench;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.*;
import static com.getjobs.application.workbench.WorkbenchService.*;

/** One bounded user intent owns discovery-to-execution orchestration and durable safe checkpoints. */
final class WorkbenchWorkflow {
    @FunctionalInterface interface SingleJob { void execute(String taskId) throws Exception; }
    private record UserWait(String key, WorkbenchCandidates.UserAction action, boolean resolved) { }
    private final WorkbenchService service;
    private final WorkbenchStore store;
    WorkbenchWorkflow(WorkbenchService service) { this.service = service; this.store = service.store; }
    private ObjectNode object() { return service.json.createObjectNode(); }

    ObjectNode begin(ObjectNode input, String cycleId) {
        return store.write(c -> {
            var scope = service.scope.latest(c);
            require(scope != null && scope.path("version").asInt() == input.path("scopeVersion").asInt(-1) && scope.path("status").asText().equals("ACTIVE"), "请先确认当前有效求职范围");
            ObjectNode cycle;
            if (cycleId == null) {
                require(input.path("sources").isArray() && !input.path("sources").isEmpty(), "请提供本轮只读来源");
                cycle = object().put("id", id()).put("createdAt", now()).put("discoveryRunId", "");
                var config = input.deepCopy().put("secondsPerJob", limit(input, "secondsPerJob", 30, 120)).put("modelTokensPerJob", limit(input, "modelTokensPerJob", 1000, 100000));
                cycle.set("input", config); cycle.putArray("entries"); cycle.putArray("events");
            } else {
                cycle = store.get(c, "workflow_cycle", cycleId);
                require(!Set.of("RUNNING", "COMPLETED", "PREPARED").contains(cycle.path("status").asText()), "本轮正在运行或已完成");
                ((ObjectNode) cycle.path("input")).put("scopeVersion", scope.path("version").asInt());
            }
            cycle.remove("pauseReason");
            cycle.put("scopeVersion", scope.path("version").asInt()).put("status", "RUNNING");
            event(cycle, "START", cycleId == null ? "一次启动发现、匹配、备料及允许的逐岗执行" : "仅恢复已知未外发断点；UNKNOWN 不重投，累计预算不重置");
            store.save(c, "workflow_cycle", cycle); return cycle;
        });
    }

    void advance(String id, WorkbenchCandidates.Reader reader, WorkbenchMatching.Analyzer analyzer, SingleJob executor) {
        var cycle = store.write(c -> store.get(c, "workflow_cycle", id));
        var waitExecutor = Executors.newVirtualThreadPerTaskExecutor();
        var completedWaits = new ExecutorCompletionService<UserWait>(waitExecutor);
        var activeWaits = new LinkedHashMap<String, WorkbenchCandidates.UserAction>();
        try {
            var input = ((ObjectNode) cycle.path("input")).deepCopy();
            var processed = new HashSet<String>();
            var awaited = new HashSet<String>();
            var resumeInteractions = new LinkedHashSet<String>();
            ObjectNode discovery = null;
            boolean exceptions = false, prepared = false;
            while (true) {
                if (!cycle.path("discoveryRunId").asText().isEmpty()) discovery = store.write(c -> store.get(c, "candidate_run", cycle.path("discoveryRunId").asText()));
                boolean waitingOnExistingAction = discovery != null && !service.candidates.pendingUserActions(discovery).isEmpty()
                        && resumeInteractions.isEmpty();
                if (discovery == null || (!discovery.path("status").asText().equals("COMPLETED") && !waitingOnExistingAction)) {
                    stage(cycle, "DISCOVER");
                    if (discovery != null) input.put("runId", discovery.path("id").asText());
                    discovery = service.candidates.discover(input, reader, id, resumeInteractions);
                    resumeInteractions.clear();
                    cycle.put("discoveryRunId", discovery.path("id").asText()); save(cycle);
                }
                var ids = store.write(c -> {
                    var selected = new ArrayList<String>();
                    for (var row : store.list(c, "candidate")) if ((row.path("runId").asText().equals(cycle.path("discoveryRunId").asText()) || contains(cycle, row.path("id").asText()))
                            && !processed.contains(row.path("id").asText())) {
                        var entry = entry(cycle, row.path("id").asText());
                        var task = entry.has("taskId") ? store.get(c, "task", entry.path("taskId").asText()) : service.findPolicyTask(c, row.path("url").asText());
                        if (task != null) {
                            entry.put("taskId", task.path("id").asText()).put("packageId", task.path("packageId").asText());
                            if (Set.of("UNKNOWN", "SUBMITTED", "RUNNING").contains(task.path("state").asText())) {
                                entry.put("status", task.path("state").asText()).put("reason", "复用此岗位的既有 task/attempt；已开始的动作不重放");
                                continue;
                            }
                        }
                        selected.add(row.path("id").asText());
                    }
                    return selected;
                });
                processed.addAll(ids);
                for (int offset = 0; offset < ids.size(); offset += 20) {
                    var batch = input.deepCopy(); var selected = batch.putArray("candidateIds");
                    ids.subList(offset, Math.min(ids.size(), offset + 20)).forEach(selected::add);
                    stage(cycle, "MATCH"); service.matching.start(batch, analyzer);
                    stage(cycle, "PREPARE"); service.preparation.prepare(batch, analyzer);
                    for (var candidateId : selected) {
                        require(!Thread.currentThread().isInterrupted(), "执行器已停止；保留安全断点");
                        var entry = entry(cycle, candidateId.asText());
                        var record = store.write(c -> {
                            ObjectNode found = null;
                            for (var row : store.list(c, "preparation")) if (row.path("candidateId").equals(candidateId) && row.path("scopeVersion").equals(cycle.path("scopeVersion"))) found = (ObjectNode) row;
                            return found;
                        });
                        if (record == null || !record.path("status").asText().equals("POLICY_APPROVED")) {
                            entry.put("status", "EXCEPTION").put("reason", record == null ? "无当前可用材料包" : record.path("reason").asText()); exceptions = true; save(cycle); continue;
                        }
                        entry.put("packageId", record.path("id").asText());
                        var scope = service.scope.workspace().path("current");
                        String action = Set.of("OFFERTODAY", "BOSS").contains(record.path("target").path("channel").asText()) ? "CONTACT" : "APPLY";
                        boolean allowedAction = false;
                        for (var allowed : scope.path("actions")) if (allowed.asText().equals(action)) allowedAction = true;
                        if (!allowedAction) {
                            entry.put("status", "PREPARED").put("reason", "仅准备结果；范围未授权此渠道外发，不创建发送任务"); prepared = true; save(cycle); continue;
                        }
                        stage(cycle, "DISPATCH");
                        try {
                            var task = service.policyTask(record, entry.path("taskId").asText(), ((ObjectNode) cycle.path("input")).deepCopy().put("cycleId", cycle.path("id").asText()));
                            entry.put("taskId", task.path("id").asText()); save(cycle);
                            executor.execute(task.path("id").asText());
                            task = service.task(task.path("id").asText());
                            entry.put("status", task.path("state").asText()).put("reason", task.path("state").asText().equals("SUBMITTED") ? "对应回执已登记，不代表雇主已读" : "保留单岗证据；未知结果不重投，已知未外发例外可处理后恢复");
                            exceptions |= !task.path("state").asText().equals("SUBMITTED");
                        } catch (Exception e) {
                            entry.put("status", "EXCEPTION").put("reason", message(e)); exceptions = true;
                            if (message(e).contains("累计预算不足")) cycle.put("pauseReason", message(e));
                        }
                        save(cycle);
                    }
                }
                var actions = service.candidates.pendingUserActions(discovery);
                if (discovery.path("status").asText().equals("COMPLETED") || (actions.isEmpty() && activeWaits.isEmpty())) break;
                var waitingChannels = new HashSet<String>();
                for (var action : activeWaits.values()) waitingChannels.add(action.channel());
                for (var action : actions) {
                    String key = action.channel() + ":" + action.interactionId() + ":" + action.actionUrl() + ":" + action.remainingSeconds();
                    if (activeWaits.containsKey(key)) continue;
                    if (waitingChannels.contains(action.channel())) {
                        event(cycle, "WAITING_USER", action.channel() + "：同渠道另一断点已可见；此断点保留到下一次安全推进");
                        continue;
                    }
                    if (!awaited.add(key)) {
                        event(cycle, "WAITING_USER", action.channel() + "：平台仍要求同一人工步骤；保留断点，不循环重试");
                        continue;
                    }
                    activeWaits.put(key, action); waitingChannels.add(action.channel());
                    completedWaits.submit(() -> new UserWait(key, action, reader.awaitUserAction(action)));
                    event(cycle, "USER_ACTION_REQUIRED", action.channel() + "：" + action.message());
                }
                if (activeWaits.isEmpty()) break;
                stage(cycle, "WAIT_USER"); save(cycle);
                UserWait completed;
                try { completed = completedWaits.take().get(); }
                catch (ExecutionException e) {
                    if (e.getCause() instanceof Exception cause) throw cause;
                    throw new RuntimeException(e.getCause());
                }
                activeWaits.remove(completed.key());
                var action = completed.action();
                if (!completed.resolved()) {
                    event(cycle, "WAITING_USER", action.channel() + "：可见用户步骤尚未完成；继续检查其他来源断点"); save(cycle);
                    continue;
                }
                event(cycle, "USER_ACTION_RESOLVED", action.channel() + "：平台步骤已解决（自动求解或用户完成），自动续跑同一轮"); save(cycle);
                resumeInteractions.add(action.interactionId());
                input.put("runId", discovery.path("id").asText());
            }
            // Previously attempted entries still participate in the result after a resume.
            for (var entry : cycle.path("entries")) if (entry.has("taskId") && !service.task(entry.path("taskId").asText()).path("state").asText().equals("SUBMITTED")) exceptions = true;
            cycle.put("status", !discovery.path("status").asText().equals("COMPLETED") ? "CHECKPOINT" : exceptions ? "EXCEPTION" : prepared ? "PREPARED" : "COMPLETED");
            if (!Set.of("COMPLETED", "PREPARED").contains(cycle.path("status").asText())) {
                store.write(c -> {
                    for (var entry : cycle.path("entries")) {
                        ObjectNode record = null;
                        if (entry.has("packageId")) record = store.get(c, "preparation", entry.path("packageId").asText());
                        if (record == null) continue;
                        var target = (ObjectNode) record.path("target");
                        String action = Set.of("OFFERTODAY", "BOSS").contains(target.path("channel").asText()) ? "CONTACT" : "APPLY";
                        if (entry.has("taskId") && store.get(c, "task", entry.path("taskId").asText()).has("contactReceipt")) action = "SEND_CV";
                        var decision = service.scope.executionDecision(c, cycle.path("scopeVersion").asInt(), target, action, entry.path("taskId").asText("preparation"), false);
                        for (var code : decision.path("reasonCodes")) if (Set.of("VERSION_CHANGED", "INACTIVE", "EXPIRED", "MATERIAL_CHANGED", "BUDGET_EXHAUSTED", "NO_SCOPE").contains(code.asText())) cycle.put("pauseReason", decision.path("reasons").toString());
                    }
                    return null;
                });
                if (cycle.has("pauseReason")) { cycle.put("status", "PAUSED"); event(cycle, "PAUSED", cycle.path("pauseReason").asText()); }
            }
            event(cycle, "STOP", "本次有界推进结束；只计对应回执，例外与累计消耗保留");
        } catch (Exception e) { cycle.put("status", "CHECKPOINT"); event(cycle, "EXCEPTION", message(e)); }
        finally {
            waitExecutor.shutdownNow();
            try { waitExecutor.awaitTermination(5, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
        save(cycle);
    }

    ObjectNode workspace(Connection c) throws Exception {
        var cycles = store.list(c, "workflow_cycle");
        for (var cycle : cycles) for (var entry : cycle.path("entries")) if (entry.has("taskId")) ((ObjectNode) entry).set("execution", service.publicTask(store.get(c, "task", entry.path("taskId").asText())));
        var result = object(); result.set("cycles", cycles); return result;
    }
    void recover(Connection c) throws Exception {
        for (var row : store.list(c, "workflow_cycle")) if (row.path("status").asText().equals("RUNNING")) {
            var cycle = (ObjectNode) row; cycle.put("status", "CHECKPOINT"); event(cycle, "INTERRUPTED", "进程中断；不自动恢复外发，保留各岗状态与预算"); store.save(c, "workflow_cycle", cycle);
        }
    }
    private boolean contains(ObjectNode cycle, String candidateId) {
        for (var entry : cycle.path("entries")) if (entry.path("candidateId").asText().equals(candidateId)) return true;
        return false;
    }
    private ObjectNode entry(ObjectNode cycle, String candidateId) {
        for (var entry : cycle.path("entries")) if (entry.path("candidateId").asText().equals(candidateId)) return (ObjectNode) entry;
        var entry = object().put("candidateId", candidateId).put("status", "PENDING"); cycle.withArray("entries").add(entry); return entry;
    }
    private void stage(ObjectNode cycle, String stage) {
        require(!Thread.currentThread().isInterrupted(), "执行器已停止；保留安全断点");
        cycle.put("stage", stage); save(cycle);
    }
    private void event(ObjectNode cycle, String code, String message) { cycle.withArray("events").add(object().put("code", code).put("message", message).put("at", now())); }
    private void save(ObjectNode cycle) { cycle.put("updatedAt", now()); store.write(c -> { store.save(c, "workflow_cycle", cycle); return null; }); }
    private static int limit(ObjectNode input, String field, int fallback, int max) {
        if (!input.has(field)) return fallback;
        var value = input.path(field); require(value.isIntegralNumber() && value.canConvertToInt() && value.asInt() > 0 && value.asInt() <= max, field + " 超出单岗执行上限"); return value.asInt();
    }
    private String message(Exception e) { return e instanceof WorkbenchException ? e.getMessage() : "推进中断；检查执行器日志，未知结果不能重投"; }
}
