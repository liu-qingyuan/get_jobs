package com.getjobs.application.workbench;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static com.getjobs.application.workbench.WorkbenchConnections.Status.*;
import static org.junit.jupiter.api.Assertions.*;

class WorkbenchConnectionsTest {
    @TempDir Path root;

    @Test void absentAndOrdinaryProfilesRemainDistinctFromGuardRefusal() throws Exception {
        var profile = root.resolve("profile");
        assertEquals(PROFILE_MISSING, WorkbenchConnections.jobsDb(profile));
        Files.createDirectories(profile.resolve("Default"));
        assertEquals(CHECK_AT_READ, WorkbenchConnections.jobsDb(profile));
        Files.createDirectory(profile.resolve("Default/Service Worker"));
        assertEquals(READ_GUARD_BLOCKED, WorkbenchConnections.jobsDb(profile));
    }

    @Test void danglingWorkerLinkCannotBeMistakenForAbsentState() throws Exception {
        var profile = Files.createDirectories(root.resolve("profile/Default")).getParent();
        var worker = profile.resolve("Default/Service Worker");
        Files.createSymbolicLink(worker, root.resolve("missing-worker"));
        assertEquals(READ_GUARD_BLOCKED, WorkbenchConnections.jobsDb(profile));
        assertTrue(Files.isSymbolicLink(worker));
        assertFalse(Files.exists(root.resolve("missing-worker")));
    }

    @Test void linkedProfilesAreUnavailableWithoutTraversingTheirContents() throws Exception {
        var outside = Files.createDirectory(root.resolve("outside"));
        var sentinel = Files.writeString(outside.resolve("sentinel"), "Synthetic fixture");
        var profile = Files.createDirectory(root.resolve("profile"));
        Files.createSymbolicLink(profile.resolve("Default"), outside);
        assertEquals(PROFILE_UNAVAILABLE, WorkbenchConnections.jobsDb(profile));
        Files.delete(profile.resolve("Default"));
        Files.delete(profile);
        Files.createSymbolicLink(profile, outside);
        assertEquals(PROFILE_UNAVAILABLE, WorkbenchConnections.jobsDb(profile));
        assertEquals("Synthetic fixture", Files.readString(sentinel));
    }
}
