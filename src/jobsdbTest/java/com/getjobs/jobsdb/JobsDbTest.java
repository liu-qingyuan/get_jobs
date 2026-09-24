package com.getjobs.jobsdb;

import com.microsoft.playwright.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Executable contracts without another test dependency. All browser requests are intercepted. */
public final class JobsDbTest {
    private static int passed;
    private static final String URL = "https://hk.jobsdb.com/job/12345678";
    public static void main(String[] args) throws Exception {
        String driver = System.getProperty("playwright.cli.dir", "");
        equal(true, !driver.isBlank() && Files.isRegularFile(Path.of(driver, "package", "cli.js")),
                "JobsDB tasks explicitly use the installed Patchright driver");
        equal(true, System.getenv("PLAYWRIGHT_NODEJS_PATH") != null,
                "custom driver has an explicit Node runtime");
        equal("12345678", JobsDbFlow.jobId(URL + "?ref=search"), "canonical ID strips tracking");
        for (String url : List.of("http://hk.jobsdb.com/job/123", "https://evil.test/job/123",
                "https://hk.jobsdb.com.evil.test/job/123", "https://me@hk.jobsdb.com/job/123",
                "https://hk.jobsdb.com:444/job/123", "https://hk.jobsdb.com/job/abc",
                "https://hk.jobsdb.com/job/123/apply", "file:///job/123")) {
            rejects(() -> JobsDbFlow.jobId(url), "reject noncanonical " + url);
        }
        Path directory = Files.createTempDirectory("jobsdb-contract-");
        Path database = directory.resolve("applications.db");
        try {
            try (JobsDbStore store = new JobsDbStore(database)) {
                equal(false, store.contains("123"), "new job is not attempted");
                equal(true, store.claim("123"), "claim before submit");
                equal(false, store.claim("123"), "duplicate claim blocked");
                equal(true, store.history().getFirst().contains("UNKNOWN"), "claim persists UNKNOWN before click");
            }
            try (JobsDbStore store = new JobsDbStore(database)) {
                equal(false, store.claim("123"), "crash/reopen cannot retry claim");
                store.finish("123", JobsDbFlow.State.SUBMITTED);
                equal(true, store.history().getFirst().contains("SUBMITTED"), "success stored");
                rejects(() -> store.finish("123", JobsDbFlow.State.REVIEW), "reject nonterminal outcome");
                rejects(() -> store.finish("missing", JobsDbFlow.State.SUBMITTED), "require claim before result");
            }
            try (JobsDbStore first = new JobsDbStore(database); JobsDbStore second = new JobsDbStore(database)) {
                equal(true, first.claim("456"), "first connection claims");
                equal(false, second.claim("456"), "second connection cannot claim same job");
            }
        } finally {
            try (var files = Files.walk(directory)) {
                for (Path p : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
            }
        }
        try (Playwright playwright = Playwright.create(); Browser browser = playwright.chromium().launch(
                new BrowserType.LaunchOptions().setHeadless(true))) {
            testSearch(browser);
            testReviewAndSubmit(browser);
            testQuestions(browser);
            testSkip(browser);
            testIdentity(browser);
            testUnknown(browser);
        }
        System.out.println("PASS " + passed + " assertions; all browser requests intercepted; real submissions=0");
    }

    private static void testSearch(Browser browser) {
        try (Fixture f = new Fixture(browser)) {
            f.search = "<article><a data-automation='jobTitle' href='/job/12345678?ref=x'>Java Engineer</a></article>"
                    + "<article><a data-automation='jobTitle' href='/job/12345678?ref=y'>Duplicate</a></article>"
                    + "<article><a data-automation='jobTitle' href='https://evil.test/job/987'>External</a></article>";
            var results = f.flow.search("Java engineer", 2);
            equal(1, results.size(), "deduplicate across cards and pages; ignore external jobs");
            equal(URL, results.getFirst().url(), "canonical search URL");
            equal("Java Engineer", results.getFirst().title(), "keep job title");
            equal(2, f.searches.get(), "bounded pagination");
            rejects(() -> f.flow.search("java", 6), "reject unbounded page count");
        }
    }

    private static void testReviewAndSubmit(Browser browser) {
        try (Fixture f = new Fixture(browser)) {
            equal(JobsDbFlow.State.REVIEW, f.flow.open(URL), "prepare stops at exact final button");
            equal(0, f.submissions.get(), "prepare never submits");
            equal(0, f.wrongClicks.get(), "ignore Review and submit step indicator");
            equal(JobsDbFlow.State.SUBMITTED, f.flow.submit(), "positive success confirmation");
            equal(1, f.submissions.get(), "exactly one submission click");
        }
    }

    private static void testQuestions(Browser browser) {
        try (Fixture f = new Fixture(browser)) {
            f.start = "<form><label>Work authorization<select required id='answer'><option value=''>Choose</option>"
                    + "<option value='yes'>Yes</option></select></label>"
                    + "<button type='button' onclick=\"location.href='/job/12345678/apply/review'\">Continue</button></form>";
            equal(JobsDbFlow.State.NEEDS_INPUT, f.flow.open(URL), "unanswered required question pauses");
            equal("", f.page.locator("#answer").inputValue(), "no invented answer");
            equal(0, f.submissions.get(), "question pause does not submit");
            f.page.locator("#answer").selectOption("yes");
            equal(JobsDbFlow.State.REVIEW, f.flow.advance(), "resume after user answers");
            f.page.locator("body").evaluate("el => el.insertAdjacentHTML('afterbegin', '<input aria-invalid=\"true\">')");
            equal(JobsDbFlow.State.NEEDS_INPUT, f.flow.submit(), "validation errors block final click");
            equal(0, f.submissions.get(), "invalid state zero clicks");
        }
        try (Fixture f = new Fixture(browser)) {
            f.start = "<p>Verify you are human</p><button>Continue</button>";
            equal(JobsDbFlow.State.NEEDS_INPUT, f.flow.open(URL), "challenge pauses");
            equal(0, f.submissions.get(), "challenge zero clicks");
        }
        try (Fixture f = new Fixture(browser)) {
            f.start = "<button>Continue</button>";
            equal(JobsDbFlow.State.NEEDS_INPUT, f.flow.open(URL), "stalled Continue is bounded");
        }
    }

    private static void testSkip(Browser browser) {
        try (Fixture f = new Fixture(browser)) {
            f.detail = "<h1>Performing security verification</h1>";
            equal(JobsDbFlow.State.NEEDS_INPUT, f.flow.open(URL), "job detail verification is not mistaken for unsupported Apply");
            f.detail = "<h1>Engineer</h1><a href='/job/12345678/apply/start'>Quick apply</a>";
            equal(JobsDbFlow.State.REVIEW, f.flow.advance(), "resume detail after manual verification");
        }
        try (Fixture f = new Fixture(browser)) {
            f.detail = "<a href='https://external.test/apply'>Apply</a>";
            equal(JobsDbFlow.State.SKIPPED, f.flow.open(URL), "standard Apply skipped");
            equal(0, f.external.get(), "external ATS not visited");
        }
        try (Fixture f = new Fixture(browser)) {
            f.detail = "<a href='https://external.test/apply'>Quick apply</a>";
            equal(JobsDbFlow.State.SKIPPED, f.flow.open(URL), "external Quick Apply skipped");
            equal(0, f.external.get(), "external Quick Apply not visited");
        }
        try (Fixture f = new Fixture(browser)) {
            f.detail = "<span data-automation='applied-badge'>Applied</span>";
            equal(JobsDbFlow.State.ALREADY_APPLIED, f.flow.open(URL), "site applied badge respected");
        }
    }

    private static void testIdentity(Browser browser) {
        try (Fixture f = new Fixture(browser)) {
            equal(JobsDbFlow.State.REVIEW, f.flow.open(URL), "review before identity change");
            f.page.navigate("https://hk.jobsdb.com/job/87654321/apply/review");
            equal(JobsDbFlow.State.NEEDS_INPUT, f.flow.submit(), "wrong job identity blocks submission");
            equal(0, f.submissions.get(), "wrong job zero clicks");
        }
        try (Fixture f = new Fixture(browser)) {
            f.flow.open(URL);
            f.page.navigate("https://external.test/job/12345678/apply/review");
            equal(JobsDbFlow.State.NEEDS_INPUT, f.flow.submit(), "external origin blocks submission");
        }
    }

    private static void testUnknown(Browser browser) {
        try (Fixture f = new Fixture(browser)) {
            f.success = "<h1>Something went wrong</h1>";
            f.flow.open(URL);
            equal(JobsDbFlow.State.UNKNOWN, f.flow.submit(), "URL change alone is not success");
            equal(1, f.submissions.get(), "unknown outcome does not retry");
        }
    }

    private static void equal(Object expected, Object actual, String message) {
        if (!Objects.equals(expected, actual)) throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        passed++; System.out.println("PASS " + message);
    }
    private static void rejects(Checked action, String message) {
        try { action.run(); } catch (Exception e) { passed++; System.out.println("PASS " + message); return; }
        throw new AssertionError(message + ": expected exception");
    }
    @FunctionalInterface private interface Checked { void run() throws Exception; }

    private static final class Fixture implements AutoCloseable {
        final BrowserContext context;
        final Page page;
        final JobsDbFlow flow;
        final AtomicInteger submissions = new AtomicInteger(), wrongClicks = new AtomicInteger(), external = new AtomicInteger(), searches = new AtomicInteger();
        String detail = "<h1>Engineer</h1><a href='/job/12345678/apply/start'>Quick apply</a>";
        String start = "<p>Resume selected</p><button onclick=\"location.href='/job/12345678/apply/review'\">Continue</button>";
        String search = "";
        String success = "<h1>Your application has been submitted.</h1>";
        final String review = "<h1>Review</h1><button type='submit' onclick=\"fetch('/wrong')\">Review and submit</button>"
                + "<button type='submit' onclick=\"location.href='/job/12345678/apply/success'\">Submit application</button>";
        Fixture(Browser browser) {
            context = browser.newContext(new Browser.NewContextOptions().setServiceWorkers(com.microsoft.playwright.options.ServiceWorkerPolicy.BLOCK));
            context.route("**/*", route -> {
                URI uri = URI.create(route.request().url());
                String path = uri.getPath(), html;
                if (!"hk.jobsdb.com".equals(uri.getHost())) external.incrementAndGet();
                if (path.equals("/jobs")) { searches.incrementAndGet(); html = search; }
                else if (path.endsWith("/apply/start")) html = start;
                else if (path.endsWith("/apply/review")) html = review;
                else if (path.endsWith("/apply/success")) { submissions.incrementAndGet(); html = success; }
                else if (path.equals("/wrong")) { wrongClicks.incrementAndGet(); html = "wrong"; }
                else html = detail;
                route.fulfill(new Route.FulfillOptions().setContentType("text/html").setBody("<!doctype html><html><body>" + html + "</body></html>"));
            });
            page = context.newPage(); flow = new JobsDbFlow(page);
            page.setDefaultTimeout(5000); page.setDefaultNavigationTimeout(5000);
        }
        @Override public void close() { context.close(); }
    }
}
