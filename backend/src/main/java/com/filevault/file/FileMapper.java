package com.filevault.file;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface FileMapper {
    String METADATA = """
            SELECT f.id, f.display_name AS displayName, o.storage_key AS storageKey,
                   o.sha256, o.size_bytes AS sizeBytes, o.media_type AS mediaType,
                   f.created_at AS createdAt
            FROM user_files f JOIN file_objects o ON o.id = f.object_id AND o.owner_id = f.owner_id
            WHERE f.owner_id = #{owner} AND f.deleted_at IS NULL
            """;

    record StoredFile(String id, String displayName, String storageKey, String sha256,
                      long sizeBytes, String mediaType, LocalDateTime createdAt) { }

    @Select(METADATA + " ORDER BY f.created_at DESC, f.id DESC LIMIT #{limit} OFFSET #{offset}")
    List<StoredFile> list(long owner, int limit, long offset);

    @Select(METADATA + " AND f.id = #{id}")
    StoredFile find(long owner, String id);

    @Select("SELECT COALESCE(SUM(size_bytes), 0) FROM file_objects WHERE owner_id = #{owner}")
    long allocatedBytes(long owner);

    @Insert("""
            INSERT INTO file_objects(id, owner_id, storage_key, sha256, size_bytes, media_type)
            VALUES (#{id}, #{owner}, #{key}, #{sha256}, #{size}, #{mediaType})
            """)
    void insertObject(String id, long owner, String key, String sha256, long size, String mediaType);

    @Insert("INSERT INTO user_files(id, owner_id, object_id, display_name) VALUES (#{id}, #{owner}, #{object}, #{name})")
    void insertFile(String id, long owner, String object, String name);

    @Update("UPDATE user_files SET display_name = #{name} WHERE owner_id = #{owner} AND id = #{id} AND deleted_at IS NULL")
    int rename(long owner, String id, String name);

    @Update("UPDATE user_files SET deleted_at = UTC_TIMESTAMP(6) WHERE owner_id = #{owner} AND id = #{id} AND deleted_at IS NULL")
    int softDelete(long owner, String id);
}
