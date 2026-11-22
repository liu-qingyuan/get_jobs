package com.getjobs.application.workbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.*;
import java.util.function.Supplier;

/** Local connection preflight only. Never opens a browser, reads credential files, or grants an action. */
final class WorkbenchConnections {
    enum Status {
        NOT_CONFIGURED(false, "未配置只读 Adapter"),
        PROFILE_MISSING(false, "未找到当前副本的独立浏览器资料"),
        PROFILE_UNAVAILABLE(false, "独立浏览器资料不可用；不会改写或迁移"),
        BROWSER_NOT_CONNECTED(false, "已有本机浏览器资料，但工作台尚无已核实连接；不代表登录失效"),
        BUSY(false, "原渠道任务占用浏览器；不会抢占"),
        READ_GUARD_BLOCKED(false, "已有 Service Worker 状态；只读守卫拒绝，保留原浏览器资料，不要求重新登录"),
        CHECK_AT_READ(true, "只读执行时核验连接与平台状态；不代表已登录或可外发");

        final boolean canAttempt;
        final String reason;
        Status(boolean canAttempt, String reason) { this.canAttempt = canAttempt; this.reason = reason; }
    }

    private final Supplier<Status> boss;
    private final Supplier<Status> jobsDb;

    WorkbenchConnections(Supplier<Status> boss, Supplier<Status> jobsDb) {
        this.boss = boss;
        this.jobsDb = jobsDb;
    }

    ObjectNode view(ObjectMapper json) {
        var result = json.createObjectNode();
        put(result.putObject("boss"), boss.get());
        put(result.putObject("jobsDb"), jobsDb.get());
        return result;
    }

    private static void put(ObjectNode output, Status status) {
        output.put("status", status.name()).put("reason", status.reason);
    }

    void requireRead(String channel) {
        Status status = switch (channel) {
            case "BOSS" -> boss.get();
            case "JOBSDB" -> jobsDb.get();
            default -> Status.CHECK_AT_READ;
        };
        if (!status.canAttempt) throw new WorkbenchException(409, (channel.equals("JOBSDB") ? "JobsDB" : "Boss") + "：" + status.reason);
    }

    static Status profile(Path directory) {
        if (Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)) return Status.PROFILE_MISSING;
        return Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) ? Status.CHECK_AT_READ : Status.PROFILE_UNAVAILABLE;
    }

    static Status jobsDb(Path directory) {
        var state = profile(directory);
        if (!state.canAttempt) return state;
        // Mirror the CLI's cheap preflight for actionable feedback. The CLI retains its own
        // guard under the profile lock immediately before opening Chrome.
        try (var children = Files.newDirectoryStream(directory)) {
            for (var child : children) {
                if (Files.exists(child.resolve("Service Worker"))) return Status.READ_GUARD_BLOCKED;
            }
            return Status.CHECK_AT_READ;
        } catch (IOException | DirectoryIteratorException e) {
            return Status.PROFILE_UNAVAILABLE;
        }
    }
}
