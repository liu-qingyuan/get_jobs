package com.getjobs.application.workbench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.getjobs.worker.boss.BossCandidateReader;
import java.net.URI;
import java.sql.Connection;
import java.time.Instant;
import java.util.*;
import static com.getjobs.application.workbench.WorkbenchService.*;

/** One user-confirmed scope. Policy decisions never approve a legacy task, acquire a claim, or execute an action. */
final class WorkbenchScopePolicy {
    private static final Set<String> ACTIONS = Set.of("DISCOVER", "MATCH", "PREPARE", "APPLY", "CONTACT", "SEND_CV", "PROJECT_EXECUTE");
    private static final Set<String> OUTBOUND = Set.of("APPLY", "CONTACT", "SEND_CV");
    private static final List<String> METERS = List.of("candidates", "modelTokens", "seconds", "outbound");
    private final WorkbenchService service;
    private final WorkbenchStore store;
    WorkbenchScopePolicy(WorkbenchService service) { this.service = service; this.store = service.store; }
    private ObjectNode object() { return service.json.createObjectNode(); }
    private ArrayNode array() { return service.json.createArrayNode(); }

    ObjectNode save(ObjectNode input) {
        return store.write(c -> {
            var previous = latest(c);
            require(previous == null ? input.path("expectedVersion").asInt(0) == 0 : input.path("expectedVersion").asInt(-1) == previous.path("version").asInt(), "范围版本已变化，请刷新后确认");
            var scope = object();
            scope.set("channels", strings(input, "channels", Set.of("OFFICIAL", "OFFERTODAY", "BOSS", "JOBSDB"), true));
            scope.set("regions", strings(input, "regions", Set.of("HK", "MAINLAND"), true));
            scope.set("languages", strings(input, "languages", Set.of("zh", "en"), true));
            scope.set("directions", strings(input, "directions", Set.of("AI_APPLICATION", "ML", "EMBODIED"), true));
            var hosts = strings(input, "hosts", null, true);
            for (var host : hosts) if (!host.asText().matches("[a-z0-9]+(?:[a-z0-9.-]*[a-z0-9])?") || !host.asText().contains("."))
                throw new WorkbenchException(400, "来源须为小写精确域名，不接受通配符、路径或端口");
            scope.set("hosts", hosts);
            scope.set("includeTerms", strings(input, "includeTerms", null, false));
            scope.set("excludeTerms", strings(input, "excludeTerms", null, false));
            scope.set("factIds", strings(input, "factIds", null, false));
            scope.set("resumeIds", strings(input, "resumeIds", null, false));
            scope.set("transformations", strings(input, "transformations", Set.of("ORIGINAL", "REORDER", "REPHRASE"), true));
            scope.set("actions", input.has("actions") ? strings(input, "actions", ACTIONS, true) : array().add("DISCOVER").add("MATCH").add("PREPARE"));
            scope.set("projectIds", strings(input, "projectIds", null, false));
            scope.set("projectResources", strings(input, "projectResources", Set.of("LOCAL_FILES", "LOCAL_PROCESS", "MODEL"), false));
            if (contains(scope.path("actions"), "PROJECT_EXECUTE")) {
                require(!scope.path("projectIds").isEmpty() && contains(scope.path("projectResources"), "LOCAL_FILES") && contains(scope.path("projectResources"), "LOCAL_PROCESS"), "项目执行须明确选择项目及本地文件/进程资源；默认无网络、账号或外发许可");
                for (var projectId : scope.path("projectIds")) service.projects.directory(store.get(c, "project", projectId.asText()));
            }
            var budgets = object();
            for (String meter : METERS) budgets.put(meter, number(input.path("budgets").path(meter), meter, 0));
            scope.set("budgets", budgets);
            String expires = text(input, "expiresAt", 60, true);
            try {
                Instant expiry = Instant.parse(expires);
                if (!expiry.isAfter(Instant.now()) || expiry.isAfter(Instant.now().plusSeconds(90L * 86400))) throw new IllegalArgumentException();
            } catch (Exception e) { throw new WorkbenchException(400, "范围有效期须在未来 90 天内"); }
            scope.put("expiresAt", expires);
            scope.set("materials", service.materials.scopeSelection(c, scope));
            scope.set("contactFactIds", strings(input, "contactFactIds", null, false));
            require(!input.has("contactVerified") || input.path("contactVerified").isBoolean(), "contactVerified 须为显式核对标记");
            scope.put("contactVerified", input.path("contactVerified").asBoolean(false));
            if (scope.path("contactVerified").asBoolean()) {
                require((contains(scope.path("channels"), "OFFERTODAY") || contains(scope.path("channels"), "JOBSDB")) && !scope.path("contactFactIds").isEmpty(), "请明确选定本范围的港区渠道联系方式事实再核对");
                for (var contactId : scope.path("contactFactIds")) {
                    boolean applicable = false;
                    for (var fact : scope.path("materials").path("facts")) if (fact.path("id").equals(contactId)
                            && (contains(scope.path("channels"), "OFFERTODAY") && WorkbenchMaterials.applicable(fact.path("scope").asText(), "HK", "www.offertoday.com")
                            || contains(scope.path("channels"), "JOBSDB") && WorkbenchMaterials.applicable(fact.path("scope").asText(), "HK", "hk.jobsdb.com")) && contactValue(fact)) applicable = true;
                    require(applicable, "联系方式须为范围内适用的已确认邮箱/电话字段及可识别完整值，经历事实不能冒充联系方式");
                }
            }
            scope.put("version", previous == null ? 1 : previous.path("version").asInt() + 1);
            scope.put("id", Integer.toString(scope.path("version").asInt())).put("status", "ACTIVE").put("source", "USER_SCOPE").put("createdAt", now());
            store.save(c, "scope_version", scope);
            return scope;
        });
    }

