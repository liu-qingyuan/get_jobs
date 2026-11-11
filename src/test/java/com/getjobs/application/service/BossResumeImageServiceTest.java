package com.getjobs.application.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class BossResumeImageServiceTest {
    @TempDir Path directory;

    @Test void storesOnlyParsedImagesInRuntimeDirectoryAndReplacesThePreviousImage() throws Exception {
        var service = new BossResumeImageService(directory);
        var image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        var bytes = new ByteArrayOutputStream(); ImageIO.write(image, "png", bytes);
        var saved = service.save(new MockMultipartFile("file", "misleading-name.jpg", "image/jpeg", bytes.toByteArray()));
        assertEquals("resume.png", saved.fileName());
        assertEquals(directory.resolve("resume.png"), saved.path());
        assertTrue((Boolean) service.getMetadata().get("exists"));
        bytes.reset(); ImageIO.write(image, "jpg", bytes);
        var replacement = service.save(new MockMultipartFile("file", "fixture.jpg", "image/jpeg", bytes.toByteArray()));
        assertEquals("resume.jpg", replacement.fileName());
        assertFalse(Files.exists(directory.resolve("resume.png")));
        assertTrue(Files.exists(directory.resolve("resume.jpg")));
    }

    @Test void invalidOrOversizedInputDoesNotBecomeAResume() throws Exception {
        var service = new BossResumeImageService(directory);
        assertThrows(IllegalArgumentException.class, () -> service.save(new MockMultipartFile("file", "fake.png", "image/png", "not an image".getBytes())));
        assertFalse((Boolean) service.getMetadata().get("exists"));
        assertThrows(IllegalArgumentException.class, () -> service.save(new MockMultipartFile("file", "big.png", "image/png", new byte[(int) BossResumeImageService.MAX_FILE_SIZE + 1])));
        try (var files = Files.list(directory)) { assertEquals(0, files.count()); }
    }
}
