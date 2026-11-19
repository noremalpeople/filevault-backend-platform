package com.filevault.upload;

import com.filevault.common.ApiException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChunkLayoutTest {
    @Test
    void computesFixedChunksAndTheFinalRemainder() {
        var layout = ChunkLayout.of(3L * ChunkLayout.CHUNK_BYTES + 123, 200L * 1024 * 1024);
        assertEquals(4, layout.count());
        assertEquals(ChunkLayout.CHUNK_BYTES, layout.expectedBytes(0));
        assertEquals(123, layout.expectedBytes(3));
        assertThrows(ApiException.class, () -> layout.expectedBytes(4));
        assertThrows(ApiException.class, () -> layout.expectedBytes(-1));
    }

    @Test
    void rejectsZeroAndOverLimitWithoutTruncation() {
        assertThrows(ApiException.class, () -> ChunkLayout.of(0, 100));
        assertThrows(ApiException.class, () -> ChunkLayout.of(Long.MAX_VALUE, 100));
        assertEquals(1, ChunkLayout.of(100, 100).count());
    }
}