    ObjectNode change(int version, String operation) {
        return store.write(c -> {
            var previous = latest(c);
            require(previous != null && previous.path("version").asInt() == version, "范围版本已变化，请刷新后操作");
            String status = previous.path("status").asText();
            String next = switch (operation) {
                case "pause" -> { require(status.equals("ACTIVE"), "只有当前活动范围可暂停"); yield "PAUSED"; }
                case "resume" -> {
                    require(status.equals("PAUSED") && Instant.parse(previous.path("expiresAt").asText()).isAfter(Instant.now()), "仅未到期的暂停范围可恢复");
                    require(service.materials.scopeSelection(c, previous).equals(previous.path("materials")), "材料版本已变化，请重新设置范围");
                    yield "ACTIVE";
                }
                case "revoke" -> { require(!status.equals("REVOKED"), "范围已经撤销"); yield "REVOKED"; }
                default -> throw new WorkbenchException(400, "无效的范围操作");
            };
            var scope = previous.deepCopy().put("id", Integer.toString(version + 1)).put("version", version + 1)
                    .put("status", next).put("change", operation).put("createdAt", now());
            store.save(c, "scope_version", scope); return scope;
        });
    }

    ObjectNode workspace() { return store.write(this::workspace); }
    ObjectNode workspace(Connection c) throws Exception {
        var result = object(); var history = store.list(c, "scope_version");
        result.set("history", history);
        result.set("usage", usage(c));
        result.set("authorizations", store.list(c, "scope_authorization"));
        if (!history.isEmpty()) {
            var current = ((ObjectNode) history.get(history.size() - 1)).deepCopy();
            String effective = current.path("status").asText();
            if (effective.equals("ACTIVE")) {
                if (!Instant.parse(current.path("expiresAt").asText()).isAfter(Instant.now())) effective = "EXPIRED";
                else try { if (!service.materials.scopeSelection(c, current).equals(current.path("materials"))) effective = "MATERIAL_CHANGED"; }
                catch (WorkbenchException e) { effective = "MATERIAL_CHANGED"; }
                if (effective.equals("ACTIVE")) {
                    var used = usage(c); boolean available = false;
                    for (var action : current.path("actions")) {
                        var cost = cost(action.asText(), object()); boolean fits = true;
                        for (String meter : METERS) if (cost.path(meter).asLong() > current.path("budgets").path(meter).asLong() - used.path(meter).asLong()) fits = false;
                        if (fits) available = true;
                    }
                    if (!available) effective = "EXHAUSTED";
                }
            }
            current.put("effectiveStatus", effective); result.set("current", current);
        }
        return result;
    }
    ObjectNode latest(Connection c) throws Exception {
        var all = store.list(c, "scope_version");
        return all.isEmpty() ? null : (ObjectNode) all.get(all.size() - 1);
    }

