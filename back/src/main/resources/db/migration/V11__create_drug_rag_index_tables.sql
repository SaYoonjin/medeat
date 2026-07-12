CREATE TABLE rag_document (
    rag_document_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    item_seq BIGINT NOT NULL,
    drug_name VARCHAR(500),
    section_type VARCHAR(50) NOT NULL,
    content MEDIUMTEXT NOT NULL,
    content_hash CHAR(64) NOT NULL,
    document_version INT NOT NULL,
    source VARCHAR(255) NOT NULL,
    fetched_at DATETIME NOT NULL,
    lifecycle_status VARCHAR(30) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_rag_document_version (item_seq, section_type, document_version),
    KEY idx_rag_document_active (item_seq, section_type, lifecycle_status)
);

CREATE TABLE rag_chunk (
    rag_chunk_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    rag_document_id BIGINT NOT NULL,
    chunk_index INT NOT NULL,
    content TEXT NOT NULL,
    chunk_hash CHAR(64) NOT NULL,
    vector_id VARCHAR(100),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_rag_chunk_index (rag_document_id, chunk_index),
    CONSTRAINT fk_rag_chunk_document
        FOREIGN KEY (rag_document_id)
        REFERENCES rag_document (rag_document_id)
        ON DELETE CASCADE
);

CREATE TABLE rag_index_job (
    rag_index_job_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    rag_chunk_id BIGINT NOT NULL,
    job_status VARCHAR(30) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    next_retry_at DATETIME NULL,
    processing_started_at DATETIME NULL,
    claim_token VARCHAR(100) NULL,
    last_error_message TEXT NULL,
    completed_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_rag_index_job_chunk (rag_chunk_id),
    KEY idx_rag_index_job_status (job_status, next_retry_at),
    CONSTRAINT fk_rag_index_job_chunk
        FOREIGN KEY (rag_chunk_id)
        REFERENCES rag_chunk (rag_chunk_id)
        ON DELETE CASCADE
);
