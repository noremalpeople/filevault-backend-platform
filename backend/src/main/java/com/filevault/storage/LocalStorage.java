package com.filevault.storage;

import com.filevault.common.ApiException;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class LocalStorage {
    private static final Logger LOG = LoggerFactory.getLogger(LocalStorage.class);
    private final Path root;
    private final Path objects;
    private final Path staging;
    private final Path chunks;

    public LocalStorage(@Value("${filevault.storage-root}") String directory) throws IOException {
        root = Path.of(directory).toAbsolutePath().normalize();
        Path web = Path.of("src/main/resources/static").toAbsolutePath().normalize();
        if (root.startsWith(web) || web.startsWith(root)) {
            throw new IOException("Choose a private application storage root.");
        }
        Files.createDirectories(root);
        verifyDirectory(root);
        objects = ensureDirectory(root.resolve("objects"));
        staging = ensureDirectory(root.resolve("staging"));
        chunks = ensureDirectory(root.resolve("chunks"));
    }

    private Path ensureDirectory(Path path) throws IOException {
        Files.createDirectories(path);
        verifyDirectory(path);
        return path;
    }

    private void verifyDirectory(Path path) throws IOException {
        if (!path.startsWith(root) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                || !path.toRealPath().equals(path)) {
            throw new IOException("Unexpected storage directory.");
        }
    }

    public record Staged(Path path, long bytes, String sha256) implements AutoCloseable {
        @Override
        public void close() {
            remove(path);
        }
    }

    public Staged stage(InputStream input, long maxBytes) throws IOException {
        verifyDirectory(staging);
        Path temporary = Files.createTempFile(staging, "incoming-", ".part");
        MessageDigest hash = sha256();
        boolean success = false;
        try {
            long size = 0;
            try (var output = new DigestOutputStream(Files.newOutputStream(temporary), hash)) {
                byte[] buffer = new byte[64 * 1024];
                for (int count; (count = input.read(buffer)) != -1;) {
                    size += count;
                    if (size > maxBytes) {
                        throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "FILE_TOO_LARGE", "The upload exceeds the limit.");
                    }
                    output.write(buffer, 0, count);
                }
            }
            if (size == 0) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "EMPTY_FILE", "Empty uploads are unsupported.");
            }
            success = true;
            return new Staged(temporary, size, HexFormat.of().formatHex(hash.digest()));
        } finally {
            if (!success) {
                remove(temporary);
            }
        }
    }

    public void publish(Staged staged, String key) throws IOException {
        move(staged, keyedPath(objects, key));
    }

    public void publishChunk(Staged staged, String key) throws IOException {
        move(staged, keyedPath(chunks, key));
    }

    public boolean hasChunk(String key) throws IOException {
        Path path = keyedPath(chunks, key);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        verifyFile(path);
        return true;
    }

    public Staged mergeChunks(List<String> keys, long size) throws IOException {
        List<InputStream> streams = new ArrayList<>();
        try {
            for (String key : keys) {
                streams.add(openFile(keyedPath(chunks, key)));
            }
            try (var all = new SequenceInputStream(Collections.enumeration(streams))) {
                return stage(all, size);
            }
        } finally {
            for (InputStream stream : streams) {
                try {
                    stream.close();
                } catch (IOException failure) {
                    LOG.warn("FileVault could not close a chunk stream.");
                }
            }
        }
    }

    private void move(Staged staged, Path destination) throws IOException {
        verifyDirectory(staging);
        if (!staged.path().getParent().equals(staging) || !staged.path().toRealPath().equals(staged.path())) {
            throw new IOException("Unexpected staging file.");
        }
        try {
            Files.move(staged.path(), destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException failure) {
            Files.move(staged.path(), destination);
        }
    }

    public InputStream open(String key) throws IOException {
        return openFile(keyedPath(objects, key));
    }

    private InputStream openFile(Path path) throws IOException {
        verifyFile(path);
        return Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS);
    }

    private void verifyFile(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || !path.toRealPath().equals(path)) {
            throw new IOException("Stored file is unavailable.");
        }
    }

    public void removeObject(String key) {
        try {
            remove(keyedPath(objects, key));
        } catch (IOException failure) {
            LOG.warn("Storage rollback could not access the private object directory.");
        }
    }

    public boolean removeChunk(String key) {
        try {
            Path path = keyedPath(chunks, key);
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                verifyFile(path);
            }
            Files.deleteIfExists(path);
            return true;
        } catch (IOException failure) {
            LOG.warn("FileVault could not remove a stored chunk.");
            return false;
        }
    }

    private Path keyedPath(Path directory, String key) throws IOException {
        verifyDirectory(directory);
        if (!UUID.fromString(key).toString().equals(key)) {
            throw new IOException("Unexpected storage key.");
        }
        return directory.resolve(key);
    }

    private static void remove(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException failure) {
            LOG.warn("FileVault could not remove a temporary or rolled-back object.");
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Required SHA-256 provider unavailable.", failure);
        }
    }
}
