CREATE TABLE IF NOT EXISTS tp_doc_change_analysis (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id         BIGINT        NOT NULL,
    old_document_id    BIGINT        NOT NULL,
    new_document_id    BIGINT        NOT NULL,
    changes            JSONB         NOT NULL,
    chapter_locatable  BOOLEAN       NOT NULL DEFAULT TRUE,
    status             SMALLINT      NOT NULL DEFAULT 0,
    created_by         BIGINT,
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    handled_by         BIGINT,
    handled_at         TIMESTAMPTZ,
    handle_note        TEXT,
    CONSTRAINT ck_doc_change_analysis_status CHECK (status IN (0, 1))
);
CREATE INDEX IF NOT EXISTS idx_doc_change_analysis_project
    ON tp_doc_change_analysis (project_id, status, id DESC);

COMMENT ON TABLE  tp_doc_change_analysis IS 'F3 文档重传变更分析（复核队列）：章节 diff + 受影响需求点/用例清单 + 处置留痕';
COMMENT ON COLUMN tp_doc_change_analysis.changes           IS 'JSONB 明细=[{chapter,changeType:add|modify|delete,requirementIds[],caseIds[]}]';
COMMENT ON COLUMN tp_doc_change_analysis.chapter_locatable IS 'false=无标题文档全文单块对照（章节不可定位）';
COMMENT ON COLUMN tp_doc_change_analysis.status            IS '0=待复核 1=已处置（处置仅留痕，不改用例状态机）';
