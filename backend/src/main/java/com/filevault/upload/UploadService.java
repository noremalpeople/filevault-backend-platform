package com.filevault.upload;

import com.filevault.common.ApiException;
import com.filevault.common.TransferTelemetry;
import com.filevault.storage.FileRules;
import com.filevault.storage.LocalStorage;
import com.filevault.user.UserMapper;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
class UploadService {
    private final UserMapper users;
    private final UploadMapper uploads;
    private final UploadWrites writes;
    private final LocalStorage storage;
    private final TransferTelemetry telemetry;
    private final long maximum;
    private final int sessionLimit;
    private final long reservationLimit;

    UploadService(UserMapper users, UploadMapper uploads, UploadWrites writes, LocalStorage storage, TransferTelemetry telemetry,
                  @Value("${filevault.max-upload-bytes}") long maximum,
                  @Value("${filevault.max-open-uploads}") int sessionLimit,
                  @Value("${filevault.user-upload-reservation-bytes}") long reservationLimit) {
        if (maximum < 1 || maximum > 512L * 1024 * 1024) {
            throw new IllegalArgumentException("FileVault max upload size must be between 1 byte and 512 MiB.");
        }
        if (sessionLimit < 1 || reservationLimit < 1) throw new IllegalArgumentException("Invalid upload limits.");
        this.users = users;
        this.uploads = uploads;
        this.writes = writes;
        this.storage = storage;
        this.telemetry = telemetry;
        this.maximum = maximum;
        this.sessionLimit = sessionLimit;
        this.reservationLimit = reservationLimit;
    }

    record Repair(int index, LocalStorage.ChunkState reason) { }

    record Detail(String id, String filename, long totalSize, int chunkSize, int chunkCount,
                  String status, LocalDateTime expiresAt, List<Integer> received, List<Repair> repair, String fileId) { }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Detail create(String username, String filename, long size, String sha256) {
        FileRules.validateName(filename);
        var layout = ChunkLayout.of(size, maximum);
        long owner = owner(username);
        users.lock(owner);
        if (uploads.openCount(owner) >= sessionLimit) {
            throw conflict("SESSION_LIMIT", "Finish or abort another upload session first.");
        }
        if (size > reservationLimit - uploads.reservedBytes(owner)) {
            throw conflict("UPLOAD_RESERVATION_EXCEEDED", "The account has no temporary upload capacity.");
        }
        var id = UUID.randomUUID().toString();
        var expiry = now().plusDays(1);
        uploads.insert(id, owner, filename, size, layout.size(), layout.count(), sha256, expiry);
        return describe(require(uploads.find(owner, id)), List.of(), List.of());
    }

    public Detail get(String username, UUID id) throws IOException {
        long owner = owner(username);
        var session = require(uploads.find(owner, id.toString()));
        if (!"OPEN".equals(effectiveStatus(session))) return describe(session, List.of(), List.of());
        var received = new ArrayList<Integer>();
        var repair = new ArrayList<Repair>();
        for (var chunk : uploads.chunks(session.id())) {
            var state = storage.inspectChunk(chunk.part());
            if (state == LocalStorage.ChunkState.OK) received.add(chunk.chunkIndex());
            else repair.add(new Repair(chunk.chunkIndex(), state));
        }
        var current = require(uploads.find(owner, session.id()));
        if (!"OPEN".equals(effectiveStatus(current))) return describe(current, List.of(), List.of());
        return describe(current, received, repair);
    }

    public void put(String username, UUID id, int index, InputStream content) throws IOException {
        long owner = owner(username);
        var session = open(uploads.find(owner, id.toString()));
        var layout = new ChunkLayout(session.totalSize(), session.chunkSize(), session.chunkCount());
        int bytes = layout.expectedBytes(index);
        try (content; var staged = storage.stage(content, bytes)) {
            if (staged.bytes() != bytes) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "CHUNK_SIZE", "The chunk has an unexpected size.");
            }
            writes.accept(owner, session.id(), index, staged);
        }
    }

    public String complete(String username, UUID id) throws IOException {
        long owner = owner(username);
        var session = require(uploads.find(owner, id.toString()));
        if ("COMPLETED".equals(session.status())) {
            return session.fileId();
        }
        open(session);
        var chunks = uploads.chunks(session.id());
        if (chunks.size() != session.chunkCount()) {
            var current = require(uploads.find(owner, session.id()));
            if ("COMPLETED".equals(current.status())) return current.fileId();
            open(current);
            throw conflict("MISSING_CHUNKS", "Upload all chunks before completing.");
        }
        for (int index = 0; index < chunks.size(); index++) {
            if (chunks.get(index).chunkIndex() != index) {
                return fail(owner, session.id(), new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
                        "INVALID_CHUNK_LAYOUT", "The stored upload layout is invalid. Start over."));
            }
        }
        String mediaType = FileRules.validateName(session.filename());
        try (var merged = merge(chunks, session.totalSize())) {
            if (merged.bytes() != session.totalSize() || !merged.sha256().equals(session.expectedSha256())) {
                return fail(owner, session.id(), new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
                        "FILE_HASH_MISMATCH", "Merged bytes do not match the declaration. Start over."));
            }
            try {
                FileRules.validateContent(merged.path(), mediaType);
            } catch (ApiException invalidContent) {
                return fail(owner, session.id(), invalidContent);
            }
            return writes.finish(owner, session.id(), mediaType, merged);
        } catch (IOException | ApiException failure) {
            var current = require(uploads.find(owner, session.id()));
            if ("COMPLETED".equals(current.status())) return current.fileId();
            if (failure instanceof ApiException error && !"CHUNK_REPAIR_REQUIRED".equals(error.code())) throw error;
            open(current);
            throw failure;
        }
    }

    private LocalStorage.Staged merge(List<UploadMapper.Chunk> chunks, long size) throws IOException {
        long started = System.nanoTime();
        boolean success = false;
        try {
            var staged = storage.mergeChunks(chunks.stream().map(UploadMapper.Chunk::part).toList(), size);
            success = true;
            return staged;
        } finally {
            telemetry.recordTransfer("merge", success, started);
        }
    }

    private String fail(long owner, String id, ApiException failure) {
        String completed = writes.fail(owner, id);
        if (completed != null) return completed;
        throw failure;
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

    private Detail describe(UploadMapper.Session session, List<Integer> received, List<Repair> repair) {
        return new Detail(session.id(), session.filename(), session.totalSize(), session.chunkSize(),
                session.chunkCount(), effectiveStatus(session), session.expiresAt(), received, repair, session.fileId());
    }

    private long owner(String username) {
        var user = users.find(username);
        if (user == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Sign in first.");
        }
        return user.id();
    }

    static UploadMapper.Session require(UploadMapper.Session session) {
        if (session == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "UPLOAD_NOT_FOUND", "Upload session not found.");
        }
        return session;
    }

    static UploadMapper.Session open(UploadMapper.Session session) {
        session = require(session);
        if ("FAILED".equals(session.status())) {
            throw conflict("UPLOAD_FAILED", "This upload failed validation. Start over.");
        }
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

    static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }
}
