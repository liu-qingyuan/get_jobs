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
    private final String callback;
    private final long timeoutSeconds;
    private final AtomicBoolean busy = new AtomicBoolean();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> { var t = new Thread(r, "workbench-agent"); t.setDaemon(true); return t; });
    private volatile Process process;
    private FileChannel lockChannel;
    private FileLock lock;
    private boolean closed;

    public WorkbenchRunner(WorkbenchService service, List<String> command, String callback, long timeoutSeconds) {
        this.service = service; this.command = List.copyOf(command); this.callback = callback; this.timeoutSeconds = timeoutSeconds;
        if (!command.isEmpty() && (command.stream().noneMatch(arg -> arg.contains("{taskFile}")) || command.stream().anyMatch(String::isBlank)))
            throw new IllegalArgumentException("官网执行器参数必须包含 {taskFile} 且不能为空");
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
        return service.json.createObjectNode().put("agentConfigured", !command.isEmpty()).put("busy", busy.get());
    }

    public synchronized ObjectNode start(String id) {
        if (closed) throw new WorkbenchException(409, "执行器已关闭，不能启动任务");
        if (command.isEmpty()) throw new WorkbenchException(409, "尚未配置官网 AI 执行器；可复制材料交接，但不能声称自动执行");
        if (!busy.compareAndSet(false, true)) throw new WorkbenchException(409, "已有 AI 任务执行中，请等待或处理人工阻塞");
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
            List<String> arguments = command.stream().map(arg -> arg.replace("{taskFile}", file.toString())).toList();
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
