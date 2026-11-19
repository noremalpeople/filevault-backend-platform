package com.filevault.storage;

import com.filevault.common.ApiException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class StorageTest {
    @TempDir
    Path scratch;

    @ParameterizedTest
    @ValueSource(strings = {"../sample.txt", "C:\\sample.txt", "CON.txt", "item.svg", "file.txt "})
    void rejectsUnsupportedNames(String name) {
        assertThrows(ApiException.class, () -> FileRules.validateName(name));
    }

    @Test
    void streamsHashesAndPublishesContent() throws Exception {
        var storage = new LocalStorage(scratch.toString());
        byte[] content = "abc".getBytes(StandardCharsets.UTF_8);
        var key = UUID.randomUUID().toString();
        try (var staged = storage.stage(new ByteArrayInputStream(content), 3)) {
            assertEquals(3, staged.bytes());
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", staged.sha256());
            assertEquals("text/plain", FileRules.validateName("report.txt"));
            FileRules.validateContent(staged.path(), "text/plain");
            storage.publish(staged, key);
        }
        try (var stream = storage.open(key)) {
            assertArrayEquals(content, stream.readAllBytes());
        }
    }

    @Test
    void removesStagingFileAfterOversizedInput() throws Exception {
        var storage = new LocalStorage(scratch.toString());
        assertThrows(ApiException.class, () -> storage.stage(new ByteArrayInputStream(new byte[4]), 3));
        try (Stream<Path> entries = Files.list(scratch.resolve("staging"))) {
            assertEquals(0, entries.count());
        }
    }

    @Test
    void verifiesFileBytesInsteadOfAssumingExtensionMatches() throws Exception {
        Path file = Files.writeString(scratch.resolve("test.pdf"), "plain text");
        assertThrows(ApiException.class, () -> FileRules.validateContent(file, "application/pdf"));
        Path text = Files.write(scratch.resolve("invalid.txt"), new byte[] {(byte) 0xff});
        assertThrows(ApiException.class, () -> FileRules.validateContent(text, "text/plain"));
    }
}
