package com.filevault.upload;

import com.filevault.storage.LocalStorage;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class UploadCleanup {
    private final UploadMapper uploads;
    private final LocalStorage storage;

    UploadCleanup(UploadMapper uploads, LocalStorage storage) {
        this.uploads = uploads;
        this.storage = storage;
    }

    @Scheduled(initialDelayString = "${filevault.cleanup-delay-ms}", fixedDelayString = "${filevault.cleanup-delay-ms}")
    void cleanup() {
        uploads.expire();
        for (var chunk : uploads.closedChunks()) {
            if (storage.removeChunk(chunk.storedKey())) {
                uploads.forget(chunk.sessionId(), chunk.chunkIndex());
            }
        }
    }
}
