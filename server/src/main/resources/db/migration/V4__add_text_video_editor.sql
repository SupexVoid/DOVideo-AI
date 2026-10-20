CREATE TABLE media_transcripts (
    id BIGINT NOT NULL AUTO_INCREMENT,
    media_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    revision INT NOT NULL,
    source VARCHAR(32) NOT NULL,
    duration_ms BIGINT NOT NULL,
    state VARCHAR(32) NOT NULL,
    error_code VARCHAR(128) NULL,
    cues_json JSON NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_transcript_media_revision (media_id,revision),
    KEY idx_transcript_owner_media (user_id,media_id,revision),
    CONSTRAINT chk_transcript_state CHECK (state IN ('QUEUED','PROCESSING','COMPLETED','FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE media_edit_projects (
    id BIGINT NOT NULL AUTO_INCREMENT,
    media_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    transcript_id BIGINT NOT NULL,
    version INT NOT NULL,
    kept_cue_ids_json JSON NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_project_owner_media_updated (user_id,media_id,updated_at),
    KEY idx_project_transcript (transcript_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE media_export_jobs (
    id VARCHAR(36) NOT NULL,
    user_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    project_version INT NOT NULL,
    media_id BIGINT NOT NULL,
    transcript_id BIGINT NOT NULL,
    duration_ms BIGINT NOT NULL,
    source_object VARCHAR(1024) NOT NULL,
    source_hash VARCHAR(128) NULL,
    request_hash VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    snapshot_json JSON NOT NULL,
    preset VARCHAR(32) NOT NULL,
    attempt INT NOT NULL DEFAULT 1,
    state VARCHAR(32) NOT NULL,
    result_object VARCHAR(1024) NULL,
    error_code VARCHAR(128) NULL,
    error_message VARCHAR(1000) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    started_at DATETIME(3) NULL,
    completed_at DATETIME(3) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_export_owner_request (user_id,request_hash),
    KEY idx_export_state_updated (state,updated_at),
    KEY idx_export_owner_project (user_id,project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
