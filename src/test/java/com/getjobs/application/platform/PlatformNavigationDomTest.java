package com.getjobs.application.platform;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Response;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 平台导航页面的可复现页面级测试。
 *
 * <p>只在显式启用时运行：{@code PLATFORM_NAV_DOM_TEST=true}。使用 JDK 自带
 * {@link HttpServer} 服务 {@code front/out} 静态导出，API 固定返回 503 模拟未连接；
 * Playwright 只访问 127.0.0.1，外部请求会被记录并中止。不访问真实平台、账号或 profile。</p>
 */
@EnabledIfEnvironmentVariable(named = "PLATFORM_NAV_DOM_TEST", matches = "true")
class PlatformNavigationDomTest {
    private static final Path OUT = Path.of("front", "out").toAbsolutePath().normalize();
    private static final List<String> REQUESTS = Collections.synchronizedList(new ArrayList<>());
    private static final Map<String, String> ROUTES = Map.of(
            "/", "/index.html",
            "/platforms", "/platforms.html",
            "/boss", "/boss.html",
            "/liepin", "/liepin.html",
            "/51job", "/51job.html",
            "/zhilian", "/zhilian.html",
            "/workbench", "/workbench.html",
            "/env-config", "/env-config.html",
            "/ai-config", "/ai-config.html");
    private static final Map<String, String> TYPES = new LinkedHashMap<>();

    static {
        TYPES.put("html", "text/html; charset=utf-8");
        TYPES.put("js", "text/javascript; charset=utf-8");
        TYPES.put("mjs", "text/javascript; charset=utf-8");
        TYPES.put("css", "text/css; charset=utf-8");
        TYPES.put("json", "application/json; charset=utf-8");
        TYPES.put("txt", "text/plain; charset=utf-8");
        TYPES.put("svg", "image/svg+xml");
        TYPES.put("ico", "image/x-icon");
        TYPES.put("woff2", "font/woff2");
        TYPES.put("png", "image/png");
        TYPES.put("map", "application/json; charset=utf-8");
    }

    private static HttpServer server;
    private static ExecutorService serverExecutor;
    private static Playwright playwright;
    private static Browser browser;
    private static String base;

    @BeforeAll
    static void startStaticSiteAndBrowser() throws Exception {
        assertTrue(Files.isDirectory(OUT), "缺少前端静态导出，请先构建：cd front && node node_modules/next/dist/bin/next build");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverExecutor = Executors.newCachedThreadPool();
        server.setExecutor(serverExecutor);
        server.createContext("/", PlatformNavigationDomTest::handle);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();

        playwright = Playwright.create();
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
    }

    @AfterAll
    static void stop() {
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
        if (server != null) server.stop(0);
        if (serverExecutor != null) serverExecutor.shutdownNow();
    }

    @Test
    @Timeout(90)
    void rowsLinksAndLocalOnlyRequests() {
        REQUESTS.clear();
        BrowserContext context = browser.newContext();
        context.route("**/*", route -> {
            String method = route.request().method();
            String url = route.request().url();
            REQUESTS.add(method + " " + url);
            URI uri = URI.create(url);
            if (!Set.of("127.0.0.1", "localhost").contains(uri.getHost())) {
                route.abort();
                return;
            }
            route.resume();
        });
        Page page = context.newPage();
        Response initial = page.navigate(base + "/platforms");
        assertNotNull(initial);
        assertEquals(200, initial.status());
        page.waitForSelector("table[data-testid=\"platform-navigation\"]");
        // API 固定返回 503；静态行仍必须可见，页面显示“未连接”告警。
        page.locator("p[role='alert']").first().waitFor();
        assertTrue(page.locator("p[role='alert']").first().textContent().contains("运行时状态未连接"));

        List<String> names = page
                .locator("table[data-testid=\"platform-navigation\"] tbody tr th[scope=row] > span:first-child")
                .allTextContents().stream().map(String::trim).collect(Collectors.toList());
        assertEquals(List.of("Boss", "Liepin", "51job", "Zhilian", "OfferToday", "JobsDB", "公司官网"), names);
        for (String name : names) assertEquals(1, names.stream().filter(name::equals).count(), name);
        assertEquals(7, page.locator("table[data-testid=\"platform-navigation\"] tbody tr").count());

        var boss = page.locator("tr[data-platform-row=boss]");
        assertEquals(1, boss.locator("a[href='/boss']").count());
        assertEquals(1, boss.locator("a[href='/workbench']").count());

        for (String path : List.of("/boss", "/liepin", "/51job", "/zhilian", "/workbench")) {
            Response response = page.navigate(base + path);
            assertNotNull(response, path);
            assertEquals(200, response.status(), path);
            assertTrue(page.url().startsWith(base + path), path + " 不应离开 localhost 静态页：" + page.url());
        }

        assertFalse(REQUESTS.isEmpty());
        for (String entry : REQUESTS) {
            int separator = entry.indexOf(' ');
            String method = entry.substring(0, separator);
            String url = entry.substring(separator + 1);
            URI uri = URI.create(url);
            assertTrue(Set.of("GET", "HEAD").contains(method), "非只读请求：" + entry);
            assertTrue(Set.of("127.0.0.1", "localhost").contains(uri.getHost()), "外部请求：" + entry);
        }
        assertTrue(REQUESTS.stream().anyMatch(entry -> entry.contains("/api/workbench/state")),
                "页面应请求既有状态接口以展示运行时状态");
    }

    private static void handle(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            if (!Set.of("GET", "HEAD").contains(method)) {
                send(exchange, 405, "text/plain; charset=utf-8", "method not allowed".getBytes(StandardCharsets.UTF_8));
                return;
            }
            String path = exchange.getRequestURI().getPath();
            if ("/api/workbench/state".equals(path)) {
                send(exchange, 503, "application/json; charset=utf-8",
                        "{\"error\":\"平台导航测试：API 未连接\"}".getBytes(StandardCharsets.UTF_8));
                return;
            }
            Path file = resolve(path);
            if (file == null) {
                send(exchange, 404, "text/plain; charset=utf-8", "not found".getBytes(StandardCharsets.UTF_8));
                return;
            }
            byte[] body = Files.readAllBytes(file);
            send(exchange, 200, contentType(file), body);
        } catch (RuntimeException error) {
            send(exchange, 500, "text/plain; charset=utf-8", String.valueOf(error.getMessage()).getBytes(StandardCharsets.UTF_8));
        } finally {
            exchange.close();
        }
    }

    private static Path resolve(String rawPath) {
        String path = ROUTES.getOrDefault(rawPath, rawPath);
        while (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
        if (!path.startsWith("/")) return null;
        Path candidate = OUT.resolve(path.substring(1)).normalize();
        if (!candidate.startsWith(OUT)) return null;
        if (Files.isDirectory(candidate)) candidate = candidate.resolve("index.html");
        if (!Files.isRegularFile(candidate) && !path.substring(1).contains(".")) {
            Path html = OUT.resolve(path.substring(1) + ".html").normalize();
            if (Files.isRegularFile(html)) candidate = html;
        }
        return Files.isRegularFile(candidate) ? candidate : null;
    }

    private static String contentType(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
        return TYPES.getOrDefault(extension, "application/octet-stream");
    }

    private static void send(HttpExchange exchange, int status, String type, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        if ("HEAD".equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Content-Length", String.valueOf(body.length));
            exchange.sendResponseHeaders(status, -1);
        } else {
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
        }
    }
}
