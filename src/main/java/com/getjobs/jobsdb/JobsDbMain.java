package com.getjobs.jobsdb;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** JobsDB-only subprocess boundary; other platform runtimes are never started. */
public final class JobsDbMain {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(System.getProperty("jobsdb.root", ".")).toAbsolutePath().normalize();
        Path python = root.resolve(".jobsdb/venv/bin/python");
        Path worker = root.resolve("scripts/jobsdb/jobsdb.py");
        if (!Files.isExecutable(python) || !Files.isRegularFile(worker)) {
            System.err.println("JobsDB runtime missing. Run: bash gradlew jobsdbSetup");
            System.exit(2);
        }
        List<String> command = new ArrayList<>(List.of(python.toString(), "-u", worker.toString()));
        command.addAll(Arrays.asList(args.length == 0 ? new String[]{"help"} : args));
        Process child = new ProcessBuilder(command).directory(root.toFile()).inheritIO().start();
        Thread cleanup = new Thread(() -> {
            if (!child.isAlive()) return;
            child.destroy();
            try { if (!child.waitFor(10, TimeUnit.SECONDS)) child.destroyForcibly(); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        });
        Runtime.getRuntime().addShutdownHook(cleanup);
        int status = child.waitFor();
        Runtime.getRuntime().removeShutdownHook(cleanup);
        System.exit(status);
    }
}
