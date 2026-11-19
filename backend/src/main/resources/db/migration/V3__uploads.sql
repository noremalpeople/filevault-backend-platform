CREATE TABLE upload_sessions (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_id BIGINT NOT NULL,
    filename VARCHAR(180) NOT NULL,
    total_size BIGINT NOT NULL,
    chunk_size INT NOT NULL,
    chunk_count INT NOT NULL,
    status ENUM('OPEN','COMPLETED','ABORTED','EXPIRED') NOT NULL DEFAULT 'OPEN',
    expected_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    version INT NOT NULL DEFAULT 0,
    file_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY ix_upload_owner_status (owner_id, status, expires_at),
    KEY ix_upload_expiry (status, expires_at),
    CONSTRAINT fk_upload_owner FOREIGN KEY (owner_id) REFERENCES users(id),
    CONSTRAINT fk_upload_file FOREIGN KEY (file_id) REFERENCES user_files(id),
    CONSTRAINT ck_upload_size CHECK (total_size > 0 AND chunk_size > 0 AND chunk_count > 0),
    CONSTRAINT ck_upload_result CHECK ((file_id IS NOT NULL) = (status = 'COMPLETED'))
) ENGINE=InnoDB;

CREATE TABLE upload_chunks (
    session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    chunk_index INT NOT NULL,
    chunk_size INT NOT NULL,
    sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    stored_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (session_id, chunk_index),
    UNIQUE KEY uq_chunks_key (stored_key),
    CONSTRAINT fk_chunk_session FOREIGN KEY (session_id) REFERENCES upload_sessions(id),
    CONSTRAINT ck_chunk_size CHECK (chunk_size > 0 AND chunk_index >= 0)
) ENGINE=InnoDB;
