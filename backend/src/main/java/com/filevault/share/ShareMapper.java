package com.filevault.share;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
interface ShareMapper {
    record SharedFile(String name, String storageKey, long sizeBytes, String mediaType, LocalDateTime expiresAt, String sha256) { }
    record ActiveShare(String id, LocalDateTime expiresAt) { }

    @Select("""
            SELECT id, expires_at AS expiresAt
            FROM share_links
            WHERE owner_id = #{owner} AND user_file_id = #{file}
                  AND revoked_at IS NULL AND expires_at > UTC_TIMESTAMP(6)
            ORDER BY created_at DESC, id DESC
            """)
    List<ActiveShare> listActive(long owner, String file);

    @Insert("""
            INSERT INTO share_links(id, owner_id, user_file_id, token_hash, expires_at, created_at)
            VALUES (#{id}, #{owner}, #{file}, #{digest}, #{expiresAt}, UTC_TIMESTAMP(6))
            """)
    void insert(String id, long owner, String file, String digest, LocalDateTime expiresAt);

    @Update("""
            UPDATE share_links SET revoked_at = COALESCE(revoked_at, UTC_TIMESTAMP(6))
            WHERE owner_id = #{owner} AND id = #{id}
            """)
    int revoke(long owner, String id);

    @Select("SELECT COUNT(*) FROM share_links WHERE owner_id = #{owner} AND id = #{id}")
    int owns(long owner, String id);

    @Select("""
            SELECT f.display_name AS name, o.storage_key AS storageKey, o.size_bytes AS sizeBytes,
                   f.media_type AS mediaType, s.expires_at AS expiresAt, o.sha256
            FROM share_links s
            JOIN user_files f ON f.id = s.user_file_id AND f.owner_id = s.owner_id
            JOIN file_objects o ON o.id = f.object_id AND o.owner_id = f.owner_id
            WHERE s.token_hash = #{digest} AND s.revoked_at IS NULL AND s.expires_at > UTC_TIMESTAMP(6)
                  AND f.deleted_at IS NULL AND o.state = 'READY'
            """)
    SharedFile findActive(String digest);
}
