CREATE TABLE file_objects (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_id BIGINT NOT NULL,
    storage_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    size_bytes BIGINT NOT NULL,
    media_type VARCHAR(80) NOT NULL,
    state ENUM('READY') NOT NULL DEFAULT 'READY',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_objects_storage (storage_key),
    UNIQUE KEY uq_objects_owner_ref (owner_id, id),
    KEY ix_objects_owner_content (owner_id, sha256, size_bytes),
    CONSTRAINT fk_objects_owner FOREIGN KEY (owner_id) REFERENCES users(id),
    CONSTRAINT ck_objects_size CHECK (size_bytes > 0)
) ENGINE=InnoDB;

CREATE TABLE user_files (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_id BIGINT NOT NULL,
    object_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    display_name VARCHAR(180) NOT NULL,
    deleted_at DATETIME(6),
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY ix_files_list (owner_id, deleted_at, created_at, id),
    KEY ix_files_object (owner_id, object_id),
    CONSTRAINT fk_files_object_owner FOREIGN KEY (owner_id, object_id) REFERENCES file_objects(owner_id, id)
) ENGINE=InnoDB;