    /** Read-only explanation; untrusted target fields cannot change saved conditions or grant authority. */
    ObjectNode preview(ObjectNode input) { return store.write(c -> assess(c, input)); }
    ObjectNode authorize(ObjectNode input) {
        return store.write(c -> {
            var decision = assess(c, input);
            if (decision.path("allowed").asBoolean()) {
                var authorization = object().put("id", id()).put("source", "SCOPE_POLICY").put("scopeVersion", decision.path("scopeVersion").asInt())
                        .put("status", "FROZEN").put("createdAt", now());
                var request = object().put("scopeVersion", decision.path("scopeVersion").asInt()).put("action", text(input, "action", 50, true));
                var target = object();
                for (String field : List.of("channel", "region", "language", "direction", "company", "title", "description", "resumeId", "transformation"))
                    target.put(field, input.path("target").path(field).asText().trim());
                target.put("url", url(input.path("target").path("url").asText().trim(), true));
                request.set("target", target); request.set("cost", decision.path("cost").deepCopy());
                authorization.set("request", request);
                authorization.set("materials", service.materials.scopeContext(c, latest(c), target));
                authorization.set("reasons", decision.path("reasons").deepCopy());
                store.save(c, "scope_authorization", authorization); decision.set("authorization", authorization);
            }
            return decision;
        });
    }

    /** Consumes a frozen grant once, atomically with version/material/target/budget revalidation. No side effect is performed. */
    ObjectNode consume(String id) {
        return store.write(c -> {
            var authorization = store.get(c, "scope_authorization", id);
            require(authorization.path("status").asText().equals("FROZEN"), "授权已消费，不能用于重试或下一阶段");
            var decision = assess(c, (ObjectNode) authorization.path("request"));
            if (!decision.path("allowed").asBoolean()) return decision;
            var used = usage(c);
            for (String meter : METERS) used.put(meter, used.path(meter).asLong() + authorization.path("request").path("cost").path(meter).asLong());
            store.save(c, "scope_usage", used);
            authorization.put("status", "CONSUMED").put("consumedAt", now());
            store.save(c, "scope_authorization", authorization); decision.set("authorization", authorization);
            return decision;
        });
    }

    /** Late results remain recordable after invalidation; this never grants permission for the next phase. */
    ObjectNode result(String id, ObjectNode input) {
        return store.write(c -> {
            var authorization = store.get(c, "scope_authorization", id);
            require(authorization.path("status").asText().equals("CONSUMED") && !authorization.has("result"), "只能为已消费动作追加一次结果，不能改写历史");
            var result = object().put("outcome", choice(input, "outcome", Set.of("CONTACTED", "SUCCEEDED", "UNKNOWN", "FAILED")))
                    .put("evidence", text(input, "evidence", 6000, true)).put("at", now()).put("source", "consumer");
            authorization.set("result", result); store.save(c, "scope_authorization", authorization); return authorization;
        });
    }

    private ObjectNode usage(Connection c) throws Exception {
        var rows = store.list(c, "scope_usage");
        if (!rows.isEmpty()) return (ObjectNode) rows.get(0);
        var used = object().put("id", "total"); for (String meter : METERS) used.put(meter, 0L); return used;
    }
    private ObjectNode cost(String action, JsonNode input) {
        if (!input.isMissingNode() && !input.isObject()) throw new WorkbenchException(400, "cost 须为预算计量对象");
        var cost = object();
        for (String meter : METERS) {
            long minimum = switch (meter) {
                case "outbound" -> OUTBOUND.contains(action) ? 1 : 0;
                case "seconds" -> 1;
                case "candidates" -> action.equals("DISCOVER") ? 1 : 0;
                default -> Set.of("MATCH", "PREPARE").contains(action) ? 1 : 0;
            };
            cost.put(meter, input.has(meter) ? Math.max(minimum, number(input.path(meter), meter, 0)) : minimum);
        }
        return cost;
    }

