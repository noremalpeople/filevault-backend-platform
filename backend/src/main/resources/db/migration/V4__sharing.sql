ALTER TABLE user_files ADD COLUMN media_type VARCHAR(80);
UPDATE user_files f JOIN file_objects o ON f.object_id = o.id AND f.owner_id = o.owner_id
SET f.media_type = o.media_type;
ALTER TABLE user_files MODIFY media_type VARCHAR(80) NOT NULL,
    ADD UNIQUE KEY uq_files_owner_ref (owner_id, id);

CREATE TABLE share_links (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_id BIGINT NOT NULL,
    user_file_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    revoked_at DATETIME(6),
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_share_token (token_hash),
    KEY ix_share_owner_file (owner_id, user_file_id),
    CONSTRAINT fk_share_owner_file FOREIGN KEY (owner_id, user_file_id) REFERENCES user_files(owner_id, id),
    CONSTRAINT ck_share_expiry CHECK (expires_at > created_at)
) ENGINE=InnoDB;
