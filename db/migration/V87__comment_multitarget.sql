
ALTER TABLE tp_comment DROP CONSTRAINT IF EXISTS ck_comment_host;
DO $$ BEGIN
    ALTER TABLE tp_comment ADD CONSTRAINT ck_comment_host
        CHECK (host_type IN ('review', 'case', 'batch', 'defect'));
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

CREATE TABLE IF NOT EXISTS tp_comment_attachment (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    comment_id   BIGINT       NOT NULL,
    file_name    VARCHAR(255) NOT NULL,
    object_key   VARCHAR(512) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    size_bytes   BIGINT       NOT NULL,
    created_at   TIMESTAMP    NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_comment_att_comment ON tp_comment_attachment (comment_id);

CREATE TABLE IF NOT EXISTS tp_comment_read (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id              BIGINT      NOT NULL,
    host_type            VARCHAR(20) NOT NULL,
    host_id              BIGINT      NOT NULL,
    last_read_comment_id BIGINT      NOT NULL,
    updated_at           TIMESTAMP   NOT NULL DEFAULT now(),
    CONSTRAINT uq_comment_read_pos UNIQUE (user_id, host_type, host_id)
);

COMMENT ON TABLE tp_comment IS '多态评论表（V14 四宿主：评审工作台/用例/批次/缺陷；T232 系统追评 + T240 人工评论）';
COMMENT ON TABLE tp_comment_attachment IS '评论附件表（V14/T240；object_key 走 StorageRouter，local/OSS 透明，V81 加密配置沿用）';
COMMENT ON TABLE tp_comment_read IS '评论读位表（V14/T240 未读角标；last_read_comment_id 比较法单 SQL 聚合）';