    /** Reserves the full read-only page envelope before a trusted Adapter starts. No materials or send capability leave here. */
    ObjectNode reserveDiscovery(int version, ObjectNode source, int slots, int seconds, int tokens) {
        return store.write(c -> {
            var request = discoveryRequest(version, source);
            request.putObject("cost").put("candidates", slots).put("seconds", seconds).put("modelTokens", tokens).put("outbound", 0);
            var decision = assess(c, request, true, false);
            if (decision.path("allowed").asBoolean()) {
                var used = usage(c);
                for (String meter : METERS) used.put(meter, used.path(meter).asLong() + decision.path("cost").path(meter).asLong());
                store.save(c, "scope_usage", used);
                var authorization = object().put("id", id()).put("source", "SCOPE_POLICY").put("scopeVersion", version)
                        .put("status", "CONSUMED").put("createdAt", now()).put("consumedAt", now());
                authorization.set("request", request); authorization.set("reasons", decision.path("reasons"));
                store.save(c, "scope_authorization", authorization);
                decision.put("authorizationId", authorization.path("id").asText());
            }
            return decision;
        });
    }

    /** Dedicated read-only matching reservation; never creates a reusable frozen execution grant. */
    ObjectNode matchingDecision(Connection c, int version, ObjectNode target) throws Exception {
        return assess(c, matchingRequest(version, target), false, true, target.path("lastReadDescription").asText(), true);
    }
    ObjectNode reserveMatching(int version, ObjectNode target, int seconds, int tokens) {
        return store.write(c -> {
            var request = matchingRequest(version, target);
            request.putObject("cost").put("seconds", seconds).put("modelTokens", tokens).put("candidates", 0).put("outbound", 0);
            var decision = assess(c, request, false, false, target.path("lastReadDescription").asText(), true);
            if (decision.path("allowed").asBoolean()) {
                var used = usage(c);
                for (String meter : METERS) used.put(meter, used.path(meter).asLong() + decision.path("cost").path(meter).asLong());
                store.save(c, "scope_usage", used);
                var authorization = object().put("id", id()).put("source", "SCOPE_POLICY").put("scopeVersion", version)
                        .put("status", "CONSUMED").put("createdAt", now()).put("consumedAt", now());
                authorization.set("request", request); authorization.set("reasons", decision.path("reasons"));
                store.save(c, "scope_authorization", authorization); decision.put("authorizationId", authorization.path("id").asText());
            }
            return decision;
        });
    }
    /** Project permission and cumulative accounting stay here, not in the executor or job lifecycle. */
    void projectDecision(Connection c, ObjectNode project, int version, long seconds, long tokens, boolean reserved) throws Exception {
        var scope = latest(c);
        require(scope != null && scope.path("version").asInt() == version, "项目范围版本已变化；请按当前范围显式恢复");
        require(scope.path("status").asText().equals("ACTIVE") && Instant.parse(scope.path("expiresAt").asText()).isAfter(Instant.now()), "项目范围已暂停、撤销或到期");
        require(service.materials.scopeSelection(c, scope).equals(scope.path("materials")), "事实或材料已变化，须重新确认范围");
        require(contains(scope.path("actions"), "PROJECT_EXECUTE") && contains(scope.path("projectIds"), project.path("id").asText()), "此范围未授权此具体项目");
        require(contains(scope.path("projectResources"), "LOCAL_FILES") && contains(scope.path("projectResources"), "LOCAL_PROCESS"), "项目本地资源未获许可");
        require(tokens == 0 || contains(scope.path("projectResources"), "MODEL"), "项目模型资源未获许可");
        service.projects.directory(project);
        if (!reserved) {
            var used = usage(c);
            require(seconds <= scope.path("budgets").path("seconds").asLong() - used.path("seconds").asLong()
                    && tokens <= scope.path("budgets").path("modelTokens").asLong() - used.path("modelTokens").asLong(), "项目累计预算耗尽；失败及恢复不退款、不清零");
        }
    }
    ObjectNode reserveProject(Connection c, ObjectNode project, int version, long seconds, long tokens) throws Exception {
        projectDecision(c, project, version, seconds, tokens, false);
        var used = usage(c);
        used.put("seconds", used.path("seconds").asLong() + seconds).put("modelTokens", used.path("modelTokens").asLong() + tokens);
        store.save(c, "scope_usage", used);
        var grant = object().put("id", id()).put("source", "SCOPE_POLICY").put("scopeVersion", version).put("status", "CONSUMED").put("createdAt", now());
        var request = grant.putObject("request").put("action", "PROJECT_EXECUTE").put("projectId", project.path("id").asText()).put("directory", service.projects.directory(project).toString());
        request.set("resources", latest(c).path("projectResources").deepCopy());
        request.putObject("cost").put("seconds", seconds).put("modelTokens", tokens).put("outbound", 0).put("candidates", 0);
        store.save(c, "scope_authorization", grant); return grant;
    }

