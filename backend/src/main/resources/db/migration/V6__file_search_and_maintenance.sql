CREATE INDEX ix_files_owner_name ON user_files(owner_id, deleted_at, display_name);

CREATE TABLE storage_audit_jobs (
    id BIGINT NOT NULL AUTO_INCREMENT,
    period_key BIGINT NOT NULL,
    state ENUM('PENDING','RUNNING','SUCCEEDED','FAILED') NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    available_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    lease_until DATETIME(6),
    claim_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
    finished_at DATETIME(6),
    error_kind VARCHAR(80),
    report VARCHAR(512),
    PRIMARY KEY (id),
    UNIQUE KEY uq_storage_audit_period (period_key),
    KEY ix_storage_audit_ready (state, available_at, id),
    KEY ix_storage_audit_lease (state, lease_until)
) ENGINE=InnoDB;
