
CREATE TABLE IF NOT EXISTS tp_knowledge_hit (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    doc_id      BIGINT      NOT NULL REFERENCES tp_knowledge_doc(id) ON DELETE CASCADE,
    task_id     BIGINT,
    scene       VARCHAR(40) NOT NULL,
    chunk_ids   JSONB       NOT NULL DEFAULT '[]'::jsonb,
    created_at  TIMESTAMP   NOT NULL DEFAULT now(),
    CONSTRAINT ck_knowledge_hit_chunk_ids CHECK (jsonb_typeof(chunk_ids) = 'array')
);

CREATE INDEX IF NOT EXISTS idx_knowledge_hit_doc  ON tp_knowledge_hit (doc_id, created_at);
CREATE INDEX IF NOT EXISTS idx_knowledge_hit_task ON tp_knowledge_hit (task_id);

COMMENT ON TABLE tp_knowledge_hit IS '知识命中留痕（V2+迁移件）：G2 检索按 doc 聚合观测行，被引用计数与溯源列表数据源';
COMMENT ON COLUMN tp_knowledge_hit.task_id   IS '消费任务 id（tp_ai_task 逻辑引用无 FK，可空=观测面可空，tp_skill_usage 同基径）';
COMMENT ON COLUMN tp_knowledge_hit.scene IS '检索场景（KnowledgeSearchScene 枚举名，14）';
COMMENT ON COLUMN tp_knowledge_hit.chunk_ids IS '本次检索该 doc 命中的 chunk id 数组（jsonb，溯源粒度）';
