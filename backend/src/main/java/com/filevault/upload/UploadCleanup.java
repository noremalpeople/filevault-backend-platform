package com.filevault.upload;

import com.filevault.storage.LocalStorage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
@ConditionalOnProperty(name = "filevault.reconcile-only", havingValue = "false", matchIfMissing = true)
class UploadCleanup {
    private final UploadMapper uploads;
    private final LocalStorage storage;
    private final int retrySeconds;

    UploadCleanup(UploadMapper uploads, LocalStorage storage,
                  @Value("${filevault.cleanup-retry-seconds}") int retrySeconds) {
        if (retrySeconds < 1) throw new IllegalArgumentException("Cleanup retry interval must be positive.");
        this.uploads = uploads;
        this.storage = storage;
        this.retrySeconds = retrySeconds;
    }

    @Scheduled(initialDelayString = "${filevault.cleanup-delay-ms}", fixedDelayString = "${filevault.cleanup-delay-ms}")
    void cleanup() {
        uploads.expire();
        for (var chunk : uploads.closedChunks()) {
            cleanChunk(chunk);
        }
    }

    void cleanChunk(UploadMapper.Chunk chunk) {
        if (storage.removeChunk(chunk.storedKey())) {
            uploads.forget(chunk.sessionId(), chunk.chunkIndex());
        } else {
            uploads.deferCleanup(chunk.sessionId(), chunk.chunkIndex(), retrySeconds);
        }
    }
}