    /** Shared preparation/action eligibility. Derived-file checks belong to the preparation Module; policy uses its selected baseline ID. */
    ObjectNode preparedDecision(Connection c, int version, ObjectNode target, String action, boolean reserved) throws Exception {
        var request = object().put("scopeVersion", version).put("action", action); request.set("target", target);
        return assess(c, request, false, reserved, target.path("lastReadDescription").asText());
    }
    ObjectNode executionDecision(Connection c, int version, ObjectNode target, String action, String taskId, boolean reserved) throws Exception {
        var request = object().put("scopeVersion", version).put("action", action); request.set("target", target);
        return assess(c, request, false, reserved, target.path("lastReadDescription").asText(), false, taskId);
    }

    /** Reserve the complete executor envelope even if preflight fails without a send. */
    ObjectNode reserveExecutionResources(Connection c, ObjectNode task, ObjectNode record, long seconds, long tokens) throws Exception {
        String action = task.path("channel").asText().equals("OFFERTODAY") ? "CONTACT" : "APPLY";
        var decision = executionDecision(c, record.path("scopeVersion").asInt(), (ObjectNode) record.path("target"), action, task.path("id").asText(), false);
        require(decision.path("allowed").asBoolean(), decision.path("reasons").toString());
        var used = usage(c); var scope = latest(c);
        require(seconds <= scope.path("budgets").path("seconds").asLong() - used.path("seconds").asLong()
                && tokens <= scope.path("budgets").path("modelTokens").asLong() - used.path("modelTokens").asLong(), "累计预算不足：单岗执行时间/模型上限；请补充后恢复");
        used.put("seconds", used.path("seconds").asLong() + seconds).put("modelTokens", used.path("modelTokens").asLong() + tokens);
        store.save(c, "scope_usage", used);
        var reservation = object().put("id", id()).put("taskId", task.path("id").asText()).put("scopeVersion", record.path("scopeVersion").asInt()).put("source", "SCOPE_POLICY").put("createdAt", now());
        reservation.putObject("limits").put("seconds", seconds).put("modelTokens", tokens);
        store.save(c, "scope_execution_resource", reservation); return reservation;
    }

    /** Called inside the task claim transaction, never with caller-supplied task identity. */
    ObjectNode claimExecution(Connection c, ObjectNode task, ObjectNode record, String action) throws Exception {
        var request = object().put("scopeVersion", record.path("scopeVersion").asInt()).put("action", action);
        request.set("target", record.path("target"));
        request.putObject("cost").put("outbound", 1);
        var decision = assess(c, request, false, false, record.path("target").path("lastReadDescription").asText(), false, task.path("id").asText());
        require(decision.path("allowed").asBoolean(), decision.path("reasons").toString());
        var used = usage(c);
        for (String meter : METERS) used.put(meter, used.path(meter).asLong() + decision.path("cost").path(meter).asLong());
        store.save(c, "scope_usage", used);
        var grant = object().put("id", id()).put("source", "SCOPE_POLICY").put("scopeVersion", record.path("scopeVersion").asInt())
                .put("taskId", task.path("id").asText()).put("status", "CONSUMED").put("createdAt", now()).put("consumedAt", now());
        grant.set("request", request); store.save(c, "scope_authorization", grant); return grant;
    }
    void executionResult(Connection c, String id, String outcome, String evidence) throws Exception {
        var grant = store.get(c, "scope_authorization", id);
        if (!grant.has("result")) {
            grant.set("result", object().put("outcome", outcome).put("evidence", evidence).put("at", now()).put("source", "consumer"));
            store.save(c, "scope_authorization", grant);
        }
    }
    ObjectNode reservePreparation(int version, ObjectNode target, int seconds, int tokens) {
        var request = object().put("scopeVersion", version).put("action", "PREPARE"); request.set("target", target);
        request.putObject("cost").put("seconds", seconds).put("modelTokens", tokens).put("candidates", 0).put("outbound", 0);
        var decision = authorize(request);
        return decision.path("allowed").asBoolean() ? consume(decision.path("authorization").path("id").asText()) : decision;
    }
    private ObjectNode matchingRequest(int version, ObjectNode target) {
        var request = object().put("scopeVersion", version).put("action", "MATCH");
        request.set("target", target.deepCopy().put("resumeId", "").put("transformation", "ORIGINAL")); return request;
    }

