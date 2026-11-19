package com.filevault.upload;

import com.filevault.storage.LocalStorage;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
interface UploadMapper {
    String SESSION = """
            SELECT id, filename, total_size AS totalSize, chunk_size AS chunkSize,
                   chunk_count AS chunkCount, status, expected_sha256 AS expectedSha256,
                   expires_at AS expiresAt, file_id AS fileId
            FROM upload_sessions WHERE owner_id = #{owner} AND id = #{id}
            """;
    String CHUNK_COLUMNS = """
            c.session_id AS sessionId, c.chunk_index AS chunkIndex, c.chunk_size AS chunkSize,
            c.sha256, c.stored_key AS storedKey
            """;

    record Session(String id, String filename, long totalSize, int chunkSize, int chunkCount, String status,
                   String expectedSha256, LocalDateTime expiresAt, String fileId) { }

    record Chunk(String sessionId, int chunkIndex, int chunkSize, String sha256, String storedKey) {
        LocalStorage.Part part() {
            return new LocalStorage.Part(storedKey, chunkSize, sha256);
        }
    }

    @Select(SESSION)
    Session find(long owner, String id);

    @Select(SESSION + " FOR UPDATE")
    Session lock(long owner, String id);

    @Select("""
            SELECT COUNT(*) FROM upload_sessions
            WHERE owner_id = #{owner} AND status = 'OPEN' AND expires_at > UTC_TIMESTAMP(6)
            """)
    int openCount(long owner);

    @Select("""
            SELECT COALESCE((SELECT SUM(total_size) FROM upload_sessions
                             WHERE owner_id = #{owner} AND status = 'OPEN' AND expires_at > UTC_TIMESTAMP(6)), 0)
                 + COALESCE((SELECT SUM(c.chunk_size) FROM upload_chunks c JOIN upload_sessions s ON s.id = c.session_id
                             WHERE s.owner_id = #{owner} AND (s.status <> 'OPEN' OR s.expires_at <= UTC_TIMESTAMP(6))), 0)
            """)
    long reservedBytes(long owner);

    @Insert("""
            INSERT INTO upload_sessions(id,owner_id,filename,total_size,chunk_size,chunk_count,expected_sha256,expires_at)
            VALUES (#{id},#{owner},#{filename},#{total},#{size},#{count},#{sha256},#{expiry})
            """)
    void insert(String id, long owner, String filename, long total, int size, int count, String sha256,
                LocalDateTime expiry);

    @Update("""
            UPDATE upload_sessions SET status = #{state}, file_id = #{fileId,jdbcType=CHAR}, version = version + 1
            WHERE id = #{id} AND owner_id = #{owner} AND status = 'OPEN'
            """)
    int close(long owner, String id, String state, String fileId);

    @Update("""
            UPDATE upload_sessions SET status = 'EXPIRED', version = version + 1
            WHERE status = 'OPEN' AND expires_at <= UTC_TIMESTAMP(6)
            """)
    int expire();

    @Select("SELECT " + CHUNK_COLUMNS + " FROM upload_chunks c WHERE c.session_id = #{id} ORDER BY c.chunk_index")
    List<Chunk> chunks(String id);

    @Select("SELECT " + CHUNK_COLUMNS + " FROM upload_chunks c WHERE c.session_id = #{id} AND c.chunk_index = #{index}")
    Chunk chunk(String id, int index);

    @Insert("""
            INSERT INTO upload_chunks(session_id,chunk_index,chunk_size,sha256,stored_key)
            VALUES (#{id},#{index},#{size},#{sha256},#{key})
            """)
    void insertChunk(String id, int index, int size, String sha256, String key);

    @Select("SELECT " + CHUNK_COLUMNS + """
            FROM upload_chunks c JOIN upload_sessions s ON c.session_id = s.id
            WHERE s.status IN ('EXPIRED','ABORTED','COMPLETED','FAILED')
              AND (c.cleanup_after IS NULL OR c.cleanup_after <= UTC_TIMESTAMP(6))
            ORDER BY c.session_id,c.chunk_index LIMIT 100
            """)
    List<Chunk> closedChunks();

    @Delete("DELETE FROM upload_chunks WHERE session_id = #{sessionId} AND chunk_index = #{chunkIndex}")
    void forget(String sessionId, int chunkIndex);

    @Update("""
            UPDATE upload_chunks SET cleanup_after = DATE_ADD(UTC_TIMESTAMP(6), INTERVAL #{seconds} SECOND)
            WHERE session_id = #{sessionId} AND chunk_index = #{chunkIndex}
            """)
    void deferCleanup(String sessionId, int chunkIndex, int seconds);
}
