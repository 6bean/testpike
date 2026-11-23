
CREATE TABLE IF NOT EXISTS tp_document (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id   BIGINT       NOT NULL,
    name         VARCHAR(300),
    file_type    VARCHAR(10),                              -- pdf / markdown
    file_hash    CHAR(64)     NOT NULL UNIQUE,
    size_bytes   BIGINT,
    storage_key  VARCHAR(500),
    status       SMALLINT     NOT NULL DEFAULT 0,
    chunk_count  INT          NOT NULL DEFAULT 0,
    error_msg    VARCHAR(500),
    created_by   BIGINT,
    created_at   TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at   TIMESTAMP    NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_document_project_status ON tp_document (project_id, status);
COMMENT ON TABLE tp_document IS 'F1 需求文档（01 M2-α ；status 3/4=F2 提取任务态并入——extract_state 扩展位注记）';

CREATE TABLE IF NOT EXISTS tp_document_chunk (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    document_id BIGINT       NOT NULL,
    chapter     VARCHAR(200),
    seq         INT          NOT NULL,
    content     TEXT,
    tsv         tsvector GENERATED ALWAYS AS (to_tsvector('simple', coalesce(content, ''))) STORED,
    token_est   INT,
    CONSTRAINT fk_document_chunk_doc FOREIGN KEY (document_id)
        REFERENCES tp_document (id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_document_chunk_seq ON tp_document_chunk (document_id, seq);
CREATE INDEX IF NOT EXISTS idx_document_chunk_tsv ON tp_document_chunk USING GIN (tsv);
COMMENT ON TABLE tp_document_chunk IS 'F1 文档分块（08 检索方案：tsvector 生成列 + GIN；zhparser 不可用降级 simple——08 L51 兜底口径）';

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
('ai.doc.max-size-mb', '100', '系统', 'int', 0, 'F1 文档上传大小上限 MB（超限 400 可读，02 §3.3）')
ON CONFLICT (config_key) DO NOTHING;
