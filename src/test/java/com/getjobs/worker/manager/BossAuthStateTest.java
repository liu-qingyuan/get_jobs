package com.getjobs.worker.manager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.SameSiteAttribute;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The real Boss login token is a session cookie. Playwright's persistent context does not reliably
 * carry session cookies across processes, so the app saves/restores a private storageState. This
 * test proves a session cookie survives that round trip without touching any real account.
 */
@EnabledIfEnvironmentVariable(named = "BOSS_AUTH_STATE_TEST", matches = "true")
class BossAuthStateTest {
    @Test
    void sessionCookieSurvivesStorageStateAndIsRestored() throws Exception {
        Path directory = Files.createTempDirectory("boss-auth-state-");
        Path profile = directory.resolve("profile");
        Path state = directory.resolve("state.json");
        var options = new BrowserType.LaunchPersistentContextOptions().setHeadless(true).setChannel("chrome");
        try (Playwright playwright = Playwright.create()) {
            BrowserContext context = playwright.chromium().launchPersistentContext(profile, options);
            context.addCookies(List.of(new Cookie("wt2", "synthetic-session")
                    .setDomain("example.test").setPath("/").setExpires(-1).setHttpOnly(true)
                    .setSecure(true).setSameSite(SameSiteAttribute.LAX)));
            context.storageState(new BrowserContext.StorageStateOptions().setPath(state));
            context.close();
        }
        assertTrue(Files.isRegularFile(state), "storageState was not written");

        try (Playwright playwright = Playwright.create()) {
            BrowserContext context = playwright.chromium().launchPersistentContext(profile, options);
            var root = new ObjectMapper().readTree(Files.readString(state));
            List<Cookie> cookies = new ArrayList<>();
            for (var value : root.path("cookies")) {
                Cookie cookie = new Cookie(value.path("name").asText(), value.path("value").asText())
                        .setDomain(value.path("domain").asText()).setPath(value.path("path").asText())
                        .setHttpOnly(value.path("httpOnly").asBoolean(false)).setSecure(value.path("secure").asBoolean(false));
                if (value.path("expires").isNumber()) cookie.setExpires(value.path("expires").asDouble());
                if (value.hasNonNull("sameSite")) {
                    cookie.setSameSite(switch (value.path("sameSite").asText().toLowerCase()) {
                        case "strict" -> SameSiteAttribute.STRICT;
                        case "lax" -> SameSiteAttribute.LAX;
                        case "none" -> SameSiteAttribute.NONE;
                        default -> null;
                    });
                }
                cookies.add(cookie);
            }
            context.addCookies(cookies);
            var restored = context.cookies().stream().filter(c -> c.name.equals("wt2")).findFirst().orElseThrow();
            assertEquals("synthetic-session", restored.value);
            assertEquals(-1.0, restored.expires, 0.01, "session cookie must be restored as a session cookie");
            context.close();
        }
    }
}