    ObjectNode discoveryCandidate(Connection c, int version, ObjectNode target, String observedDescription) throws Exception {
        var request = discoveryRequest(version, target);
        return assess(c, request, false, true, observedDescription);
    }
    private ObjectNode discoveryRequest(int version, ObjectNode target) {
        var request = object().put("scopeVersion", version).put("action", "DISCOVER");
        var copy = target.deepCopy();
        copy.put("company", target.path("company").asText("只读来源"));
        copy.put("title", target.path("title").asText("只读岗位列表"));
        copy.put("transformation", "ORIGINAL").put("resumeId", "");
        request.set("target", copy); return request;
    }
    private ObjectNode assess(Connection c, ObjectNode input) throws Exception { return assess(c, input, false, false); }
    private ObjectNode assess(Connection c, ObjectNode input, boolean sourceRead, boolean reserved) throws Exception {
        return assess(c, input, sourceRead, reserved, "");
    }
    private ObjectNode assess(Connection c, ObjectNode input, boolean sourceRead, boolean reserved, String observedDescription) throws Exception {
        return assess(c, input, sourceRead, reserved, observedDescription, false);
    }
    private ObjectNode assess(Connection c, ObjectNode input, boolean sourceRead, boolean reserved, String observedDescription, boolean currentMatch) throws Exception {
        return assess(c, input, sourceRead, reserved, observedDescription, currentMatch, "scope-target");
    }
    private ObjectNode assess(Connection c, ObjectNode input, boolean sourceRead, boolean reserved, String observedDescription, boolean currentMatch, String taskId) throws Exception {
        var decision = object().put("allowed", false).put("source", "SCOPE_POLICY");
        var reasons = decision.putArray("reasons"); var codes = decision.putArray("reasonCodes");
        var scope = latest(c);
        if (scope == null) { deny(reasons, codes, "NO_SCOPE", "尚未确认求职范围，默认不授权"); return decision; }
        decision.put("scopeVersion", scope.path("version").asInt());
        if (input.path("scopeVersion").asInt(-1) != scope.path("version").asInt()) deny(reasons, codes, "VERSION_CHANGED", "范围版本已变化，旧授权不能使用");
        if (!scope.path("status").asText().equals("ACTIVE")) deny(reasons, codes, "INACTIVE", "范围已暂停或撤销");
        if (!Instant.parse(scope.path("expiresAt").asText()).isAfter(Instant.now())) deny(reasons, codes, "EXPIRED", "范围已到期");
        String action = text(input, "action", 50, true);
        var cost = cost(action, input.path("cost"));
        // Only internal task-bound execution may use its already charged time/model envelope.
        if (OUTBOUND.contains(action)) for (var task : store.list(c, "task")) if (task.path("id").asText().equals(taskId) && task.has("executionResourceId")) cost.put("seconds", 0);
        decision.set("cost", cost);
        var used = usage(c);
        for (String meter : METERS) if (!reserved && cost.path(meter).asLong() > scope.path("budgets").path(meter).asLong() - used.path(meter).asLong())
            deny(reasons, codes, "BUDGET_EXHAUSTED", "累计预算不足：" + meter);
        if (action.equals("PROJECT_EXECUTE")) deny(reasons, codes, "PROJECT_INTERFACE_REQUIRED", "项目须经具体项目队列核对目录及资源，不可借用岗位授权");
        if (!ACTIONS.contains(action) || !contains(scope.path("actions"), action)) deny(reasons, codes, "ACTION_DENIED", "此范围未允许动作：" + action);
        if (!(input.path("target") instanceof ObjectNode target)) throw new WorkbenchException(400, "请指定单个岗位目标");
        String link = url(text(target, "url", 3000, true), true);
        var uri = URI.create(link); String host = uri.getHost().toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        if (port >= 0 && !(port == 443 && uri.getScheme().equals("https") || port == 80 && uri.getScheme().equals("http")))
            deny(reasons, codes, "SOURCE_DENIED", "范围未授权非标准来源端口");
        String channel = text(target, "channel", 50, true);
        if (JobsDbTarget.isHost(link) && !channel.equals("JOBSDB")) deny(reasons, codes, "CHANNEL_DENIED", "JobsDB 不能借用其他渠道授权或官网 AI");
        if (channel.equals("JOBSDB")) {
            try { JobsDbTarget.validate(link, sourceRead); }
            catch (WorkbenchException e) { deny(reasons, codes, "CHANNEL_DENIED", e.getMessage()); }
            if (OUTBOUND.contains(action)) deny(reasons, codes, "CAPABILITY_UNVERIFIED", JobsDbTarget.OUTBOUND_BLOCK_REASON);
        }
        if (BossCandidateReader.isHost(link) && !channel.equals("BOSS")) deny(reasons, codes, "CHANNEL_DENIED", "Boss 不能借用其他渠道授权或官网 AI");
        if (channel.equals("BOSS")) {
            try {
                if (sourceRead) BossCandidateReader.validateSource(link); else BossCandidateReader.detailUrl(link);
            } catch (IllegalArgumentException e) { deny(reasons, codes, "CHANNEL_DENIED", e.getMessage()); }
            if (!target.path("region").asText().equals("MAINLAND")) deny(reasons, codes, "CHANNEL_DENIED", "Boss 只读候选仅支持内地范围");
            if (OUTBOUND.contains(action)) deny(reasons, codes, "CAPABILITY_UNVERIFIED", BossCandidateReader.OUTBOUND_BLOCK_REASON);
        }
        if (OfferTodayTarget.isHost(link) && !channel.equals("OFFERTODAY")) deny(reasons, codes, "CHANNEL_DENIED", "OfferToday 不能借用其他渠道授权");
        if (channel.equals("OFFERTODAY")) try {
            if (sourceRead) {
                require(host.equals("www.offertoday.com") && uri.getScheme().equals("https")
                        && uri.getPath().matches("/hk/(?:search|recommend)[^/]*(?:/.*)?"), "只读来源不是受支持的 OfferToday 港区列表");
            } else OfferTodayTarget.parse(link);
        }
        catch (WorkbenchException e) { deny(reasons, codes, "CHANNEL_DENIED", e.getMessage()); }
        if (Set.of("OFFERTODAY", "JOBSDB").contains(channel) && !target.path("region").asText().equals("HK"))
            deny(reasons, codes, "CHANNEL_DENIED", "当前渠道仅支持港区");
        if (!contains(scope.path("hosts"), host)) deny(reasons, codes, "SOURCE_DENIED", "岗位不在已确认的精确来源域名内");
        for (String field : List.of("channel", "region", "language", "direction")) {
            String plural = switch (field) { case "channel" -> "channels"; case "region" -> "regions"; case "language" -> "languages"; default -> "directions"; };
            if (!contains(scope.path(plural), text(target, field, 50, true))) deny(reasons, codes, "TARGET_DENIED", "岗位条件越界：" + field);
        }
        String content = (text(target, "company", 200, true) + " " + text(target, "title", 300, true) + " " + text(target, "description", 30000, false) + " " + observedDescription).toLowerCase(Locale.ROOT);
        if (!sourceRead && !scope.path("includeTerms").isEmpty() && !matches(scope.path("includeTerms"), content)) deny(reasons, codes, "SELECTION_DENIED", "岗位未命中已确认的选择词");
        if (!sourceRead && matches(scope.path("excludeTerms"), content)) deny(reasons, codes, "EXCLUDED", "岗位命中排除项");
        String transformation = text(target, "transformation", 50, true);
        if (!sourceRead && (!reserved || action.equals("PREPARE") || OUTBOUND.contains(action)) && !currentMatch && !contains(scope.path("transformations"), transformation)) deny(reasons, codes, "TRANSFORMATION_DENIED", "材料变换未获允许；不能新增事实或跨地区/语言覆盖");
        if (!currentMatch) try {
            var selected = service.materials.scopeSelection(c, scope);
            if (!selected.equals(scope.path("materials"))) deny(reasons, codes, "MATERIAL_CHANGED", "事实或独立材料版本已变化，须重新确认范围");
            boolean resumeRequired = action.equals("PREPARE") || OUTBOUND.contains(action);
            String resumeId = text(target, "resumeId", 80, resumeRequired);
            if (resumeRequired || !resumeId.isEmpty()) {
                boolean suitable = false;
                for (var resume : selected.path("resumes")) if (service.materials.scopeResumeApplies(resume, target)) suitable = true;
                if (!suitable) deny(reasons, codes, "MATERIAL_DENIED", "所选简历不在范围内或与岗位地区/语言/独立岗位版不匹配");
            }
        } catch (WorkbenchException e) { deny(reasons, codes, "MATERIAL_CHANGED", "所选事实或材料不可用：" + e.getMessage()); }
        if (OUTBOUND.contains(action)) {
            try { service.duplicate(c, object().put("id", taskId).put("kind", "APPLICATION").put("url", link)); }
            catch (WorkbenchException e) { deny(reasons, codes, "DUPLICATE", e.getMessage()); }
            boolean contacted = false;
            for (var prior : store.list(c, "scope_authorization")) {
                if (!prior.path("status").asText().equals("CONSUMED") || !OUTBOUND.contains(prior.path("request").path("action").asText())
                        || !targetKey(prior.path("request").path("target").path("url").asText()).equals(targetKey(link))) continue;
                boolean continuation = action.equals("SEND_CV") && prior.path("request").path("action").asText().equals("CONTACT")
                        && prior.path("scopeVersion").equals(scope.path("version")) && prior.path("result").path("outcome").asText().equals("CONTACTED")
                        && List.of("channel", "region", "language", "direction", "resumeId", "transformation").stream()
                                .allMatch(field -> prior.path("request").path("target").path(field).asText().equals(target.path(field).asText().trim()));
                if (continuation) contacted = true;
                else deny(reasons, codes, "PRIOR_ACTION", "此目标已有已开始/结果未知的动作；新范围不能重投或继续旧外发阶段");
            }
            if (action.equals("SEND_CV") && !contacted) deny(reasons, codes, "CONTACT_REQUIRED", "附件阶段需要同范围、同目标及材料的明确联系证据");
        }
        decision.put("allowed", reasons.isEmpty());
        if (reasons.isEmpty()) reasons.add("符合当前范围；仅策略核验，不替代材料冻结、执行器能力或单岗 claim");
        return decision;
    }

