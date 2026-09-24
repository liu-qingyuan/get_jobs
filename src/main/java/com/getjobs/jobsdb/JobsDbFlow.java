package com.getjobs.jobsdb;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.WaitUntilState;
import com.microsoft.playwright.options.WaitForSelectorState;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

/** JobsDB's changing DOM belongs here. No Spring, AI guesses, or external ATS actions. */
public final class JobsDbFlow {
    public enum State { REVIEW, NEEDS_INPUT, SKIPPED, ALREADY_APPLIED, SUBMITTED, UNKNOWN }
    public record Job(String id, String title, String url) {}
    private static final Pattern QUICK = Pattern.compile("^Quick apply$", Pattern.CASE_INSENSITIVE);
    private static final Pattern NEXT = Pattern.compile("^(Continue|Next)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SUBMIT = Pattern.compile("^Submit application$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SUCCESS = Pattern.compile(
            "^(Your application has been (submitted|sent)|Application (submitted|sent)|Successfully submitted)[.!]?$",
            Pattern.CASE_INSENSITIVE);
    private final Page page;
    private String activeId;

    public JobsDbFlow(Page page) {
        this.page = page;
        page.setDefaultTimeout(10_000);
        page.setDefaultNavigationTimeout(30_000);
    }

    public static String jobId(String url) {
        URI uri = URI.create(url);
        if (!isJobsDb(uri) || !uri.getPath().matches("/job/[0-9]+/?"))
            throw new IllegalArgumentException("Expected https://hk.jobsdb.com/job/<numeric-id>");
        return uri.getPath().split("/")[2];
    }

    private static boolean isJobsDb(URI uri) {
        return "https".equals(uri.getScheme()) && "hk.jobsdb.com".equalsIgnoreCase(uri.getHost())
                && uri.getUserInfo() == null && (uri.getPort() == -1 || uri.getPort() == 443);
    }

    public List<Job> search(String keywords, int pages) {
        if (keywords.isBlank() || pages < 1 || pages > 5)
            throw new IllegalArgumentException("Search requires keywords and 1–5 pages");
        Map<String, Job> jobs = new LinkedHashMap<>();
        for (int n = 1; n <= pages; n++) {
            page.navigate("https://hk.jobsdb.com/jobs?keywords="
                    + URLEncoder.encode(keywords, StandardCharsets.UTF_8) + "&page=" + n,
                    new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            if (!isJobsDb(URI.create(page.url()))) throw new IllegalStateException("Search left JobsDB");
            Locator links = page.locator("a[data-automation='jobTitle'], a[data-automation='job-title'], article a[href*='/job/']");
            try { links.first().waitFor(); }
            catch (TimeoutError e) { throw new IllegalStateException("No job cards detected; inspect login, challenge, or empty search in browser", e); }
            for (Locator link : links.all()) {
                String href = link.getAttribute("href");
                if (href == null) continue;
                try {
                    String absolute = URI.create(page.url()).resolve(href).toString();
                    String id = jobId(absolute);
                    jobs.putIfAbsent(id, new Job(id, link.innerText().strip(), "https://hk.jobsdb.com/job/" + id));
                } catch (IllegalArgumentException ignored) { /* Non-job or external links are not candidates. */ }
            }
        }
        return List.copyOf(jobs.values());
    }

    public State open(String jobUrl) {
        activeId = jobId(jobUrl);
        page.navigate("https://hk.jobsdb.com/job/" + activeId,
                new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
        if (!onActiveJob() || challengeOrInvalid()) return State.NEEDS_INPUT;
        if (visible(page.locator("[data-automation='applied-badge']"))) return State.ALREADY_APPLIED;
        Locator quick = page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName(QUICK))
                .or(page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName(QUICK)));
        try { quick.first().waitFor(); }
        catch (TimeoutError e) { return State.SKIPPED; }
        Locator button = firstVisible(quick);
        if (button == null) return State.SKIPPED;
        String href = button.getAttribute("href");
        if (href != null) {
            URI target = URI.create(page.url()).resolve(href);
            if (!isJobsDb(target) || !target.getPath().startsWith("/job/" + activeId + "/apply"))
                return State.SKIPPED;
        }
        button.click();
        try { page.waitForURL("**/job/" + activeId + "/apply**"); }
        catch (TimeoutError e) { return State.NEEDS_INPUT; }
        return advance();
    }

    /** Moves only through known navigation; never fills unanswered questions or submits. */
    public State advance() {
        if (onActiveJob() && !onApplication()) return open("https://hk.jobsdb.com/job/" + activeId);
        for (int step = 0; step < 8; step++) {
            if (!onApplication() || challengeOrInvalid()) return State.NEEDS_INPUT;
            if (firstVisible(submitButton()) != null) return State.REVIEW;
            Locator next = firstVisible(page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName(NEXT)));
            if (next == null || !next.isEnabled()) return State.NEEDS_INPUT;
            String before = page.url() + "\n" + page.locator("body").innerText();
            next.click();
            try {
                page.waitForFunction("before => location.href + '\\n' + document.body.innerText !== before", before);
            } catch (TimeoutError e) { return State.NEEDS_INPUT; }
        }
        return State.NEEDS_INPUT;
    }

    /** Caller must atomically claim the ID and obtain user confirmation before invoking this. */
    public State submit() {
        if (!onApplication() || challengeOrInvalid()) return State.NEEDS_INPUT;
        Locator submit = firstVisible(submitButton());
        if (submit == null || !submit.isEnabled()) return State.NEEDS_INPUT;
        try {
            submit.click(); // Exactly one click; a timeout is an unknown outcome, never a retry.
            page.getByText(SUCCESS).first().waitFor(
                    new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
            return onActiveJob() ? State.SUBMITTED : State.UNKNOWN;
        } catch (PlaywrightException e) {
            return State.UNKNOWN;
        }
    }

    private Locator submitButton() {
        return page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName(SUBMIT));
    }

    private boolean onActiveJob() {
        URI uri = URI.create(page.url());
        return activeId != null && isJobsDb(uri)
                && uri.getPath().matches("/job/" + activeId + "(?:/.*)?");
    }

    private boolean onApplication() {
        return onActiveJob() && URI.create(page.url()).getPath().matches("/job/" + activeId + "/apply(?:/.*)?");
    }

    private boolean challengeOrInvalid() {
        if (visible(page.locator("iframe[src*='recaptcha'], iframe[src*='hcaptcha'], iframe[src*='challenges.cloudflare.com'], [aria-invalid='true'], [data-automation='validation-error']")))
            return true;
        if (visible(page.getByText(Pattern.compile("^(Verify you are human|Performing security verification|Security check|Sign in to continue)$", Pattern.CASE_INSENSITIVE))))
            return true;
        return (Boolean) page.evaluate("""
            () => [...document.querySelectorAll('input,select,textarea')].some(el =>
                el.getClientRects().length && !el.disabled && !el.checkValidity())
            """);
    }

    private static boolean visible(Locator locator) { return firstVisible(locator) != null; }
    private static Locator firstVisible(Locator locator) {
        for (Locator item : locator.all()) if (item.isVisible()) return item;
        return null;
    }
}
