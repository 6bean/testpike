-- V544__web_element_ledger_base.sql — Web element ledger base table (WE batch S0, ).
-- Elevates a DOM locator from a string hard-coded inside case scripts to a
-- first-class per-project asset: one row = one page element with an ORDERED set
-- of locator anchors (anchors JSONB, each {type,value,strength,note}) plus its
-- lifecycle state. Runtime tries anchors in order (executor side, later segment);
-- a redesign repairs one ledger row instead of every referencing case.
--   state: 0=candidate 1=confirmed 2=stale 3=retired (transition table is
-- enforced service-side; the CHECK here only bounds the domain).
--   confidence: feedback-recomputed hit quality from execution reports, NOT a
--   model self-score (kept as a separate measure from evidence_grade by design).
--   revision: optimistic-lock counter bumped on every anchor/state change;
--   stale writes are refused with the shared 40901 version-conflict semantics.
--   page_key: origin + folded path template (single-source normalization shared
--   with the contract endpoint-key rules so /pets/123 and /pets/{id} land on one
--   row instead of fragmenting the asset).
-- R57: rows accumulate, so two hot-updatable keys bound the footprint — retired
-- rows age out after element.retention-days, unverified candidates after
-- element.candidate-ttl-days (sweep consumers wire in this segment's follow-ups).
CREATE TABLE IF NOT EXISTS tp_web_element (
    id           BIGSERIAL PRIMARY KEY,
    project_id   BIGINT NOT NULL REFERENCES tp_project (id),
    name         VARCHAR(120) NOT NULL,
    page_key     VARCHAR(200) NOT NULL,
    anchors      JSONB NOT NULL DEFAULT '[]'::jsonb,
    state        SMALLINT NOT NULL DEFAULT 0,
    confidence   DOUBLE PRECISION NOT NULL DEFAULT 0.5,
    hit_count    BIGINT NOT NULL DEFAULT 0,
    miss_count   BIGINT NOT NULL DEFAULT 0,
    last_hit_at  TIMESTAMP NULL,
    last_miss_at TIMESTAMP NULL,
    evidence     JSONB NULL,
    revision     INT NOT NULL DEFAULT 1,
    created_at   TIMESTAMP NOT NULL DEFAULT now(),
    updated_at   TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT uq_web_element_project_page_name UNIQUE (project_id, page_key, name),
    CONSTRAINT ck_web_element_state CHECK (state IN (0, 1, 2, 3)),
    CONSTRAINT ck_web_element_confidence CHECK (confidence >= 0 AND confidence <= 1),
    CONSTRAINT ck_web_element_anchors_array CHECK (jsonb_typeof(anchors) = 'array'),
    CONSTRAINT ck_web_element_evidence_object CHECK (evidence IS NULL OR jsonb_typeof(evidence) = 'object')
);

COMMENT ON TABLE tp_web_element IS 'Web element ledger: per-project page elements with ordered locator anchors and lifecycle state (WE batch S0).';
COMMENT ON COLUMN tp_web_element.anchors IS 'Ordered anchor array: [{type: css|xpath|text|placeholder|alt|testid|rel, value, strength 0..1, note}]';
COMMENT ON COLUMN tp_web_element.state IS '0=candidate 1=confirmed 2=stale 3=retired';
COMMENT ON COLUMN tp_web_element.confidence IS 'Feedback-recomputed hit quality from execution reports, never a model self-score.';
COMMENT ON COLUMN tp_web_element.revision IS 'Optimistic lock: +1 on every anchor or state change; stale writes refused as 40901.';

-- Listing/filter shape of the ledger page: segment by project, then page, then state.
CREATE INDEX IF NOT EXISTS idx_web_element_project_page
    ON tp_web_element (project_id, page_key);
CREATE INDEX IF NOT EXISTS idx_web_element_project_state
    ON tp_web_element (project_id, state);

-- Hot-updatable retention keys (idempotent replay, tp_sys_config is the live source).
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
('element.retention-days', '90', '系统', 'int', 0, '网页元素账本中已退役条目的保留天数（取值范围 1-3650；到期后条目被自动清理，命中与失手计数不再累计）'),
('element.candidate-ttl-days', '90', '系统', 'int', 0, '网页元素账本中候选条目的待验证时长上限（取值范围 1-3650；候选条目超过该天数仍未通过执行验证会自动转为退役）')
ON CONFLICT (config_key) DO NOTHING;