    private static boolean contactValue(JsonNode fact) {
        String key = fact.path("key").asText(), value = fact.path("value").asText();
        return switch (key) {
            case "email", "contact_email" -> value.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+");
            case "phone", "contact_phone", "mainland_phone" -> value.matches("\\+?[0-9][0-9 ()-]{5,30}") && value.replaceAll("[^0-9]", "").length() >= 8;
            default -> false;
        };
    }

    private void deny(ArrayNode reasons, ArrayNode codes, String code, String reason) { reasons.add(reason); codes.add(code); }
    private static boolean matches(JsonNode terms, String content) { for (var term : terms) if (content.contains(term.asText().toLowerCase(Locale.ROOT))) return true; return false; }
    private static boolean contains(JsonNode values, String value) { for (var item : values) if (item.asText().equals(value)) return true; return false; }
    private ArrayNode strings(ObjectNode input, String field, Set<String> choices, boolean required) {
        var values = input.path(field);
        if (values.isMissingNode() && !required) return array();
        if (!values.isArray() || values.size() > 100 || required && values.isEmpty()) throw new WorkbenchException(400, field + " 须为有界非空数组");
        var output = array(); var seen = new HashSet<String>();
        for (var value : values) {
            if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > 200 || choices != null && !choices.contains(value.asText())) throw new WorkbenchException(400, field + " 包含无效值");
            if (seen.add(value.asText().trim())) output.add(value.asText().trim());
        }
        return output;
    }
    private static long number(JsonNode value, String field, long minimum) {
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() < minimum || value.asLong() > 1_000_000_000L) throw new WorkbenchException(400, field + " 须为有界整数");
        return value.asLong();
    }
}
