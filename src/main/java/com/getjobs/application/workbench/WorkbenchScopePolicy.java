package com.getjobs.application.workbench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.sql.Connection;
import java.time.Instant;
import java.util.*;
import static com.getjobs.application.workbench.WorkbenchService.*;

/** One user-confirmed scope. Policy decisions never approve a legacy task, acquire a claim, or execute an action. */
final class WorkbenchScopePolicy {
    private static final Set<String> ACTIONS = Set.of("DISCOVER", "MATCH", "PREPARE", "APPLY", "CONTACT", "SEND_CV");
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
    private ObjectNode latest(Connection c) throws Exception {
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

    private ObjectNode assess(Connection c, ObjectNode input) throws Exception {
        var decision = object().put("allowed", false).put("source", "SCOPE_POLICY");
        var reasons = decision.putArray("reasons"); var codes = decision.putArray("reasonCodes");
        var scope = latest(c);
        if (scope == null) { deny(reasons, codes, "NO_SCOPE", "尚未确认求职范围，默认不授权"); return decision; }
        decision.put("scopeVersion", scope.path("version").asInt());
        if (input.path("scopeVersion").asInt(-1) != scope.path("version").asInt()) deny(reasons, codes, "VERSION_CHANGED", "范围版本已变化，旧授权不能使用");
        if (!scope.path("status").asText().equals("ACTIVE")) deny(reasons, codes, "INACTIVE", "范围已暂停或撤销");
        if (!Instant.parse(scope.path("expiresAt").asText()).isAfter(Instant.now())) deny(reasons, codes, "EXPIRED", "范围已到期");
        String action = text(input, "action", 50, true);
        var cost = cost(action, input.path("cost")); decision.set("cost", cost);
        var used = usage(c);
        for (String meter : METERS) if (cost.path(meter).asLong() > scope.path("budgets").path(meter).asLong() - used.path(meter).asLong())
            deny(reasons, codes, "BUDGET_EXHAUSTED", "累计预算不足：" + meter);
        if (!ACTIONS.contains(action) || !contains(scope.path("actions"), action)) deny(reasons, codes, "ACTION_DENIED", "此范围未允许动作：" + action);
        if (!(input.path("target") instanceof ObjectNode target)) throw new WorkbenchException(400, "请指定单个岗位目标");
        String link = url(text(target, "url", 3000, true), true);
        var uri = URI.create(link); String host = uri.getHost().toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        if (port >= 0 && !(port == 443 && uri.getScheme().equals("https") || port == 80 && uri.getScheme().equals("http")))
            deny(reasons, codes, "SOURCE_DENIED", "范围未授权非标准来源端口");
        String channel = text(target, "channel", 50, true);
        if (OfferTodayTarget.isHost(link) && !channel.equals("OFFERTODAY")) deny(reasons, codes, "CHANNEL_DENIED", "OfferToday 不能借用其他渠道授权");
        if (channel.equals("OFFERTODAY")) try { OfferTodayTarget.parse(link); }
        catch (WorkbenchException e) { deny(reasons, codes, "CHANNEL_DENIED", e.getMessage()); }
        if (Set.of("OFFERTODAY", "JOBSDB").contains(channel) && !target.path("region").asText().equals("HK"))
            deny(reasons, codes, "CHANNEL_DENIED", "当前渠道仅支持港区");
        if (!contains(scope.path("hosts"), host)) deny(reasons, codes, "SOURCE_DENIED", "岗位不在已确认的精确来源域名内");
        for (String field : List.of("channel", "region", "language", "direction")) {
            String plural = switch (field) { case "channel" -> "channels"; case "region" -> "regions"; case "language" -> "languages"; default -> "directions"; };
            if (!contains(scope.path(plural), text(target, field, 50, true))) deny(reasons, codes, "TARGET_DENIED", "岗位条件越界：" + field);
        }
        String content = (text(target, "company", 200, true) + " " + text(target, "title", 300, true) + " " + text(target, "description", 30000, false)).toLowerCase(Locale.ROOT);
        if (!scope.path("includeTerms").isEmpty() && !matches(scope.path("includeTerms"), content)) deny(reasons, codes, "SELECTION_DENIED", "岗位未命中已确认的选择词");
        if (matches(scope.path("excludeTerms"), content)) deny(reasons, codes, "EXCLUDED", "岗位命中排除项");
        String transformation = text(target, "transformation", 50, true);
        if (!contains(scope.path("transformations"), transformation)) deny(reasons, codes, "TRANSFORMATION_DENIED", "材料变换未获允许；不能新增事实或跨地区/语言覆盖");
        try {
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
            try { service.duplicate(c, object().put("id", "scope-target").put("kind", "APPLICATION").put("url", link)); }
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
