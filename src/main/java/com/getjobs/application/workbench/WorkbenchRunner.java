package com.getjobs.application.workbench;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** A configured external CLI Adapter, not a browser agent. No shell interpolation. */
public final class WorkbenchRunner implements AutoCloseable {
    private final WorkbenchService service;
    private final List<String> command;
    private final List<String> offerTodayCommand;
    private final String callback;
    private final long timeoutSeconds;
    private final AtomicBoolean busy = new AtomicBoolean();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> { var t = new Thread(r, "workbench-agent"); t.setDaemon(true); return t; });
    private volatile Process process;
    private FileChannel lockChannel;
    private FileLock lock;
    private boolean closed;

    public WorkbenchRunner(WorkbenchService service, List<String> command, String callback, long timeoutSeconds) {
        this(service, command, List.of(), callback, timeoutSeconds);
    }

    public WorkbenchRunner(WorkbenchService service, List<String> command, List<String> offerTodayCommand, String callback, long timeoutSeconds) {
        this.service = service; this.command = List.copyOf(command); this.offerTodayCommand = List.copyOf(offerTodayCommand);
        this.callback = callback; this.timeoutSeconds = timeoutSeconds;
        if (!command.isEmpty() && (command.stream().noneMatch(arg -> arg.contains("{taskFile}")) || command.stream().anyMatch(String::isBlank)))
            throw new IllegalArgumentException("官网执行器参数必须包含 {taskFile} 且不能为空");
        if (offerTodayCommand.stream().anyMatch(String::isBlank)) throw new IllegalArgumentException("OfferToday 脚本前缀参数不能为空");
        try {
            lockChannel = FileChannel.open(service.store.root.resolve("runner.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            lock = lockChannel.tryLock();
            if (lock == null) throw new IllegalStateException("已有工作台使用此数据目录");
            service.recoverInterrupted();
        } catch (Exception e) {
            try { if (lock != null) lock.release(); if (lockChannel != null) lockChannel.close(); } catch (Exception ignored) { }
            throw new IllegalStateException("工作台数据目录已被使用或无法锁定", e);
        }
    }

    public ObjectNode runtime() {
        return service.json.createObjectNode().put("agentConfigured", !command.isEmpty())
                .put("offerTodayConfigured", !offerTodayCommand.isEmpty()).put("busy", busy.get());
    }

    public synchronized ObjectNode start(String id) {
        if (closed) throw new WorkbenchException(409, "执行器已关闭，不能启动任务");
        boolean offerToday = service.task(id).path("channel").asText().equals("OFFERTODAY");
        if (offerToday ? offerTodayCommand.isEmpty() : command.isEmpty()) throw new WorkbenchException(409,
                offerToday ? "尚未配置 OfferToday 脚本；不能以平台入口存在冒充可执行" : "尚未配置官网 AI 执行器；可复制材料交接，但不能声称自动执行");
        if (!busy.compareAndSet(false, true)) throw new WorkbenchException(409, "已有执行任务运行中，请等待或处理人工阻塞");
        boolean claimed = false;
        Process launched = null;
        try {
            String token = UUID.randomUUID().toString() + UUID.randomUUID();
            var bundle = service.beginTask(id, token, callback);
            claimed = true;
            Path directory = service.store.root.resolve("runs").resolve(id).resolve(UUID.randomUUID().toString());
            Files.createDirectories(directory);
            Path file = directory.resolve("task.json");
            bundle.put("reporterPath", Path.of("scripts/workbench/report.py").toAbsolutePath().normalize().toString());
            Files.writeString(file, service.json.writerWithDefaultPrettyPrinter().writeValueAsString(bundle));
            try { Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------")); }
            catch (UnsupportedOperationException ignored) { }
            List<String> arguments;
            if (offerToday) {
                arguments = new ArrayList<>(offerTodayCommand);
                arguments.add("run"); arguments.add(file.toString());
            } else arguments = command.stream().map(arg -> arg.replace("{taskFile}", file.toString())).toList();
            var builder = new ProcessBuilder(arguments).redirectErrorStream(true).redirectOutput(directory.resolve("executor.log").toFile());
            builder.environment().put("WORKBENCH_TASK_FILE", file.toString());
            Process child = builder.start(); launched = child; process = child;
            child.getOutputStream().close();
            executor.submit(() -> {
                try {
                    if (!child.waitFor(timeoutSeconds, TimeUnit.SECONDS)) terminate(child);
                } catch (InterruptedException e) { terminate(child); Thread.currentThread().interrupt(); }
                finally {
                    try { service.processEnded(id); }
                    finally { process = null; busy.set(false); }
                }
            });
            return service.task(id);
        } catch (WorkbenchException e) { busy.set(false); throw e; }
        catch (Exception e) {
            if (launched != null) { terminate(launched); service.processEnded(id); }
            else if (claimed) service.startFailed(id);
            process = null; busy.set(false);
            throw new WorkbenchException(409, launched == null ? "外部执行器未能启动；请检查本机命令配置，未发送申请" : "执行器启动后异常，请核实任务结果，不要重新投递");
        }
    }

    /** Explicit read-only discovery; shares the same exclusive process slot as applications. */
    public synchronized ObjectNode discoverOfferToday() {
        if (closed || offerTodayCommand.isEmpty()) throw new WorkbenchException(409, "请先配置 OfferToday 脚本及已授权的现有浏览器连接");
        if (!busy.compareAndSet(false, true)) throw new WorkbenchException(409, "已有执行任务运行中，暂不能读取 OfferToday");
        Process child = null;
        try {
            Path directory = service.store.root.resolve("runs").resolve("discovery-" + UUID.randomUUID());
            Files.createDirectories(directory);
            Path output = directory.resolve("positions.json");
            var arguments = new ArrayList<>(offerTodayCommand); arguments.add("discover");
            child = new ProcessBuilder(arguments).redirectOutput(output.toFile())
                    .redirectError(directory.resolve("discovery.log").toFile()).start();
            process = child;
            if (!child.waitFor(Math.min(timeoutSeconds, 45), TimeUnit.SECONDS)) {
                terminate(child);
                throw new WorkbenchException(409, "OfferToday 读取超时；未创建申请，请检查已有浏览器连接");
            }
            if (child.exitValue() != 0) throw new WorkbenchException(409, "OfferToday 读取未完成；请先共享当前标签页，或检查本机脚本日志");
            if (!Files.isRegularFile(output) || Files.size(output) > 1024 * 1024) throw new WorkbenchException(409, "OfferToday 职位输出无效或过大");
            var parsed = service.json.readTree(Files.readString(output));
            if (parsed == null || !parsed.path("jobs").isArray() || parsed.has("connected") && !parsed.path("connected").asBoolean())
                throw new WorkbenchException(409, "OfferToday 未返回已连接页面的职位列表");
            var jobs = service.json.createArrayNode();
            var seen = new HashSet<String>();
            for (var record : parsed.path("jobs")) {
                if (jobs.size() == 40) break;
                if (!(record instanceof ObjectNode job)) throw new WorkbenchException(409, "OfferToday 职位格式无效");
                var target = OfferTodayTarget.parse(WorkbenchService.text(job, "url", 3000, true));
                if (!seen.add(target.id())) continue;
                jobs.add(service.json.createObjectNode().put("url", target.url()).put("platformJobId", target.id())
                        .put("title", WorkbenchService.text(job, "title", 300, false))
                        .put("company", WorkbenchService.text(job, "company", 200, false))
                        .put("description", WorkbenchService.text(job, "description", 30000, false)));
            }
            var result = service.json.createObjectNode().put("connected", true).put("limit", 40)
                    .put("note", "仅读取当前已加载页面，最多 40 个不同岗位，不自动翻页；选择后仍须填写、逐岗审核，未创建或发送申请。");
            result.set("jobs", jobs);
            return result;
        } catch (WorkbenchException e) { throw e; }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WorkbenchException(409, "OfferToday 只读任务已中断，未据此创建申请");
        } catch (Exception e) { throw new WorkbenchException(409, "OfferToday 读取失败，请检查脚本和当前标签页；未创建申请"); }
        finally {
            if (child != null && child.isAlive()) terminate(child);
            process = null; busy.set(false);
        }
    }

    /** Bounded, read-only material analysis. Shares the application slot but has no event token or browser authority. */
    public synchronized ObjectNode reviewMaterials(ObjectNode input) {
        if (closed || busy.get()) throw new WorkbenchException(409, "已有执行任务运行中或执行器已关闭");
        var review = service.materials.prepare(input);
        if (review.has("candidates")) return review;
        if (command.isEmpty()) throw new WorkbenchException(409, "尚未配置 AI 执行器，不能整理材料；未确认任何事实");
        busy.set(true);
        Process child = null;
        try {
            Path directory = service.store.root.resolve("runs").resolve("material-" + UUID.randomUUID());
            Files.createDirectories(directory);
            Path file = directory.resolve("task.json"), result = directory.resolve("result.json");
            var bundle = service.materials.bundle(review).put("resultPath", result.toString());
            Files.writeString(file, service.json.writeValueAsString(bundle));
            try { Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------")); }
            catch (UnsupportedOperationException ignored) { }
            var arguments = command.stream().map(arg -> arg.replace("{taskFile}", file.toString())).toList();
            child = new ProcessBuilder(arguments).redirectErrorStream(true).redirectOutput(directory.resolve("analysis.log").toFile()).start();
            process = child; child.getOutputStream().close();
            if (!child.waitFor(Math.min(timeoutSeconds, 120), TimeUnit.SECONDS)) {
                terminate(child); throw new WorkbenchException(409, "材料分析超时；未确认任何事实");
            }
            if (child.exitValue() != 0 || !Files.isRegularFile(result) || Files.isSymbolicLink(result) || Files.size(result) > 1024 * 1024)
                throw new WorkbenchException(409, "材料分析未返回有效结果；未确认任何事实");
            return service.materials.finish(review, service.json.readTree(Files.readString(result)));
        } catch (WorkbenchException e) { throw e; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new WorkbenchException(409, "材料分析中断；未确认任何事实"); }
        catch (Exception e) { throw new WorkbenchException(409, "材料分析失败，请检查已配置的本机命令；未确认任何事实"); }
        finally {
            if (child != null && child.isAlive()) terminate(child);
            process = null; busy.set(false);
        }
    }

    public synchronized ObjectNode checkStatus(String applicationId) {
        if (closed || command.isEmpty()) throw new WorkbenchException(409, "请先配置并启动官网 AI 执行器");
        if (busy.get()) throw new WorkbenchException(409, "已有 AI 任务执行中");
        var task = service.createStatusCheck(applicationId);
        return start(task.path("id").asText());
    }

    private static void terminate(Process child) {
        child.descendants().forEach(handle -> { if (handle.isAlive()) handle.destroy(); });
        child.destroy();
        try { if (!child.waitFor(3, TimeUnit.SECONDS)) child.destroyForcibly(); }
        catch (InterruptedException e) { child.destroyForcibly(); Thread.currentThread().interrupt(); }
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        Process child = process;
        if (child != null && child.isAlive()) terminate(child);
        executor.shutdownNow();
        try { executor.awaitTermination(5, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        try { if (lock != null) lock.release(); if (lockChannel != null) lockChannel.close(); }
        catch (Exception e) { throw new IllegalStateException("无法释放工作台运行锁", e); }
    }
}
