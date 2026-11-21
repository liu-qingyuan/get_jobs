package com.getjobs.application.workbench;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.getjobs.worker.boss.BossCandidateReader;
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
    private final List<String> jobsDbCommand;
    private final WorkbenchCandidates.Reader bossReader;
    private final String callback;
    private final long timeoutSeconds;
    private final AtomicBoolean busy = new AtomicBoolean();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> { var t = new Thread(r, "workbench-agent"); t.setDaemon(true); return t; });
    private volatile Process process;
    private FileChannel lockChannel;
    private FileLock lock;
    private volatile boolean closed;

    public WorkbenchRunner(WorkbenchService service, List<String> command, String callback, long timeoutSeconds) {
        this(service, command, List.of(), callback, timeoutSeconds);
    }

    public WorkbenchRunner(WorkbenchService service, List<String> command, List<String> offerTodayCommand, String callback, long timeoutSeconds) {
        this(service, command, offerTodayCommand, null, callback, timeoutSeconds);
    }

    WorkbenchRunner(WorkbenchService service, List<String> command, List<String> offerTodayCommand,
            WorkbenchCandidates.Reader bossReader, String callback, long timeoutSeconds) {
        this(service, command, offerTodayCommand, List.of(), bossReader, callback, timeoutSeconds);
    }

    WorkbenchRunner(WorkbenchService service, List<String> command, List<String> offerTodayCommand, List<String> jobsDbCommand,
            WorkbenchCandidates.Reader bossReader, String callback, long timeoutSeconds) {
        this.service = service; this.command = List.copyOf(command); this.offerTodayCommand = List.copyOf(offerTodayCommand);
        this.jobsDbCommand = List.copyOf(jobsDbCommand); this.bossReader = bossReader;
        this.callback = callback; this.timeoutSeconds = timeoutSeconds;
        if (!command.isEmpty() && (command.stream().noneMatch(arg -> arg.contains("{taskFile}")) || command.stream().anyMatch(String::isBlank)))
            throw new IllegalArgumentException("官网执行器参数必须包含 {taskFile} 且不能为空");
        if (offerTodayCommand.stream().anyMatch(String::isBlank) || jobsDbCommand.stream().anyMatch(String::isBlank)) throw new IllegalArgumentException("渠道脚本前缀参数不能为空");
        try {
            lockChannel = FileChannel.open(service.store.root.resolve("runner.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            lock = lockChannel.tryLock();
            if (lock == null) throw new IllegalStateException("已有工作台使用此数据目录");
            recoverProcessLease();
            service.recoverInterrupted();
        } catch (Exception e) {
            try { if (lock != null) lock.release(); if (lockChannel != null) lockChannel.close(); } catch (Exception ignored) { }
            throw new IllegalStateException("工作台数据目录已被使用或无法锁定", e);
        }
    }

    public ObjectNode runtime() {
        return service.json.createObjectNode().put("agentConfigured", !command.isEmpty())
                .put("offerTodayConfigured", !offerTodayCommand.isEmpty()).put("busy", busy.get())
                .put("jobsDbConfigured", !jobsDbCommand.isEmpty()).put("jobsDbProductionVerified", false)
                .put("jobsDbOutboundSupported", false).put("jobsDbCapabilityReason", JobsDbTarget.OUTBOUND_BLOCK_REASON)
                .put("bossReadConfigured", bossReader != null).put("bossOutboundSupported", false).put("bossCapabilityReason", BossCandidateReader.OUTBOUND_BLOCK_REASON);
    }

    public synchronized ObjectNode start(String id) {
        if (closed) throw new WorkbenchException(409, "执行器已关闭，不能启动任务");
        boolean offerToday = service.task(id).path("channel").asText().equals("OFFERTODAY");
        if (offerToday ? offerTodayCommand.isEmpty() : command.isEmpty()) throw new WorkbenchException(409,
                offerToday ? "尚未配置 OfferToday 脚本；不能以平台入口存在冒充可执行" : "尚未配置官网 AI 执行器；可复制材料交接，但不能声称自动执行");
        if (!busy.compareAndSet(false, true)) throw new WorkbenchException(409, "已有执行任务运行中，请等待或处理人工阻塞");
        try {
            Process child = launch(id);
            executor.submit(() -> {
                try { waitForTask(id, child); }
                finally { busy.set(false); }
            });
            return service.task(id);
        } catch (RuntimeException e) { busy.set(false); throw e; }
    }

    /** Keeps the same exclusive slot across every stage and every single-job child. */
    public synchronized ObjectNode startCycle(ObjectNode input, String cycleId) {
        if (closed || !busy.compareAndSet(false, true)) throw new WorkbenchException(409, "执行器已关闭或正在处理其他任务");
        try {
            var cycle = service.workflow.begin(input, cycleId);
            executor.submit(() -> {
                try {
                    service.workflow.advance(cycle.path("id").asText(), this::readCandidatePage, this::analyzeCandidates, id -> {
                        if (closed || Thread.currentThread().isInterrupted()) throw new WorkbenchException(409, "执行器已停止；保留安全断点");
                        waitForTask(id, launch(id));
                    });
                } finally { busy.set(false); }
            });
            return cycle;
        } catch (RuntimeException e) { busy.set(false); throw e; }
    }

    private void waitForTask(String id, Process child) {
        try { if (!child.waitFor(service.task(id).path("executionSeconds").asLong(timeoutSeconds), TimeUnit.SECONDS)) stopProcess(child); }
        catch (InterruptedException e) { stopProcess(child); Thread.currentThread().interrupt(); }
        finally { try { releaseProcess(); } finally { service.processEnded(id); } }
    }

    private Process launch(String id) {
        boolean offerToday = service.task(id).path("channel").asText().equals("OFFERTODAY");
        if (offerToday ? offerTodayCommand.isEmpty() : command.isEmpty()) throw new WorkbenchException(409, "此渠道尚未配置单岗执行器");
        boolean claimed = false;
        Process launched = null;
        try {
            String token = UUID.randomUUID().toString() + UUID.randomUUID();
            var bundle = service.beginTask(id, token, callback, timeoutSeconds);
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
            Process child = spawn(builder, bundle.path("task").has("packageId")); launched = child;
            child.getOutputStream().close();
            return child;
        } catch (WorkbenchException e) { if (claimed) service.processEnded(id); throw e; }
        catch (Exception e) {
            if (launched != null) { stopProcess(launched); service.processEnded(id); }
            else if (claimed) service.startFailed(id);
            process = null;
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
            child = spawn(new ProcessBuilder(arguments).redirectOutput(output.toFile())
                    .redirectError(directory.resolve("discovery.log").toFile()));
            if (!child.waitFor(Math.min(timeoutSeconds, 45), TimeUnit.SECONDS)) {
                stopProcess(child);
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
            releaseProcess(); busy.set(false);
        }
    }

    /** One bounded read-only turn, including required details; no application callback, materials or claim. */
    public synchronized ObjectNode discoverCandidates(ObjectNode input) {
        if (closed || !busy.compareAndSet(false, true)) throw new WorkbenchException(409, "执行器已关闭或正在处理其他任务");
        try { return service.candidates.discover(input, this::readCandidatePage); }
        finally { busy.set(false); }
    }

    private ObjectNode readCandidatePage(ObjectNode bundle) throws Exception {
        if (closed || Thread.currentThread().isInterrupted()) throw new WorkbenchException(409, "执行器已停止");
        if (bundle.path("source").path("channel").asText().equals("BOSS")) {
            if (bossReader == null) throw new WorkbenchException(409, "Boss 只读来源未连接本副本已授权会话；不会新建登录态或回退官网 AI");
            ((ObjectNode) bundle.path("limits")).put("seconds", Math.min(timeoutSeconds, bundle.path("limits").path("seconds").asLong()));
            return bossReader.read(bundle);
        }
        boolean offerToday = bundle.path("source").path("channel").asText().equals("OFFERTODAY");
        boolean jobsDb = bundle.path("source").path("channel").asText().equals("JOBSDB");
        var readerCommand = jobsDb ? jobsDbCommand : offerToday ? offerTodayCommand : command;
        if (readerCommand.isEmpty()) throw new WorkbenchException(409,
                jobsDb ? "JobsDB 未配置独立脚本/会话；不会回退官网 AI 或创建登录态" : offerToday ? "OfferToday 未配置或没有已授权会话；不会新建登录态" : "尚未配置通用官网只读执行器");
        Path directory = service.store.root.resolve("runs").resolve("candidates-" + UUID.randomUUID());
        Files.createDirectories(directory);
        Path file = directory.resolve("task.json"), result = directory.resolve("result.json");
        // The process deadline cannot exceed the resource envelope charged by the policy.
        long seconds = Math.min(timeoutSeconds, bundle.path("limits").path("seconds").asLong());
        ((ObjectNode) bundle.path("limits")).put("seconds", seconds);
        bundle.put("resultPath", result.toString());
        Files.writeString(file, service.json.writeValueAsString(bundle));
        try { Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------")); }
        catch (UnsupportedOperationException ignored) { }
        List<String> arguments;
        if (offerToday || jobsDb) {
            arguments = new ArrayList<>(readerCommand); arguments.add("read-candidates"); arguments.add(file.toString());
            if (jobsDb) { arguments.add("--data-dir"); arguments.add(service.jobsDbDirectory().toString()); }
        } else arguments = command.stream().map(arg -> arg.replace("{taskFile}", file.toString())).toList();
        Process child = null;
        try {
            child = spawn(new ProcessBuilder(arguments).redirectErrorStream(true).redirectOutput(directory.resolve("read.log").toFile()));
            child.getOutputStream().close();
            if (!child.waitFor(seconds, TimeUnit.SECONDS)) throw new WorkbenchException(409, "只读读取时间预算耗尽；保留断点");
            if (child.exitValue() != 0 || !Files.isRegularFile(result) || Files.isSymbolicLink(result) || Files.size(result) > 2 * 1024 * 1024)
                throw new WorkbenchException(409, "只读来源未返回有效结果；检查会话、版本或本机执行器日志");
            var parsed = service.json.readTree(Files.readString(result));
            if (!(parsed instanceof ObjectNode output)) throw new WorkbenchException(409, "只读来源结果格式无效");
            if (output.has("exception")) throw new WorkbenchException(409, WorkbenchService.text(output, "exception", 1000, true));
            return output;
        } finally {
            releaseProcess();
        }
    }

    /** Explicit bounded batch; subsequent reads reconcile relevant changes, never retry identical failed inputs. */
    public synchronized ObjectNode matchCandidates(ObjectNode input) {
        if (closed || !busy.compareAndSet(false, true)) throw new WorkbenchException(409, "执行器已关闭或正在处理其他任务");
        try { return service.matching.start(input, this::analyzeCandidates); }
        finally { busy.set(false); }
    }
    /** Preparation shares the exclusive slot, but has no application callback or external-action capability. */
    public synchronized ObjectNode prepareCandidates(ObjectNode input) {
        if (closed || !busy.compareAndSet(false, true)) throw new WorkbenchException(409, "执行器已关闭或正在处理其他任务");
        try { return service.preparation.prepare(input, this::analyzeCandidates); }
        finally { busy.set(false); }
    }
    public void refreshMatching() {
        // Claim before waiting for the lifecycle monitor: an already running process must never block a GET.
        if (!busy.compareAndSet(false, true)) return;
        try {
            synchronized (this) {
                if (!closed) service.matching.refresh(this::analyzeCandidates);
            }
        } finally { busy.set(false); }
    }
    private ObjectNode analyzeCandidates(ObjectNode bundle) throws Exception {
        if (closed || Thread.currentThread().isInterrupted()) throw new WorkbenchException(409, "执行器已停止");
        if (command.isEmpty()) throw new WorkbenchException(409, "尚未配置匹配 AI 执行器，未生成事实或申请");
        Path directory = service.store.root.resolve("runs").resolve((bundle.path("kind").asText().equals("MATERIAL_PREPARE") ? "preparation-" : "matching-") + UUID.randomUUID());
        Files.createDirectories(directory);
        Path file = directory.resolve("task.json"), result = directory.resolve("result.json");
        long seconds = Math.min(timeoutSeconds, bundle.path("limits").path("seconds").asLong());
        ((ObjectNode) bundle.path("limits")).put("seconds", seconds);
        bundle.put("resultPath", result.toString());
        Files.writeString(file, service.json.writeValueAsString(bundle));
        try { Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------")); }
        catch (UnsupportedOperationException ignored) { }
        var arguments = command.stream().map(arg -> arg.replace("{taskFile}", file.toString())).toList();
        Process child = null;
        try {
            child = spawn(new ProcessBuilder(arguments).redirectErrorStream(true).redirectOutput(directory.resolve("analysis.log").toFile()));
            child.getOutputStream().close();
            if (!child.waitFor(seconds, TimeUnit.SECONDS)) throw new WorkbenchException(409, "匹配时间预算耗尽，未确认新事实；相同输入不自动重试");
            if (child.exitValue() != 0 || !Files.isRegularFile(result) || Files.isSymbolicLink(result) || Files.size(result) > 2 * 1024 * 1024)
                throw new WorkbenchException(409, "匹配执行器未返回有效结果，请检查本机日志；相同输入不自动重试");
            var parsed = service.json.readTree(Files.readString(result));
            if (!(parsed instanceof ObjectNode output)) throw new WorkbenchException(409, "匹配结果格式无效");
            return output;
        } finally {
            releaseProcess();
        }
    }

    /** Bounded, read-only material analysis. Shares the application slot but has no event token or browser authority. */
    public synchronized ObjectNode reviewMaterials(ObjectNode input) {
        if (closed || !busy.compareAndSet(false, true)) throw new WorkbenchException(409, "已有执行任务运行中或执行器已关闭");
        Process child = null;
        try {
            var review = service.materials.prepare(input);
            if (review.has("candidates")) return review;
            if (command.isEmpty()) throw new WorkbenchException(409, "尚未配置 AI 执行器，不能整理材料；未确认任何事实");
            Path directory = service.store.root.resolve("runs").resolve("material-" + UUID.randomUUID());
            Files.createDirectories(directory);
            Path file = directory.resolve("task.json"), result = directory.resolve("result.json");
            var bundle = service.materials.bundle(review).put("resultPath", result.toString());
            Files.writeString(file, service.json.writeValueAsString(bundle));
            try { Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------")); }
            catch (UnsupportedOperationException ignored) { }
            var arguments = command.stream().map(arg -> arg.replace("{taskFile}", file.toString())).toList();
            child = spawn(new ProcessBuilder(arguments).redirectErrorStream(true).redirectOutput(directory.resolve("analysis.log").toFile()));
            child.getOutputStream().close();
            if (!child.waitFor(Math.min(timeoutSeconds, 120), TimeUnit.SECONDS)) {
                stopProcess(child); throw new WorkbenchException(409, "材料分析超时；未确认任何事实");
            }
            if (child.exitValue() != 0 || !Files.isRegularFile(result) || Files.isSymbolicLink(result) || Files.size(result) > 1024 * 1024)
                throw new WorkbenchException(409, "材料分析未返回有效结果；未确认任何事实");
            return service.materials.finish(review, service.json.readTree(Files.readString(result)));
        } catch (WorkbenchException e) { throw e; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new WorkbenchException(409, "材料分析中断；未确认任何事实"); }
        catch (Exception e) { throw new WorkbenchException(409, "材料分析失败，请检查已配置的本机命令；未确认任何事实"); }
        finally {
            releaseProcess(); busy.set(false);
        }
    }

    public synchronized ObjectNode checkStatus(String applicationId) {
        if (closed || command.isEmpty()) throw new WorkbenchException(409, "请先配置并启动官网 AI 执行器");
        if (busy.get()) throw new WorkbenchException(409, "已有 AI 任务执行中");
        var task = service.createStatusCheck(applicationId);
        return start(task.path("id").asText());
    }

    /** Persist only our own exact PID/start instant; restart must release it before reusing the browser slot. */
    private Process spawn(ProcessBuilder builder) throws Exception { return spawn(builder, true); }
    private Process spawn(ProcessBuilder builder, boolean supervised) throws Exception {
        String proof = "";
        if (supervised) {
            Files.createDirectories(service.store.root.resolve("runs"));
            Path script = Files.createTempFile(service.store.root.resolve("runs"), "executor-", ".py");
            try (var source = WorkbenchRunner.class.getResourceAsStream("/workbench/execute.py")) {
                if (source == null) throw new WorkbenchException(409, "缺少受控执行资源监护脚本");
                Files.write(script, source.readAllBytes());
            }
            proof = script + ".release.json";
            var arguments = new ArrayList<>(List.of("python3", script.toString(), proof)); arguments.addAll(builder.command()); builder.command(arguments);
        }
        String releaseProof = proof;
        service.store.write(c -> {
            if (!service.store.list(c, "runner_process").isEmpty()) throw new WorkbenchException(409, "前一执行资源尚未确认释放，禁止开始下一进程");
            service.store.save(c, "runner_process", service.json.createObjectNode().put("id", "active").put("status", "STARTING").put("supervised", supervised).put("releaseProof", releaseProof)); return null;
        });
        Process child = null;
        try {
            child = builder.start(); process = child;
            var startedAt = child.info().startInstant();
            if (startedAt.isEmpty() && !child.isAlive()) clearProcessLease();
            else {
                var record = service.json.createObjectNode().put("id", "active").put("status", "RUNNING").put("supervised", supervised).put("releaseProof", releaseProof).put("pid", child.pid())
                        .put("startedAt", startedAt.orElseThrow().toString());
                service.store.write(c -> { service.store.save(c, "runner_process", record); return null; });
            }
            return child;
        } catch (Exception e) {
            if (child != null) stopProcess(child);
            clearProcessLease(); process = null; throw e;
        }
    }
    private void releaseProcess() {
        Process child = process;
        if (child == null) return;
        if (child.isAlive()) stopProcess(child);
        var rows = service.store.write(c -> service.store.list(c, "runner_process"));
        if (!rows.isEmpty()) requireRelease((ObjectNode) rows.get(0));
        clearProcessLease(); process = null;
    }
    private void clearProcessLease() { service.store.write(c -> { if (!service.store.list(c, "runner_process").isEmpty()) service.store.delete(c, "runner_process", "active"); return null; }); }
    private void recoverProcessLease() {
        var rows = service.store.write(c -> service.store.list(c, "runner_process"));
        if (rows.isEmpty()) return;
        var record = rows.get(0);
        if (!record.path("status").asText().equals("RUNNING")) throw new WorkbenchException(409, "上次进程创建中断，资源归属不明；需人工核实，不能自动复用浏览器");
        ProcessHandle.of(record.path("pid").asLong()).filter(ProcessHandle::isAlive).ifPresent(handle -> {
            if (!handle.info().startInstant().map(instant -> instant.toString().equals(record.path("startedAt").asText())).orElse(false))
                throw new WorkbenchException(409, "旧执行器 PID 已复用或身份不明；不终止其他进程，需人工核实资源");
            stopOwned(handle, (ObjectNode) record);
        });
        requireRelease((ObjectNode) record);
        clearProcessLease();
    }
    private void stopProcess(Process child) {
        var rows = service.store.write(c -> service.store.list(c, "runner_process"));
        if (rows.isEmpty()) terminate(child);
        else stopOwned(child.toHandle(), (ObjectNode) rows.get(0));
    }
    private void stopOwned(ProcessHandle handle, ObjectNode record) {
        if (!record.path("supervised").asBoolean()) { terminate(handle); return; }
        boolean interrupted = Thread.interrupted();
        try {
            handle.destroy();
            handle.onExit().get(6, TimeUnit.SECONDS);
            requireRelease(record);
        } catch (Exception e) { throw new WorkbenchException(409, "后代执行资源尚未确认释放；保持租约，禁止下一岗位，需人工核实"); }
        finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
    private void requireRelease(ObjectNode record) {
        if (!record.path("supervised").asBoolean()) return;
        try {
            Path proof = Path.of(record.path("releaseProof").asText());
            if (Files.isRegularFile(proof) && !Files.isSymbolicLink(proof) && Files.size(proof) < 1024 && service.json.readTree(Files.readString(proof)).path("released").asBoolean()) return;
        } catch (Exception ignored) { /* Missing/invalid proof is an explicit resource exception, never permission to continue. */ }
        throw new WorkbenchException(409, "执行器或其后代未确认释放；保留租约，不能开始下一岗位");
    }
    private static void terminate(Process child) { terminate(child.toHandle()); }
    private static void terminate(ProcessHandle child) {
        var handles = new ArrayList<>(child.descendants().toList()); handles.add(child);
        handles.forEach(handle -> { if (handle.isAlive()) handle.destroy(); });
        boolean interrupted = Thread.interrupted();
        try {
            for (var handle : handles) if (handle.isAlive()) {
                try { handle.onExit().get(3, TimeUnit.SECONDS); }
                catch (TimeoutException e) { handle.destroyForcibly(); handle.onExit().get(3, TimeUnit.SECONDS); }
            }
        } catch (Exception e) { throw new WorkbenchException(409, "执行资源尚未确认释放；停止后续岗位，请核对已有执行器"); }
        finally { if (interrupted) Thread.currentThread().interrupt(); }
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        Process child = process;
        if (child != null && child.isAlive()) stopProcess(child);
        executor.shutdownNow();
        try { executor.awaitTermination(5, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        try { if (lock != null) lock.release(); if (lockChannel != null) lockChannel.close(); }
        catch (Exception e) { throw new IllegalStateException("无法释放工作台运行锁", e); }
    }
}
