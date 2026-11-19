package com.filevault.storage;

import com.filevault.common.ApiException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;

import static org.junit.jupiter.api.Assertions.*;

class WebRootTest {
    @TempDir
    Path scratch;

    @Test
    void rejectsSameAncestorAndNotYetCreatedChild() throws Exception {
        Path web = Files.createDirectories(scratch.resolve("web"));
        var root = new WebRoot(web.toUri().toString(), new DefaultResourceLoader());
        for (Path storage : new Path[]{ web, scratch, web.resolve("new/private") }) {
            var error = assertThrows(IOException.class, () -> root.verifyPrivate(storage));
            assertTrue(error.getMessage().contains("overlaps"));
        }
        assertDoesNotThrow(() -> root.verifyPrivate(scratch.resolve("private")));
    }

    @Test
    void resolvesJunctionBeforeCheckingAnUncreatedStorageChild() throws Exception {
        Path web = Files.createDirectories(scratch.resolve("web"));
        Path alias = scratch.resolve("junction");
        TestJunctions.create(alias, web);
        try {
            var webRoot = new WebRoot(web.toUri().toString(), new DefaultResourceLoader());
            assertThrows(IOException.class, () -> webRoot.verifyPrivate(alias.resolve("private")));
            var aliasRoot = new WebRoot(alias.toUri().toString(), new DefaultResourceLoader());
            assertThrows(IOException.class, () -> aliasRoot.verifyPrivate(web));
        } finally {
            Files.delete(alias);
        }
    }

    @Test
    void temporaryBudgetIsReleasedAndLowDiskGuardRejectsBeforeWriting() throws Exception {
        var storage = new LocalStorage(scratch.resolve("storage").toString(), null, 0, 3, false);
        try (var held = storage.stage(new ByteArrayInputStream(new byte[]{1}), 3)) {
            var error = assertThrows(ApiException.class,
                    () -> storage.stage(new ByteArrayInputStream(new byte[]{1}), 1));
            assertEquals("STORAGE_BUSY", error.code());
        }
        try (var staged = storage.stage(new ByteArrayInputStream(new byte[]{1}), 3)) {
            assertEquals(1, staged.bytes());
        }
        var lowDisk = new LocalStorage(scratch.resolve("low").toString(), null, Long.MAX_VALUE, 3, false);
        assertEquals("STORAGE_BUSY", assertThrows(ApiException.class,
                () -> lowDisk.stage(new ByteArrayInputStream(new byte[]{1}), 1)).code());
    }
}
