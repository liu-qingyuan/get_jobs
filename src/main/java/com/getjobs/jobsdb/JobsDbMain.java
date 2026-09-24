package com.getjobs.jobsdb;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.WaitUntilState;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Opt-in terminal entry point. Deliberately does not start the shared Spring application. */
public final class JobsDbMain {
    private static final Path DATA = Path.of(".jobsdb").toAbsolutePath().normalize();
    private static final String HELP = """
        JobsDB HK — isolated, review-first Quick Apply
        login                     Open browser; sign in manually, then press Enter
        search <keywords> [pages] List jobs (1–5 pages, default 1); save .jobsdb/search.json
        prepare <job-url>         Walk Quick Apply, stop before submission
        apply <job-url>           Walk Quick Apply; requires typing SUBMIT <job-id>
        history                   Show submitted/unknown attempts; never retry unknown automatically
        help                      Show this help without starting a browser
        Use the English JobsDB site. Select/upload your resume and answer questions in the browser.
        Data: .jobsdb/ ONLY. No Boss browser, database, server, or account is used.
        """;

    public static void main(String[] args) throws Exception {
        String command = args.length == 0 ? "help" : args[0];
        if (command.equals("help")) { System.out.print(HELP); return; }
        if (!Set.of("login", "search", "prepare", "apply", "history").contains(command))
            throw new IllegalArgumentException("Unknown command; run help");
        boolean application = command.equals("prepare") || command.equals("apply");
        int expected = application ? 2 : 1;
        if (command.equals("search")) {
            if (args.length < 2 || args.length > 3 || args[1].isBlank()) throw new IllegalArgumentException("search <keywords> [pages]");
        } else if (args.length != expected) throw new IllegalArgumentException("Unexpected arguments; run help");
        String id = application ? JobsDbFlow.jobId(args[1]) : null;
        int pages = command.equals("search") && args.length == 3 ? Integer.parseInt(args[2]) : 1;
        if (pages < 1 || pages > 5) throw new IllegalArgumentException("pages must be 1–5");
        Files.createDirectories(DATA);
        try (FileChannel channel = FileChannel.open(DATA.resolve("run.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lock = channel.tryLock()) {
            if (lock == null) throw new IllegalStateException("Another JobsDB command is using this profile");
            try (JobsDbStore store = new JobsDbStore(DATA.resolve("applications.db"))) {
                if (command.equals("history")) { store.history().forEach(System.out::println); return; }
                if (application && store.contains(id)) {
                    System.out.println("SKIPPED: previous submission/unknown outcome for " + id + "; check history and JobsDB.");
                    return;
                }
                runBrowser(command, args, id, pages, store);
            }
        }
    }

    private static void runBrowser(String command, String[] args, String id, int pages, JobsDbStore store) throws Exception {
        try (Playwright playwright = Playwright.create();
             BrowserContext context = playwright.chromium().launchPersistentContext(DATA.resolve("browser-profile"),
                     new BrowserType.LaunchPersistentContextOptions().setHeadless(false).setLocale("en-HK"))) {
            // Own context only; never attach over CDP or touch another Chrome session.
            Page page = context.newPage();
            JobsDbFlow flow = new JobsDbFlow(page);
            BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            try {
                switch (command) {
                    case "login" -> {
                        page.navigate("https://hk.jobsdb.com/", new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                        prompt(input, "在此独立窗口登录 JobsDB，完成后按 Enter 保存会话并关闭。");
                        System.out.println("SESSION_SAVED: login status must be checked in browser.");
                    }
                    case "search" -> {
                        List<JobsDbFlow.Job> jobs;
                        while (true) {
                            try { jobs = flow.search(args[1], pages); break; }
                            catch (IllegalStateException e) {
                                screenshot(page, "search-needs-input");
                                System.out.println("NEEDS_INPUT: " + e.getMessage());
                                String answer = prompt(input, "请在浏览器完成登录/站点验证或检查无结果原因；输入 retry 重新搜索，其他输入取消。");
                                if (!"retry".equals(answer)) { System.out.println("CANCELLED"); return; }
                            }
                        }
                        JSONArray result = new JSONArray();
                        for (JobsDbFlow.Job job : jobs) {
                            JSONObject row = new JSONObject().put("id", job.id()).put("title", job.title())
                                    .put("url", job.url()).put("attempted", store.contains(job.id()));
                            result.put(row);
                            System.out.println(job.id() + "\t" + job.title() + "\t" + job.url() + (store.contains(job.id()) ? "\tATTEMPTED" : ""));
                        }
                        Files.writeString(DATA.resolve("search.json"), result.toString(2));
                        System.out.println("FOUND=" + jobs.size());
                    }
                    case "prepare", "apply" -> {
                        JobsDbFlow.State state = flow.open(args[1]);
                        while (state == JobsDbFlow.State.NEEDS_INPUT) {
                            String answer = prompt(input, "请在浏览器补充简历/答案或处理登录验证。勿手动提交；输入 continue 重新检测，其他输入取消。");
                            if (!"continue".equals(answer)) { System.out.println("CANCELLED"); return; }
                            state = flow.advance();
                        }
                        System.out.println("STATE=" + state);
                        if (state != JobsDbFlow.State.REVIEW) return;
                        screenshot(page, id + "-review");
                        if (command.equals("prepare")) {
                            prompt(input, "PREPARED: 请检查当前表单；本命令不点击提交。按 Enter 关闭。");
                            return;
                        }
                        String confirmation = prompt(input, "核对公司、岗位、简历、所有问题答案。确认投递 " + args[1] + "，请输入 SUBMIT " + id + "；其他输入取消。");
                        if (!("SUBMIT " + id).equals(confirmation)) { System.out.println("CANCELLED"); return; }
                        if (!store.claim(id)) { System.out.println("SKIPPED: already claimed"); return; }
                        JobsDbFlow.State outcome = flow.submit();
                        // Even a failure just before clicking stays UNKNOWN; no automated resubmission.
                        if (outcome != JobsDbFlow.State.SUBMITTED) outcome = JobsDbFlow.State.UNKNOWN;
                        store.finish(id, outcome);
                        screenshot(page, id + "-" + outcome);
                        System.out.println("RESULT=" + outcome);
                    }
                    default -> throw new IllegalStateException(command);
                }
            } catch (Exception e) {
                screenshot(page, "failure");
                throw e;
            }
        }
    }

    private static String prompt(BufferedReader input, String message) throws IOException {
        System.out.println(message);
        return input.readLine(); // EOF means cancel, never consent.
    }
    private static void screenshot(Page page, String name) {
        try {
            Path path = DATA.resolve("screenshots"); Files.createDirectories(path);
            page.screenshot(new Page.ScreenshotOptions().setPath(path.resolve(name + ".png")));
        } catch (Exception e) { System.err.println("Screenshot unavailable: " + e.getMessage()); }
    }
}
