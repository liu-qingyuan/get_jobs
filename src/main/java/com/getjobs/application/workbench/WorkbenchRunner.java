package com.getjobs.application.workbench;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.getjobs.worker.boss.BossCandidateReader;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
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
    private final List<String> projectCommand;
    private final List<String> verifierCommand;
    private record PendingCandidateRead(ObjectNode bundle) { }
    private final WorkbenchCandidates.Reader bossReader;
    private volatile BossSingleJobEffect bossSingleJob;
    private final WorkbenchConnections connections;
    private final Map<String, PendingCandidateRead> pendingCandidateReads = new ConcurrentHashMap<>();
    private final Map<String, ObjectNode> resumedCandidateReads = new ConcurrentHashMap<>();
    private final WorkbenchCandidates.Reader candidateReader = new WorkbenchCandidates.Reader() {
        @Override public void checkConnection(String channel) { connections.requireRead(channel); }
        @Override public void checkConnection(String channel, String resumeReadId) { connections.requireRead(channel); }
        @Override public ObjectNode read(ObjectNode bundle) throws Exception { return readCandidatePage(bundle); }
        @Override public boolean awaitUserAction(WorkbenchCandidates.UserAction action) throws Exception {
            if (action.channel().equals("BOSS")) return bossReader != null && bossReader.awaitUserAction(action);
            return action.channel().equals("JOBSDB") && awaitJobsDbUserAction(action);
        }
    };
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
        this(service, command, offerTodayCommand, jobsDbCommand, bossReader, List.of(), callback, timeoutSeconds);
    }

    WorkbenchRunner(WorkbenchService service, List<String> command, List<String> offerTodayCommand, List<String> jobsDbCommand,
            WorkbenchCandidates.Reader bossReader, List<String> projectCommand, String callback, long timeoutSeconds) {
        this(service, command, offerTodayCommand, jobsDbCommand, bossReader, projectCommand, List.of(), callback, timeoutSeconds);
    }

    WorkbenchRunner(WorkbenchService service, List<String> command, List<String> offerTodayCommand, List<String> jobsDbCommand,
            WorkbenchCandidates.Reader bossReader, List<String> projectCommand, List<String> verifierCommand, String callback, long timeoutSeconds) {
        this(service, command, offerTodayCommand, jobsDbCommand, bossReader, projectCommand, verifierCommand, callback, timeoutSeconds,
                new WorkbenchConnections(
                        () -> bossReader == null ? WorkbenchConnections.Status.NOT_CONFIGURED : WorkbenchConnections.Status.CHECK_AT_READ,
                        () -> jobsDbCommand.isEmpty() ? WorkbenchConnections.Status.NOT_CONFIGURED : WorkbenchConnections.Status.CHECK_AT_READ));
    }

    WorkbenchRunner(WorkbenchService service, List<String> command, List<String> offerTodayCommand, List<String> jobsDbCommand,
            WorkbenchCandidates.Reader bossReader, List<String> projectCommand, List<String> verifierCommand, String callback, long timeoutSeconds,
            WorkbenchConnections connections) {
        this.connections = connections;
        this.verifierCommand = List.copyOf(verifierCommand);
        if (!verifierCommand.isEmpty() && (verifierCommand.stream().noneMatch(arg -> arg.contains("{taskFile}")) || verifierCommand.stream().anyMatch(String::isBlank)
                || verifierCommand.equals(projectCommand) || verifierCommand.equals(command)))
            throw new IllegalArgumentException("独立验收器须单独配置，参数包含 {taskFile}，不可复用项目/官网命令");
        this.projectCommand = List.copyOf(projectCommand);
        if (!projectCommand.isEmpty() && (projectCommand.stream().noneMatch(arg -> arg.contains("{taskFile}")) || projectCommand.stream().anyMatch(String::isBlank)))
            throw new IllegalArgumentException("项目执行器参数必须包含 {taskFile} 且不能为空");
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
            service.projects.recover();
            service.verification.recover();
        } catch (Exception e) {
            try { if (lock != null) lock.release(); if (lockChannel != null) lockChannel.close(); } catch (Exception ignored) { }
            throw new IllegalStateException("工作台数据目录已被使用或无法锁定", e);
        }
    }

    public ObjectNode runtime() {
        var result = service.json.createObjectNode().put("agentConfigured", !command.isEmpty())
                .put("offerTodayConfigured", !offerTodayCommand.isEmpty()).put("busy", busy.get()).put("projectConfigured", !projectCommand.isEmpty()).put("verifierConfigured", !verifierCommand.isEmpty())
                .put("jobsDbConfigured", !jobsDbCommand.isEmpty()).put("jobsDbProductionVerified", false)
                .put("jobsDbOutboundSupported", false).put("jobsDbCapabilityReason", JobsDbTarget.OUTBOUND_BLOCK_REASON)
                .put("jobsDbSingleJobSupported", !jobsDbCommand.isEmpty())
                .put("jobsDbSingleJobReason", jobsDbCommand.isEmpty() ? "JobsDB 受控单岗申请未配置独立脚本" : "受控单岗 APPLY 可用；批次、联系、附件外发仍禁用")
                .put("bossReadConfigured", bossReader != null).put("bossOutboundSupported", bossSingleJob != null)
                .put("bossCvSupported", false)
                .put("bossCapabilityReason", bossSingleJob != null
                        ? "Boss 受控 CONTACT：只记录平台自动招呼，不发送自拟正文或 CV；需应用持有已登录 browser-data 会话，点击前再次核验"
                        : BossCandidateReader.OUTBOUND_BLOCK_REASON);
        result.set("connections", connections.view(service.json));
        return result;
    }

    void setBossSingleJobEffect(BossSingleJobEffect effect) { this.bossSingleJob = effect; }

    private List<String> effectCommand(String channel, boolean application) {
        var configured = switch (channel) {
            case "JOBSDB" -> jobsDbCommand;
            case "OFFERTODAY" -> offerTodayCommand;
            case "BOSS" -> application && bossSingleJob != null ? List.of("boss-single-job") : application ? List.<String>of() : command;
            default -> command;
        };
        return application ? service.effectCommand(channel, configured) : configured;
    }

    public synchronized ObjectNode start(String id) {
        if (closed) throw new WorkbenchException(409, "执行器已关闭，不能启动任务");
        var task = service.task(id);
        String channel = task.path("channel").asText();
        if (effectCommand(channel, task.path("kind").asText().equals("APPLICATION")).isEmpty())
            throw new WorkbenchException(409, "此渠道尚未配置单岗执行器；不会回退其他渠道");
        if (!busy.compareAndSet(false, true)) throw new WorkbenchException(409, "已有执行任务运行中，请等待或处理人工阻塞");
        try {
            var child = launch(id);
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
                    service.workflow.advance(cycle.path("id").asText(), candidateReader, this::analyzeCandidates, id -> {
                        if (closed || Thread.currentThread().isInterrupted()) throw new WorkbenchException(409, "执行器已停止；保留安全断点");
                        waitForTask(id, launch(id));
                    });
                } finally { busy.set(false); }
            });
            return cycle;
        } catch (RuntimeException e) { busy.set(false); throw e; }
    }

    /** Independent queue, serialized with existing resource users; never an application task or callback. */
    public synchronized ObjectNode startProject(String id, ObjectNode input) {
        if (closed || projectCommand.isEmpty()) throw new WorkbenchException(409, "尚未配置可信项目执行器，不能借用官网或申请执行器");
        var project = service.projects.queue(id, input);
        if (project.path("status").asText().equals("QUEUED")) executor.submit(() -> executeProject(id, project.path("queueId").asText()));
        return project;
    }
    public synchronized ObjectNode verifyProject(String id, ObjectNode input) {
        if (closed || verifierCommand.isEmpty()) throw new WorkbenchException(409, "尚未配置独立可信验收器，不能采信执行器自述");
        if (!busy.compareAndSet(false, true)) throw new WorkbenchException(409, "共享资源正在使用，不能并行验收");
        ObjectNode record = null;
        try {
            var verifier = verifierArguments();
            String identity = verifierIdentity(verifier);
            record = service.verification.begin(id, input, identity);
            if (!record.path("cached").asBoolean()) {
                var bundle = service.verification.bundle(record);
                long seconds = Math.min(timeoutSeconds, record.path("seconds").asLong());
                ((ObjectNode) bundle.path("limits")).put("seconds", seconds);
                Path run = service.verification.run(record), task = run.resolve("task.json");
                Files.writeString(task, bundle.toString());
                var args = verifier.stream().map(arg -> arg.replace("{taskFile}", task.toString())).toList();
                service.verification.checkRunning(record);
                Process child = spawn(new ProcessBuilder(args).directory(run.resolve("artifacts").toFile()).redirectErrorStream(true).redirectOutput(run.resolve("verification.log").toFile()));
                child.getOutputStream().close();
                long deadline = System.nanoTime() + seconds * 1_000_000_000L;
                while (!child.waitFor(75, TimeUnit.MILLISECONDS)) {
                    service.verification.checkRunning(record);
                    if (closed || System.nanoTime() >= deadline) throw new WorkbenchException(409, "独立验收时间预算耗尽或已停止");
                }
                WorkbenchService.require(child.exitValue() == 0, "独立验收进程失败；没有新增经历");
                releaseProcess();
                WorkbenchService.require(identity.equals(verifierIdentity(verifierArguments())), "验收程序身份已变化，拒绝过期证据");
                service.verification.finish(record, projectJson(run, run.resolve("result.json")), null);
            }
        } catch (Exception e) {
            releaseProcess();
            if (record == null) {
                if (e instanceof WorkbenchException exception) throw exception;
                throw new WorkbenchException(409, "独立验收配置或环境无效");
            }
            service.verification.finish(record, null, e instanceof WorkbenchException ? e.getMessage() : "独立验收失败或证据不可读取");
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        } finally {
            try { releaseProcess(); } finally { busy.set(false); }
        }
        for (var project : service.projects.workspace().path("projects")) if (project.path("id").asText().equals(id)) return (ObjectNode) project;
        throw new WorkbenchException(409, "项目不存在");
    }
    /** Narrow launch contract: a pinned executable, or Python with a pinned absolute script. Never resolve code in artifact cwd. */
    private List<String> verifierArguments() throws Exception {
        boolean python = verifierCommand.size() == 3 && Path.of(verifierCommand.get(0)).getFileName().toString().matches("python(?:3(?:\\.\\d+)?)?");
        WorkbenchService.require((verifierCommand.size() == 2 || python) && verifierCommand.get(verifierCommand.size() - 1).equals("{taskFile}"), "独立验收仅支持 [绝对可执行程序,{taskFile}] 或 [python3,绝对脚本,{taskFile}]，不接受相对代码、-m/-c 或 shell 参数");
        var resolved = new ArrayList<>(verifierCommand);
        Path executable = Path.of(resolved.get(0));
        if (python && !executable.isAbsolute()) {
            WorkbenchService.require(executable.getNameCount() == 1, "验收解释器不能是相对路径");
            Path installed = null;
            for (String directory : System.getenv().getOrDefault("PATH", "").split(java.io.File.pathSeparator)) {
                Path path = Path.of(directory);
                if (path.isAbsolute() && Files.isRegularFile(path.resolve(executable)) && Files.isExecutable(path.resolve(executable))) { installed = path.resolve(executable); break; }
            }
            WorkbenchService.require(installed != null, "找不到可信绝对路径的 Python 解释器"); executable = installed;
        }
        WorkbenchService.require(executable.isAbsolute() && Files.isRegularFile(executable) && Files.isExecutable(executable), "验收可执行程序须为已存在的可信绝对路径");
        resolved.set(0, executable.toRealPath().toString());
        if (python) WorkbenchService.require(Path.of(resolved.get(1)).isAbsolute() && Files.isRegularFile(Path.of(resolved.get(1))), "独立验收脚本须为已存在的可信绝对路径，不能执行项目产物或相对代码");
        return resolved;
    }
    private String verifierIdentity(List<String> verifier) throws Exception {
        var identity = service.json.createObjectNode(); identity.set("arguments", service.json.valueToTree(verifier));
        var files = identity.putArray("configuredFiles");
        for (String argument : verifier) if (!argument.equals("{taskFile}")) {
            Path file = Path.of(argument);
            service.projects.checkVerifierFile(file);
            for (String projectArgument : projectCommand.subList(Math.min(1, projectCommand.size()), projectCommand.size()))
                if (!projectArgument.contains("{taskFile}") && Path.of(projectArgument).isAbsolute() && Files.isRegularFile(Path.of(projectArgument)))
                    WorkbenchService.require(!Files.isSameFile(file, Path.of(projectArgument)), "独立验收不能复用项目执行脚本");
            WorkbenchService.require(!Files.isSymbolicLink(file) && Files.size(file) <= 10 * 1024 * 1024, "验收脚本须为有界普通文件");
            files.add(WorkbenchService.hash(Files.readAllBytes(file)));
        }
        return WorkbenchService.hash(identity.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    private void executeProject(String id, String queueId) {
        if (closed) return;
        // Existing asynchronous cycles finish in this same executor before queued project work.
        if (!busy.compareAndSet(false, true)) { service.projects.end(id, "BLOCKED", "共享资源正在使用；未开始项目，稍后显式恢复", null); return; }
        try {
            var project = service.projects.begin(id, queueId);
            if (project == null || !project.path("status").asText().equals("RUNNING")) return;
            var bundle = service.projects.bundle(project);
            long seconds = Math.min(timeoutSeconds, bundle.path("limits").path("seconds").asLong());
            ((ObjectNode) bundle.path("limits")).put("seconds", seconds);
            Path directory = Path.of(bundle.path("directory").asText());
            Path run = service.store.root.resolve("runs").resolve("project-" + bundle.path("attemptId").asText()); Files.createDirectories(run);
            Path task = run.resolve("task.json"); Files.writeString(task, service.json.writeValueAsString(bundle));
            try { Files.setPosixFilePermissions(task, PosixFilePermissions.fromString("rw-------")); }
            catch (UnsupportedOperationException ignored) { }
            var arguments = projectCommand.stream().map(arg -> arg.replace("{taskFile}", task.toString())).toList();
            Process child;
            // Serialize explicit stop with the final pre-launch check: cancelled queued work never spawns.
            synchronized (service.projects) {
                service.projects.checkRunning(id);
                if (closed || Thread.currentThread().isInterrupted()) throw new InterruptedException();
                child = spawn(new ProcessBuilder(arguments).directory(directory.toFile()).redirectErrorStream(true).redirectOutput(run.resolve("execution.log").toFile()));
                child.getOutputStream().close();
            }
            long deadline = System.nanoTime() + seconds * 1_000_000_000L;
            while (true) {
                service.projects.checkRunning(id);
                if (closed || Thread.currentThread().isInterrupted()) throw new InterruptedException();
                Path progress = Path.of(bundle.path("progressPath").asText());
                if (Files.exists(progress)) {
                    try {
                        var update = projectJson(directory, progress);
                        if (update != null) service.projects.progress(id, WorkbenchService.text(update, "message", 1000, true));
                    }
                    catch (com.fasterxml.jackson.core.JsonProcessingException ignored) { /* A progress writer may be between bytes; never use progress as completion evidence. */ }
                }
                if (child.waitFor(75, TimeUnit.MILLISECONDS)) break;
                if (System.nanoTime() >= deadline) throw new WorkbenchException(409, "项目执行时间预算耗尽；本次不退款，可按当前范围显式恢复");
            }
            service.projects.checkRunning(id);
            if (child.exitValue() != 0) throw new WorkbenchException(409, "项目执行器失败；产物未验证，不增加经历");
            releaseProcess();
            var evidence = service.projects.evidence(project, projectJson(directory, Path.of(bundle.path("resultPath").asText())));
            service.projects.end(id, "UNVERIFIED", "执行器报告及产物已留证，仍须独立验证；没有新增事实、简历或申请", evidence);
        } catch (InterruptedException e) {
            releaseProcess();
            service.projects.end(id, "STOPPED", "项目执行中断，保留未验证检查点；显式恢复会重新扣量", null);
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            String status = "FAILED";
            try { service.projects.checkRunning(id); }
            catch (Exception denied) { status = "STOPPED"; }
            releaseProcess();
            service.projects.end(id, status, e instanceof WorkbenchException ? e.getMessage() : "项目执行或产物读取失败，未确认经历", null);
        } finally {
            try { releaseProcess(); } finally { busy.set(false); }
        }
    }
    private ObjectNode projectJson(Path directory, Path file) throws Exception {
        Path path = directory;
        for (var part : directory.relativize(file)) { path = path.resolve(part); WorkbenchService.require(!Files.isSymbolicLink(path), "项目输出不能是符号链接"); }
        WorkbenchService.require(Files.isRegularFile(file) && Files.size(file) <= 64 * 1024 && file.toRealPath().startsWith(directory), "项目未返回有界有效进度/结果");
        var value = service.json.readTree(Files.readString(file));
        WorkbenchService.require(value == null || value instanceof ObjectNode, "项目进度/结果格式无效"); return (ObjectNode) value;
    }

    private record TaskProcess(Process child, String token, Path resultFile, Path taskFile, ObjectNode bundle) { }

    private void waitForTask(String id, TaskProcess running) {
        if (running.child() == null && running.bundle() != null) { runBossSingleJob(id, running); return; }
        Process child = running.child();
        try {
            if (!child.waitFor(service.task(id).path("executionSeconds").asLong(timeoutSeconds), TimeUnit.SECONDS)) {
                stopProcess(child);
                return;
            }
            if (running.resultFile() != null) {
                var payload = readSingleResult(running.resultFile(), id);
                service.jobsDbResult(id, payload, child.exitValue(), running.token());
            }
        }
        catch (InterruptedException e) { stopProcess(child); Thread.currentThread().interrupt(); }
        finally { try { releaseProcess(); } finally { service.processEnded(id); } }
    }

    private void runBossSingleJob(String id, TaskProcess running) {
        try {
            bossSingleJob.run(running.bundle(), event -> service.agentEvent(id, event, running.token()),
                    running.taskFile().getParent(), () -> closed || Thread.currentThread().isInterrupted());
        } catch (Exception e) {
            try {
                service.agentEvent(id, service.json.createObjectNode().put("type", "NEEDS_INPUT")
                        .put("message", e.getMessage() == null ? "Boss contact stopped" : e.getMessage())
                        .put("evidence", ""), running.token());
            } catch (Exception ignored) { }
        } finally { service.processEnded(id); }
    }

    private TaskProcess launch(String id) {
        var selectedTask = service.task(id);
        String channel = selectedTask.path("channel").asText();
        boolean application = selectedTask.path("kind").asText().equals("APPLICATION");
        boolean chat = channel.equals("OFFERTODAY") || channel.equals("BOSS") && application, jobsDb = channel.equals("JOBSDB");
        var effect = effectCommand(channel, application);
        if (effect.isEmpty()) throw new WorkbenchException(409, "此渠道尚未配置单岗执行器");
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
            if (channel.equals("BOSS") && application && bossSingleJob != null)
                return new TaskProcess(null, token, null, file, bundle);
            List<String> arguments;
            Path resultFile = null;
            if (jobsDb) {
                resultFile = directory.resolve("result.json");
                var task = (ObjectNode) bundle.path("task");
                var snapshot = task.path("snapshot");
                Files.createDirectories(directory.resolve("resume"));
                var material = service.preparation.approvedMaterial(task);
                String resumePath = copyFrozenResume(directory.resolve("resume"), material);
                var dispatch = service.json.createObjectNode().put("kind", "JOBSDB_SINGLE_APPLY").put("schemaVersion", 1).put("taskId", id).put("attemptId", id)
                        .put("stage", "APPLY").put("executionResourceId", task.path("executionResourceId").asText())
                        .put("jdSha256", WorkbenchService.hash(task.path("description").asText().getBytes(StandardCharsets.UTF_8)))
                        .put("jobId", task.path("jobId").asText()).put("url", task.path("url").asText())
                        .put("resumePath", resumePath).put("resumeSha256", snapshot.path("resume").path("sha256").asText())
                        .put("answersSha256", task.path("answersSha256").asText()).put("profileHash", jobsDbProfileHash())
                        .put("contactHash", WorkbenchService.hash(snapshot.path("contactFacts").toString().getBytes(StandardCharsets.UTF_8)))
                        .put("resultPath", resultFile.toString());
                dispatch.set("approvedFacts", material.facts());
                dispatch.set("answers", task.path("answers").deepCopy());
                dispatch.set("answersSource", task.path("answersSource").deepCopy());
                if (task.has("answerBank")) dispatch.set("answerBank", task.path("answerBank").deepCopy());
                // Launch reserves execution resources. UPLOAD consumes outbound budget before any file disclosure.
                service.store.write(c -> {
                    var current = service.store.get(c, "task", id);
                    current.set("jobsDbDispatch", dispatch.deepCopy());
                    for (String field : List.of("resumeSha256", "answersSha256", "profileHash", "contactHash")) current.set(field, dispatch.path(field));
                    service.store.save(c, "task", current); return null;
                });
                // Callback credentials live only in the private run file, never in public task/state.
                dispatch.put("callbackUrl", bundle.path("callbackUrl").asText()).put("token", token);
                Files.writeString(file, service.json.writeValueAsString(dispatch));
                arguments = new ArrayList<>(effect);
                arguments.add("apply-single"); arguments.add(file.toString()); arguments.add("--data-dir"); arguments.add(service.jobsDbDirectory().toString());
            } else if (chat) {
                arguments = new ArrayList<>(effect);
                arguments.add("run"); arguments.add(file.toString());
            } else arguments = effect.stream().map(arg -> arg.replace("{taskFile}", file.toString())).toList();
            var builder = new ProcessBuilder(arguments).redirectErrorStream(true).redirectOutput(directory.resolve("executor.log").toFile());
            builder.environment().put("WORKBENCH_TASK_FILE", file.toString());
            Process child = spawn(builder, bundle.path("task").has("packageId")); launched = child;
            child.getOutputStream().close();
            return new TaskProcess(child, token, resultFile, null, null);
        } catch (WorkbenchException e) { if (claimed) service.processEnded(id); throw e; }
        catch (Exception e) {
            if (launched != null) { stopProcess(launched); service.processEnded(id); }
            else if (claimed) service.startFailed(id);
            process = null;
            throw new WorkbenchException(409, launched == null ? "外部执行器未能启动；请检查本机命令配置，未发送申请" : "执行器启动后异常，请核实任务结果，不要重新投递");
        }
    }

    /** Compatibility alias: freeze confirmed answers, then run the existing durable task lifecycle. */
    public synchronized ObjectNode applyJobsDbSingle(ObjectNode input) {
        if (closed || effectCommand("JOBSDB", true).isEmpty()) throw new WorkbenchException(409, "JobsDB 单岗执行器未配置或已关闭；不会回退官网或旧 batch");
        if (!busy.compareAndSet(false, true)) throw new WorkbenchException(409, "已有执行任务运行中，请等待或处理人工阻塞");
        try {
            var task = service.jobsDbTask(input);
            String id = task.path("id").asText();
            waitForTask(id, launch(id));
            var result = service.task(id);
            result.put("status", result.path("state").asText());
            if (result.path("state").asText().equals("UNKNOWN")) {
                for (var event : result.path("events")) if (event.path("type").asText().equals("PROGRESS")) result.put("reason", event.path("message").asText());
            }
            return result;
        } catch (WorkbenchException e) { throw e; }
        catch (Exception e) { throw new WorkbenchException(409, "JobsDB 单岗申请未完成；保留同一 task，不自动重投"); }
        finally { busy.set(false); }
    }

    private String copyFrozenResume(Path directory, WorkbenchPreparation.ApprovedMaterial material) throws Exception {
        String sha = material.resumeSha256();
        String name = Path.of(material.filename()).getFileName().toString();
        if (!name.toLowerCase(Locale.ROOT).endsWith(".docx")) throw new WorkbenchException(409, "JobsDB 单岗附件须为可核验 DOCX");
        Path target = directory.resolve(name);
        Files.write(target, material.resumeBytes());
        try { Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-------")); } catch (UnsupportedOperationException ignored) { }
        if (!sha.equals(WorkbenchService.hash(Files.readAllBytes(target)))) throw new WorkbenchException(409, "冻结附件复制后哈希不一致");
        return target.toString();
    }

    private String jobsDbProfileHash() throws java.io.IOException {
        // Chrome rewrites Local State/Preferences during a run. Hash only the resolved isolated
        // profile root; account/session association is independently observed before every claim.
        Path root = service.jobsDbDirectory().toAbsolutePath().normalize();
        return WorkbenchService.hash(root.toFile().getCanonicalPath().getBytes(StandardCharsets.UTF_8));
    }

    private ObjectNode readSingleResult(Path path, String taskId) {
        try {
            if (!Files.isRegularFile(path)) return null;
            var node = service.json.readTree(Files.readString(path));
            return node instanceof ObjectNode result && result.path("schemaVersion").isInt() && result.path("schemaVersion").intValue() == 1
                    && result.path("kind").asText().equals("JOBSDB_SINGLE_APPLY") && taskId.equals(result.path("taskId").asText())
                    && taskId.equals(result.path("attemptId").asText()) ? result : null;
        } catch (Exception e) {
            return null;
        }
    }

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
        try { return service.candidates.discover(input, candidateReader); }
        finally { busy.set(false); }
    }

    private ObjectNode readCandidatePage(ObjectNode bundle) throws Exception {
        if (closed || Thread.currentThread().isInterrupted()) throw new WorkbenchException(409, "执行器已停止");
        String channel = bundle.path("source").path("channel").asText();
        String resumeReadId = bundle.path("resumeReadId").asText();
        boolean jobsDb = channel.equals("JOBSDB");
        if (jobsDb && !resumeReadId.isBlank()) {
            bundle.put("readAttemptId", resumeReadId);
            var resumed = resumedCandidateReads.remove(resumeReadId);
            if (resumed != null) {
                if (resumed.path("userActionRequired") instanceof ObjectNode action) {
                    var required = jobsDbUserAction(action, bundle, true);
                    pendingCandidateReads.put(required.interactionId(), new PendingCandidateRead(bundle.deepCopy()));
                    throw required;
                }
                return resumed;
            }
            pendingCandidateReads.putIfAbsent(resumeReadId, new PendingCandidateRead(bundle.deepCopy()));
            throw new WorkbenchCandidates.UserActionRequired(resumeReadId, jobsDbUserActionUrl(bundle.path("userActionUrl").asText(), bundle),
                    "JobsDB 要求用户在可见浏览器完成平台验证或登录", bundle.path("limits").path("seconds").asInt());
        }
        if (jobsDb && connections.status(channel) == WorkbenchConnections.Status.USER_ACTION_REQUIRED) {
            String interactionId = UUID.randomUUID().toString();
            bundle.put("readAttemptId", interactionId).put("userActionUrl", bundle.path("source").path("url").asText());
            pendingCandidateReads.put(interactionId, new PendingCandidateRead(bundle.deepCopy()));
            throw new WorkbenchCandidates.UserActionRequired(interactionId, bundle.path("userActionUrl").asText(),
                    "JobsDB 现有会话需在可见浏览器完成平台验证或登录；严格只读守卫不会被放宽",
                    bundle.path("limits").path("seconds").asInt());
        }
        connections.requireRead(channel);
        if (channel.equals("BOSS")) {
            if (bossReader == null) throw new WorkbenchException(409, "Boss 只读来源未连接本副本已授权会话；不会新建登录态或回退官网 AI");
            ((ObjectNode) bundle.path("limits")).put("seconds", Math.min(timeoutSeconds, bundle.path("limits").path("seconds").asLong()));
            return bossReader.read(bundle);
        }
        boolean offerToday = channel.equals("OFFERTODAY");
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
        if (jobsDb) bundle.put("readAttemptId", UUID.randomUUID().toString());
        Files.writeString(file, service.json.writeValueAsString(bundle));
        try { Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------")); }
        catch (UnsupportedOperationException ignored) { }
        List<String> arguments;
        if (offerToday || jobsDb) {
            arguments = new ArrayList<>(readerCommand); arguments.add("read-candidates"); arguments.add(file.toString());
            if (jobsDb) { arguments.add("--data-dir"); arguments.add(service.jobsDbDirectory().toString()); }
        } else arguments = command.stream().map(arg -> arg.replace("{taskFile}", file.toString())).toList();
        try {
            var child = spawn(new ProcessBuilder(arguments).redirectErrorStream(true).redirectOutput(directory.resolve("read.log").toFile()));
            child.getOutputStream().close();
            if (!child.waitFor(seconds, TimeUnit.SECONDS)) throw new WorkbenchException(409, "只读读取时间预算耗尽；保留断点");
            if (child.exitValue() != 0) throw new WorkbenchException(409, "只读来源未返回有效结果；检查会话、版本或本机执行器日志");
            var output = candidateOutput(result);
            if (output.has("exception")) throw new WorkbenchException(409, WorkbenchService.text(output, "exception", 1000, true));
            if (jobsDb && output.path("userActionRequired") instanceof ObjectNode action) {
                var required = jobsDbUserAction(action, bundle, false);
                pendingCandidateReads.put(required.interactionId(), new PendingCandidateRead(bundle.deepCopy()));
                throw required;
            }
            return output;
        } finally {
            releaseProcess();
        }
    }

    private boolean awaitJobsDbUserAction(WorkbenchCandidates.UserAction action) throws Exception {
        var pending = reconstructPendingCandidateRead(action);
        if (pending == null) pending = pendingCandidateReads.get(action.interactionId());
        if (resumedCandidateReads.containsKey(action.interactionId())) {
            pendingCandidateReads.remove(action.interactionId());
            return true;
        }
        if (pending == null || jobsDbCommand.isEmpty()) return false;
        Path directory = service.store.root.resolve("runs").resolve("candidates-resume-" + UUID.randomUUID());
        Files.createDirectories(directory);
        Path file = directory.resolve("task.json"), result = directory.resolve("result.json");
        var bundle = pending.bundle().deepCopy();
        int bundleSeconds = bundle.path("limits").path("seconds").asInt(-1);
        WorkbenchService.require(bundleSeconds >= 0 && action.remainingSeconds() >= 0, "JobsDB 持久断点剩余时间不匹配");
        int persistedSeconds = Math.min(bundleSeconds, action.remainingSeconds());
        ((ObjectNode) bundle.path("limits")).put("seconds", persistedSeconds);
        if (persistedSeconds == 0) {
            var output = service.json.createObjectNode();
            output.putObject("userActionRequired").put("interactionId", action.interactionId()).put("targetUrl", action.actionUrl())
                    .put("remainingSeconds", 0).put("message", "JobsDB 中断的严格读取已耗尽本页时间预算；不重置预算");
            resumedCandidateReads.put(action.interactionId(), output);
            pendingCandidateReads.remove(action.interactionId());
            return true;
        }
        bundle.put("resumeReadId", action.interactionId()).put("readAttemptId", action.interactionId())
                .put("userActionUrl", jobsDbUserActionUrl(action.actionUrl(), bundle)).put("resultPath", result.toString());
        Files.writeString(file, service.json.writeValueAsString(bundle));
        try { Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------")); }
        catch (UnsupportedOperationException ignored) { }
        markPendingResumeRun(action.interactionId(), directory.getFileName().toString());
        var arguments = new ArrayList<>(jobsDbCommand);
        arguments.add("resume-read"); arguments.add(file.toString()); arguments.add("--data-dir"); arguments.add(service.jobsDbDirectory().toString());
        try {
            var child = spawn(new ProcessBuilder(arguments).redirectErrorStream(true).redirectOutput(directory.resolve("read.log").toFile()));
            child.getOutputStream().close();
            Long strictStartedAt = null; boolean strictExpired = false;
            while (!child.waitFor(100, TimeUnit.MILLISECONDS)) {
                if (strictStartedAt == null) strictStartedAt = strictReadStartedAt(result, action.interactionId());
                if (strictStartedAt != null && System.currentTimeMillis() - strictStartedAt >= persistedSeconds * 1000L) {
                    stopProcess(child); strictExpired = true; break;
                }
                if (closed || Thread.currentThread().isInterrupted()) throw new InterruptedException("JobsDB 人工步骤已停止");
            }
            if (!strictExpired && strictStartedAt != null
                    && System.currentTimeMillis() - strictStartedAt >= persistedSeconds * 1000L) strictExpired = true;
            if (strictExpired) {
                var output = service.json.createObjectNode();
                output.putObject("userActionRequired").put("interactionId", action.interactionId()).put("targetUrl", action.actionUrl())
                        .put("remainingSeconds", 0).put("message", "JobsDB 严格续读达到本页硬截止；不接受迟到结果或重置预算");
                jobsDbUserAction((ObjectNode) output.path("userActionRequired"), bundle, true);
                markPendingResumeRun(action.interactionId(), null);
                resumedCandidateReads.put(action.interactionId(), output);
                pendingCandidateReads.remove(action.interactionId());
                return true;
            }
            if (child.exitValue() != 0) {
                var output = recoveredJobsDbOutput(action.interactionId(), persistedSeconds, result, bundle, false);
                if (output == null) throw new WorkbenchException(409, "JobsDB 用户步骤未完成；保留同一读取断点，不自动破解或重试");
                markPendingResumeRun(action.interactionId(), null);
                resumedCandidateReads.put(action.interactionId(), output);
                pendingCandidateReads.remove(action.interactionId());
                return true;
            }
            var output = candidateOutput(result);
            WorkbenchService.require(!output.has("exception") && (output.path("jobs").isArray() || output.path("userActionRequired") instanceof ObjectNode),
                    "JobsDB 用户步骤完成后未返回可核验只读结果；保留断点");
            if (output.path("jobs").isArray()) requireStrictReadCompletion(output, bundle, action.interactionId(), persistedSeconds, result);
            if (output.path("userActionRequired") instanceof ObjectNode nextAction) jobsDbUserAction(nextAction, bundle, true);
            markPendingResumeRun(action.interactionId(), null);
            resumedCandidateReads.put(action.interactionId(), output);
            pendingCandidateReads.remove(action.interactionId());
            return true;
        } finally {
            releaseProcess();
        }
    }

    private Long strictReadStartedAt(Path result, String interactionId) throws Exception {
        if (!Files.isRegularFile(result) || Files.isSymbolicLink(result) || Files.size(result) > 2 * 1024 * 1024) return null;
        try {
            var parsed = service.json.readTree(Files.readString(result));
            if (!(parsed instanceof ObjectNode output) || !(output.path("readAttemptInProgress") instanceof ObjectNode running)) return null;
            WorkbenchService.require(interactionId.equals(WorkbenchService.text(running, "interactionId", 120, true)),
                    "JobsDB 严格读取进度身份不匹配");
            var started = running.path("startedAtEpochMillis");
            WorkbenchService.require(started.isIntegralNumber() && started.asLong() > 0 && started.asLong() <= System.currentTimeMillis(),
                    "JobsDB 严格读取进度时间无效");
            return started.asLong();
        } catch (com.fasterxml.jackson.core.JsonProcessingException partialWrite) {
            return null;
        }
    }

    private void requireStrictReadCompletion(ObjectNode output, ObjectNode bundle, String interactionId, int budgetSeconds, Path result) throws Exception {
        var completion = output.path("readAttemptCompleted");
        WorkbenchService.require(completion instanceof ObjectNode,
                "JobsDB 严格读取结果缺少可核验完成时间；保留断点");
        var completed = (ObjectNode) completion;
        WorkbenchService.require(interactionId.equals(WorkbenchService.text(completed, "interactionId", 120, true)),
                "JobsDB 严格读取完成身份不匹配");
        String expectedTarget = jobsDbUserActionUrl(WorkbenchService.text(bundle, "userActionUrl", 3000, true), bundle);
        WorkbenchService.require(expectedTarget.equals(WorkbenchService.text(completed, "targetUrl", 3000, true)),
                "JobsDB 严格读取完成目标不匹配");
        WorkbenchService.require(completed.path("budgetSeconds").isIntegralNumber()
                        && completed.path("budgetSeconds").asInt() == budgetSeconds && budgetSeconds > 0,
                "JobsDB 严格读取完成预算不匹配");
        var started = completed.path("startedAtEpochMillis"); var finished = completed.path("completedAtEpochMillis");
        Path published = result.resolveSibling(result.getFileName() + ".published");
        WorkbenchService.require(Files.isRegularFile(published) && !Files.isSymbolicLink(published) && Files.size(published) == 64
                        && WorkbenchService.hash(Files.readAllBytes(result)).equals(Files.readString(published)),
                "JobsDB 严格读取结果缺少与落盘字节绑定的发布证据；保留断点");
        long now = System.currentTimeMillis(); long durableAt = Files.getLastModifiedTime(published).toMillis();
        WorkbenchService.require(started.isIntegralNumber() && finished.isIntegralNumber() && started.asLong() > 0
                        && finished.asLong() >= started.asLong() && finished.asLong() <= now
                        && finished.asLong() - started.asLong() < budgetSeconds * 1000L
                        && durableAt + 999L >= started.asLong() && durableAt - started.asLong() < budgetSeconds * 1000L,
                "JobsDB 严格读取结果超过本页硬截止；拒绝迟到或未及时持久化的结果且不重置预算");
    }

    private ObjectNode recoveredJobsDbOutput(String interactionId, int currentSeconds, Path result, ObjectNode bundle,
            boolean allowCompletedOutput) throws Exception {
        if (!Files.isRegularFile(result) || Files.isSymbolicLink(result) || Files.size(result) > 2 * 1024 * 1024) return null;
        var parsed = service.json.readTree(Files.readString(result));
        if (!(parsed instanceof ObjectNode output)) return null;
        if (output.path("readAttemptInProgress") instanceof ObjectNode running) {
            WorkbenchService.require(interactionId.equals(WorkbenchService.text(running, "interactionId", 120, true)),
                    "JobsDB 中断的严格读取身份不匹配");
            long now = System.currentTimeMillis();
            var started = running.path("startedAtEpochMillis");
            WorkbenchService.require(started.isIntegralNumber() && started.asLong() > 0 && started.asLong() <= now,
                    "JobsDB 中断的严格读取时间无效");
            long elapsed = Math.max(1L, (now - started.asLong() + 999L) / 1000L);
            int remaining = Math.max(0, currentSeconds - (int) Math.min(currentSeconds, elapsed));
            output = service.json.createObjectNode();
            output.putObject("userActionRequired").put("interactionId", interactionId)
                    .put("targetUrl", WorkbenchService.text(running, "targetUrl", 3000, false))
                    .put("remainingSeconds", remaining)
                    .put("message", remaining == 0 ? "JobsDB 严格续读中断且本页时间预算已耗尽；不重置预算"
                            : "JobsDB 严格续读中断；保留扣减后的预算并重新呈现同一用户断点");
            jobsDbUserAction((ObjectNode) output.path("userActionRequired"), bundle, true);
            return output;
        }
        if (!allowCompletedOutput || output.has("exception")) return null;
        if (output.path("userActionRequired") instanceof ObjectNode nextAction) {
            jobsDbUserAction(nextAction, bundle, true);
            return output;
        }
        if (!output.path("jobs").isArray()) return null;
        requireStrictReadCompletion(output, bundle, interactionId, currentSeconds, result);
        return output;
    }

    private void markPendingResumeRun(String interactionId, String resumeRunId) {
        service.store.write(c -> {
            int matches = 0;
            for (var run : service.store.list(c, "candidate_run")) for (var row : run.path("sources")) {
                if (!(row.path("pendingRead") instanceof ObjectNode pending)
                        || !pending.path("interactionId").asText().equals(interactionId)) continue;
                if (resumeRunId == null) pending.remove("resumeRunId"); else pending.put("resumeRunId", resumeRunId);
                service.store.save(c, "candidate_run", (ObjectNode) run); matches++;
            }
            WorkbenchService.require(matches == 1, "JobsDB 持久断点不存在或不唯一");
            return null;
        });
    }

    private PendingCandidateRead reconstructPendingCandidateRead(WorkbenchCandidates.UserAction action) throws Exception {
        var current = service.scope.workspace().path("current");
        return service.store.write(c -> {
            PendingCandidateRead found = null;
            for (var run : service.store.list(c, "candidate_run")) for (var row : run.path("sources")) {
                if (!(row instanceof ObjectNode source) || !(source.path("pendingRead") instanceof ObjectNode saved)
                        || !saved.path("interactionId").asText().equals(action.interactionId())) continue;
                WorkbenchService.require(found == null && source.path("channel").asText().equals(action.channel())
                        && source.path("url").asText().equals(action.sourceUrl())
                        && saved.path("userActionUrl").asText(source.path("url").asText()).equals(action.actionUrl())
                        && saved.path("limits").path("seconds").asInt(-1) == action.remainingSeconds()
                        && saved.path("scopeVersion").asInt(-1) == current.path("version").asInt(-2)
                        && current.path("status").asText().equals("ACTIVE"), "JobsDB 持久断点身份或当前范围不匹配");
                var bundle = service.json.createObjectNode().put("kind", "CANDIDATE_READ")
                        .put("scopeVersion", saved.path("scopeVersion").asInt())
                        .put("readAttemptId", action.interactionId()).put("userActionUrl", action.actionUrl());
                bundle.set("source", saved.path("target").deepCopy());
                bundle.set("limits", saved.path("limits").deepCopy());
                String resumeRunId = saved.path("resumeRunId").asText();
                if (!resumeRunId.isBlank()) {
                    WorkbenchService.require(resumeRunId.matches("candidates-resume-[A-Za-z0-9-]{1,80}"), "JobsDB 持久续读目录身份无效");
                    Path result = service.store.root.resolve("runs").resolve(resumeRunId).resolve("result.json");
                    var recovered = recoveredJobsDbOutput(action.interactionId(), saved.path("limits").path("seconds").asInt(), result, bundle, true);
                    if (recovered != null) {
                        ((ObjectNode) saved.path("limits")).put("seconds", bundle.path("limits").path("seconds").asInt());
                        resumedCandidateReads.put(action.interactionId(), recovered);
                    }
                    saved.remove("resumeRunId");
                    service.store.save(c, "candidate_run", (ObjectNode) run);
                }
                found = new PendingCandidateRead(bundle);
            }
            if (found != null) pendingCandidateReads.putIfAbsent(action.interactionId(), found);
            return found;
        });
    }

    private WorkbenchCandidates.UserActionRequired jobsDbUserAction(ObjectNode action, ObjectNode bundle, boolean remainingRequired) {
        String interactionId = WorkbenchService.text(action, "interactionId", 120, true);
        WorkbenchService.require(interactionId.equals(bundle.path("readAttemptId").asText()), "JobsDB 人工断点身份不匹配");
        String message = WorkbenchService.text(action, "message", 500, true);
        String actionUrl = jobsDbUserActionUrl(WorkbenchService.text(action, "targetUrl", 3000, false), bundle);
        Integer remaining = null;
        if (action.has("remainingSeconds")) {
            var value = action.path("remainingSeconds");
            WorkbenchService.require(value.isIntegralNumber() && value.asInt() >= 0
                    && value.asInt() <= bundle.path("limits").path("seconds").asInt(), "JobsDB 人工断点剩余时间无效");
            remaining = value.asInt();
            ((ObjectNode) bundle.path("limits")).put("seconds", remaining);
        } else WorkbenchService.require(!remainingRequired, "JobsDB 续读断点缺少剩余时间");
        bundle.put("userActionUrl", actionUrl);
        return new WorkbenchCandidates.UserActionRequired(interactionId, actionUrl, message, remaining);
    }

    private String jobsDbUserActionUrl(String value, ObjectNode bundle) {
        String source = bundle.path("source").path("url").asText();
        if (value == null || value.isBlank() || value.equals(source)) return source;
        try { return JobsDbTarget.parse(value).url(); }
        catch (WorkbenchException e) { throw new WorkbenchException(409, "JobsDB 人工断点目标越出当前列表或数字岗位详情"); }
    }

    private ObjectNode candidateOutput(Path result) throws Exception {
        if (!Files.isRegularFile(result) || Files.isSymbolicLink(result) || Files.size(result) > 2 * 1024 * 1024)
            throw new WorkbenchException(409, "只读来源未返回有效结果；检查会话、版本或本机执行器日志");
        var parsed = service.json.readTree(Files.readString(result));
        if (!(parsed instanceof ObjectNode output)) throw new WorkbenchException(409, "只读来源结果格式无效");
        return output;
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
