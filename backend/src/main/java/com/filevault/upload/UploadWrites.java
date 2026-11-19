package com.filevault.upload;

import com.filevault.file.FileService;
import com.filevault.storage.LocalStorage;
import java.io.IOException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
class UploadWrites {
    private final UploadMapper uploads;
    private final LocalStorage storage;
    private final FileService files;

    UploadWrites(UploadMapper uploads, LocalStorage storage, FileService files) {
        this.uploads = uploads;
        this.storage = storage;
        this.files = files;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = IOException.class)
    public void accept(long owner, String id, int index, LocalStorage.Staged staged) throws IOException {
        UploadService.open(uploads.lock(owner, id));
        var previous = uploads.chunk(id, index);
        if (previous != null) {
            if (!previous.sha256().equals(staged.sha256())) {
                throw UploadService.conflict("CHUNK_CONFLICT", "This index already has different bytes.");
            }
            if (storage.inspectChunk(previous.part()) != LocalStorage.ChunkState.OK) {
                storage.repairChunk(staged, previous.storedKey());
            }
            return;
        }
        String key = UUID.randomUUID().toString();
        storage.publishChunk(staged, key);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) storage.removeChunk(key);
            }
        });
        uploads.insertChunk(id, index, Math.toIntExact(staged.bytes()), staged.sha256(), key);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = IOException.class)
    public String finish(long owner, String id, String mediaType, LocalStorage.Staged merged) throws IOException {
        var session = UploadService.require(uploads.lock(owner, id));
        if ("COMPLETED".equals(session.status())) return session.fileId();
        UploadService.open(session);
        // Existing chunk rows are immutable while OPEN. Repair only restores their original digest.
        var file = files.persist(owner, session.filename(), mediaType, merged);
        uploads.close(owner, id, "COMPLETED", file.id());
        return file.id();
    }

    @Transactional
    public String fail(long owner, String id) {
        var session = UploadService.require(uploads.lock(owner, id));
        if ("COMPLETED".equals(session.status())) return session.fileId();
        UploadService.open(session);
        uploads.close(owner, id, "FAILED", null);
        return null;
    }
}
