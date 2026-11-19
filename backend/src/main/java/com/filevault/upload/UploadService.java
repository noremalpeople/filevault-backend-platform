package com.filevault.upload;

import com.filevault.common.ApiException;
import com.filevault.file.FileService;
import com.filevault.storage.FileRules;
import com.filevault.storage.LocalStorage;
import com.filevault.user.UserMapper;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
class UploadService {
    private static final int SESSION_LIMIT = 4;
    private final UserMapper users;
    private final UploadMapper uploads;
    private final FileService files;
    private final LocalStorage storage;
    private final long maximum;

    UploadService(UserMapper users, UploadMapper uploads, FileService files, LocalStorage storage,
                  @Value("${filevault.max-upload-bytes}") long maximum) {
        if (maximum < 1 || maximum > 512L * 1024 * 1024) {
            throw new IllegalArgumentException("FileVault max upload bytes must be between 1 and 512 MiB.");
        }
        this.users = users;
        this.uploads = uploads;
        this.files = files;
        this.storage = storage;
        this.maximum = maximum;
    }

    record Detail(String id, String filename, long totalSize, int chunkSize, int chunkCount,
                  String status, LocalDateTime expiresAt, List<Integer> received, String fileId) { }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Detail create(String username, String filename, long size, String sha256) {
        FileRules.validateName(filename);
        var layout = ChunkLayout.of(size, maximum);
        long owner = owner(username);
        users.lock(owner);
        if (uploads.openCount(owner) >= SESSION_LIMIT) {
            throw conflict("SESSION_LIMIT", "Finish or abort another upload session first.");
        }
        var id = UUID.randomUUID().toString();
        var expiry = now().plusDays(1);
        uploads.insert(id, owner, filename, size, layout.size(), layout.count(), sha256, expiry);
        return describe(require(uploads.find(owner, id)));
    }

    @Transactional(readOnly = true)
    public Detail get(String username, UUID id) {
        return describe(require(uploads.find(owner(username), id.toString())));
    }

    @Transactional(rollbackFor = IOException.class)
    public void put(String username, UUID id, int index, InputStream content) throws IOException {
        long owner = owner(username);
        var session = open(uploads.lock(owner, id.toString()));
        var layout = new ChunkLayout(session.totalSize(), session.chunkSize(), session.chunkCount());
        int bytes = layout.expectedBytes(index);
        try (content; var staged = storage.stage(content, bytes)) {
            if (staged.bytes() != bytes) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "CHUNK_SIZE", "The chunk has an unexpected size.");
            }
            var previous = uploads.chunk(session.id(), index);
            if (previous != null) {
                if (!previous.sha256().equals(staged.sha256())) {
                    throw conflict("CHUNK_CONFLICT", "This index already has different bytes.");
                }
                if (!storage.hasChunk(previous.storedKey())) {
                    storage.publishChunk(staged, previous.storedKey());
                }
                return;
            }
            String key = UUID.randomUUID().toString();
            storage.publishChunk(staged, key);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status == STATUS_ROLLED_BACK) {
                        storage.removeChunk(key);
                    }
                }
            });
            uploads.insertChunk(session.id(), index, bytes, staged.sha256(), key);
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = IOException.class)
    public String complete(String username, UUID id) throws IOException {
        long owner = owner(username);
        var session = require(uploads.lock(owner, id.toString()));
        if ("COMPLETED".equals(session.status())) {
            return session.fileId();
        }
        open(session);
        var chunks = uploads.chunks(session.id());
        if (chunks.size() != session.chunkCount()) {
            throw conflict("MISSING_CHUNKS", "Upload all chunks before completing.");
        }
        String mediaType = FileRules.validateName(session.filename());
        try (var merged = storage.mergeChunks(chunks.stream().map(UploadMapper.Chunk::storedKey).toList(), session.totalSize())) {
            if (merged.bytes() != session.totalSize() || !merged.sha256().equals(session.expectedSha256())) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "FILE_HASH_MISMATCH", "Merged bytes do not match the declaration.");
            }
            FileRules.validateContent(merged.path(), mediaType);
            var file = files.persist(owner, session.filename(), mediaType, merged);
            uploads.close(owner, session.id(), "COMPLETED", file.id());
            return file.id();
        }
    }

    @Transactional
    public void abort(String username, UUID id) {
        long owner = owner(username);
        var session = require(uploads.lock(owner, id.toString()));
        if ("ABORTED".equals(session.status())) {
            return;
        }
        open(session);
        uploads.close(owner, session.id(), "ABORTED", null);
    }

    private Detail describe(UploadMapper.Session session) {
        return new Detail(session.id(), session.filename(), session.totalSize(), session.chunkSize(),
                session.chunkCount(), effectiveStatus(session), session.expiresAt(),
                uploads.chunks(session.id()).stream().map(UploadMapper.Chunk::chunkIndex).toList(),
                session.fileId());
    }

    private long owner(String username) {
        var user = users.find(username);
        if (user == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Sign in first.");
        }
        return user.id();
    }

    private static UploadMapper.Session require(UploadMapper.Session session) {
        if (session == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "UPLOAD_NOT_FOUND", "Upload session not found.");
        }
        return session;
    }

    private static UploadMapper.Session open(UploadMapper.Session session) {
        session = require(session);
        if (!"OPEN".equals(effectiveStatus(session))) {
            throw conflict("UPLOAD_CLOSED", "This upload session is closed.");
        }
        return session;
    }

    private static String effectiveStatus(UploadMapper.Session session) {
        return "OPEN".equals(session.status()) && !session.expiresAt().isAfter(now())
                ? "EXPIRED" : session.status();
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }
}
