package com.filevault.upload;

import com.filevault.common.ApiException;
import org.springframework.http.HttpStatus;

record ChunkLayout(long bytes, int size, int count) {
    static final int CHUNK_BYTES = 4 * 1024 * 1024;

    static ChunkLayout of(long bytes, long limit) {
        if (bytes <= 0 || bytes > limit) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TOTAL_SIZE", "Check the declared file size.");
        }
        return new ChunkLayout(bytes, CHUNK_BYTES, Math.toIntExact((bytes - 1) / CHUNK_BYTES + 1));
    }

    int expectedBytes(int index) {
        if (index < 0 || index >= count) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CHUNK_INDEX", "The chunk index is outside the session.");
        }
        return (int) Math.min(size, bytes - (long) size * index);
    }
}
