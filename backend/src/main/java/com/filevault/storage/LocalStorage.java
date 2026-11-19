package com.filevault.storage;

import com.filevault.common.ApiException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class LocalStorage {
    private static final Logger LOG = LoggerFactory.getLogger(LocalStorage.class);
    private final Path root;
    private final Path objects;
    private final Path staging;
    private final Path chunks;
    private final long minFree;
    private final long stagingLimit;
    private final Set<Path> inUseStaging = ConcurrentHashMap.newKeySet();
    private long stagingReserved;

    public LocalStorage(String directory) throws IOException {
        this(directory, null, 0, Long.MAX_VALUE, false);
    }

    @Autowired
    public LocalStorage(@Value("${filevault.storage-root}") String directory, WebRoot web,
                        @Value("${filevault.storage-min-free-bytes}") long minFree,
                        @Value("${filevault.storage-staging-bytes}") long stagingLimit,
                        @Value("${filevault.reconcile-only:false}") boolean readOnly) throws IOException {
        root = Path.of(directory).toAbsolutePath().normalize();
        if (web != null) web.verifyPrivate(root);
        if (!readOnly) Files.createDirectories(root);
        verifyDirectory(root);
        if (web != null) web.verifyPrivate(root);
        if (minFree < 0 || stagingLimit < 1) throw new IllegalArgumentException("Invalid storage resource limits.");
        this.minFree = minFree;
        this.stagingLimit = stagingLimit;
        objects = storageDirectory(root.resolve("objects"), readOnly);
        staging = storageDirectory(root.resolve("staging"), readOnly);
        chunks = storageDirectory(root.resolve("chunks"), readOnly);
    }

    private Path storageDirectory(Path path, boolean readOnly) throws IOException {
        if (!readOnly) Files.createDirectories(path);
        verifyDirectory(path);
        return path;
    }

    private void verifyDirectory(Path path) throws IOException {
        if (!path.startsWith(root) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                || !path.toRealPath().equals(path)) {
            throw new IOException("Unexpected storage directory.");
        }
    }

    public final class Staged implements AutoCloseable {
        private final Path path;
        private final long bytes;
        private final String sha256;
        private final long reserved;
        private boolean closed;

        private Staged(Path path, long bytes, String sha256, long reserved) {
            this.path = path;
            this.bytes = bytes;
            this.sha256 = sha256;
            this.reserved = reserved;
        }

        public Path path() { return path; }
        public long bytes() { return bytes; }
        public String sha256() { return sha256; }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                try {
                    remove(path);
                } finally {
                    inUseStaging.remove(path);
                    release(reserved);
                }
            }
        }
    }

    public Staged stage(InputStream input, long maxBytes) throws IOException {
        return stage(maxBytes, output -> copy(input, output, maxBytes));
    }

    @FunctionalInterface
    private interface StageWriter {
        long write(OutputStream output) throws IOException;
    }

    private Staged stage(long maxBytes, StageWriter writer) throws IOException {
        verifyDirectory(staging);
        reserve(maxBytes);
        Path temporary = null;
        MessageDigest hash = sha256();
        boolean success = false;
        try {
            temporary = Files.createTempFile(staging, "incoming-", ".part");
            inUseStaging.add(temporary);
            long size;
            try (var output = new DigestOutputStream(Files.newOutputStream(temporary), hash)) {
                size = writer.write(output);
            }
            if (size == 0) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "EMPTY_FILE", "Empty uploads are unsupported.");
            }
            success = true;
            return new Staged(temporary, size, HexFormat.of().formatHex(hash.digest()), maxBytes);
        } finally {
            if (!success) {
                if (temporary != null) {
                    remove(temporary);
                    inUseStaging.remove(temporary);
                }
                release(maxBytes);
            }
        }
    }

    private synchronized void reserve(long bytes) throws IOException {
        long free = Files.getFileStore(staging).getUsableSpace();
        if (bytes > stagingLimit - stagingReserved || free < minFree || bytes > free - minFree
                || stagingReserved > free - minFree - bytes) {
            throw new ApiException(HttpStatus.INSUFFICIENT_STORAGE, "STORAGE_BUSY",
                    "Temporary storage is full or the disk is below its free-space threshold.");
        }
        stagingReserved += bytes;
    }

    private synchronized void release(long bytes) {
        stagingReserved -= bytes;
    }

    private static long copy(InputStream input, OutputStream output, long limit) throws IOException {
        long size = 0;
        byte[] buffer = new byte[64 * 1024];
        for (int count; (count = input.read(buffer)) != -1;) {
            size += count;
            if (size > limit) {
                throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "FILE_TOO_LARGE", "The upload exceeds the limit.");
            }
            output.write(buffer, 0, count);
        }
        return size;
    }

    public void publish(Staged staged, String key) throws IOException {
        move(staged, keyedPath(objects, key));
    }

    public void publishChunk(Staged staged, String key) throws IOException {
        move(staged, keyedPath(chunks, key));
    }

    public void repairChunk(Staged staged, String key) throws IOException {
        Path path = keyedPath(chunks, key);
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) verifyFile(path);
        move(staged, path, StandardCopyOption.REPLACE_EXISTING);
    }

    public boolean hasObject(String key) throws IOException {
        return hasFile(keyedPath(objects, key));
    }

    private boolean hasFile(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        verifyFile(path);
        return true;
    }

    public enum ChunkState { OK, MISSING, CORRUPT }

    public record Part(String key, long bytes, String sha256) { }

    public ChunkState inspectChunk(Part part) throws IOException {
        return readPart(part, OutputStream.nullOutputStream());
    }

    private ChunkState readPart(Part part, OutputStream output) throws IOException {
        Path path = keyedPath(chunks, part.key());
        MessageDigest digest = sha256();
        try {
            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attrs.isRegularFile() || !path.toRealPath().equals(path)) {
                throw new ApiException(HttpStatus.CONFLICT, "CHUNK_STORAGE_UNSAFE",
                        "The stored chunk cannot be repaired in this session. Start over.");
            }
            if (attrs.size() != part.bytes()) return ChunkState.CORRUPT;
            try (var input = new DigestInputStream(openFile(path), digest)) {
                long bytes = copy(input, output, part.bytes());
                return bytes == part.bytes() && HexFormat.of().formatHex(digest.digest()).equals(part.sha256())
                        ? ChunkState.OK : ChunkState.CORRUPT;
            } catch (ApiException grewWhileReading) {
                return ChunkState.CORRUPT;
            }
        } catch (NoSuchFileException missing) {
            return ChunkState.MISSING;
        }
    }

    public Staged mergeChunks(List<Part> parts, long size) throws IOException {
        return stage(size, output -> {
            long bytes = 0;
            for (Part part : parts) {
                if (part.bytes() > size - bytes || readPart(part, output) != ChunkState.OK) {
                    throw new ApiException(HttpStatus.CONFLICT, "CHUNK_REPAIR_REQUIRED",
                            "A stored chunk is missing or corrupt. Check the upload and resend it.");
                }
                bytes += part.bytes();
            }
            return bytes;
        });
    }

    private void move(Staged staged, Path destination, StandardCopyOption... fallback) throws IOException {
        verifyDirectory(staging);
        if (!staged.path().getParent().equals(staging) || !staged.path().toRealPath().equals(staged.path())) {
            throw new IOException("Unexpected staging file.");
        }
        Files.setLastModifiedTime(staged.path(), FileTime.from(Instant.now()));
        if (fallback.length == 0) {
            Files.move(staged.path(), destination);
            return;
        }
        try {
            Files.move(staged.path(), destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException failure) {
            Files.move(staged.path(), destination, fallback);
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
        if (!Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isRegularFile()
                || !path.toRealPath().equals(path)) {
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
            LOG.warn("FileVault could not remove a stored chunk: {}", failure.getClass().getSimpleName());
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

    Path auditDirectory(String kind) throws IOException {
        Path path = switch (kind) {
            case "objects" -> objects;
            case "chunks" -> chunks;
            case "staging" -> staging;
            default -> throw new IllegalArgumentException("Unexpected storage category.");
        };
        verifyDirectory(path);
        return path;
    }

    boolean isStagedInUse(Path path) {
        return inUseStaging.contains(path);
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
