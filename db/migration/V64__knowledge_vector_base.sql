
CREATE EXTENSION IF NOT EXISTS vector;

CREATE SCHEMA IF NOT EXISTS knowledge;

-- ---------------------------------------------------------------------
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS knowledge.tp_knowledge_chunk (
    id        UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    content   TEXT         NOT NULL,
    metadata  JSONB        NOT NULL DEFAULT '{}'::jsonb,
    embedding vector(1536) NOT NULL
);
COMMENT ON TABLE  knowledge.tp_knowledge_chunk IS '知识库分块向量表（G1/T175，14 §4.2；可随源数据重建不进备份链，PRD 6.6）';
COMMENT ON COLUMN knowledge.tp_knowledge_chunk.metadata IS '十字段键字典 JSONB：doc_id/project_id/pack_id/source_type/case_id/chunk_index/title/lang/excellent/status（14 §4.3）';

CREATE INDEX IF NOT EXISTS idx_knowledge_chunk_hnsw
    ON knowledge.tp_knowledge_chunk
    USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);

CREATE INDEX IF NOT EXISTS idx_knowledge_chunk_meta
    ON knowledge.tp_knowledge_chunk
    USING gin (metadata jsonb_path_ops);

-- ---------------------------------------------------------------------
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.tp_knowledge_doc (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id  BIGINT       NOT NULL DEFAULT 0,
    pack_id     BIGINT       NOT NULL DEFAULT 0,
    source_type VARCHAR(20)  NOT NULL,
    source_ref  BIGINT,
    title       VARCHAR(500) NOT NULL,
    content     TEXT         NOT NULL,
    status      VARCHAR(10)  NOT NULL DEFAULT 'ACTIVE',
    created_at  TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT uq_knowledge_doc_pack_title UNIQUE (pack_id, title),
    CONSTRAINT ck_knowledge_doc_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
);
COMMENT ON TABLE public.tp_knowledge_doc IS '知识库文档台账（G1/T175，14 §1；先写台账再投 MQ 异步向量化，对账基准）';
CREATE INDEX IF NOT EXISTS idx_knowledge_doc_project ON tp_knowledge_doc (project_id, source_type, status);
