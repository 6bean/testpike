-- Requirement-side version base (dialog iteration batch, .
--
-- Why this migration exists:
--   tp_requirement was the only mutable business entity without a version
--   chain - case/skill/tool/script all snapshot every edit into a *_version
--   table while requirement edits overwrote in place, so an AI draft landed
--   against a stale requirement could not be refused and the change history
--   was unauditable. This adds the same shape the case chain uses:
--   a monotonically bumped version line on the entity + an append-only
--   snapshot table keyed by (requirement_id, version).
--   Columns:
--     version  - optimistic-lock anchor re-checked at every UPDATE (CAS);
--                seeded 1 at create, bumped on each real change.
--     deleted  - soft-delete flag UNIFYING the previous split between
--                physical DELETE and status=3 offline (F3): rows stay for
--                the version chain to reference; readers add deleted = 0.
--   tp_requirement_version mirrors tp_case_version semantics:
--     snapshot_json holds the full row projection at that version,
--     change_type 1=EDIT 2=STATUS 3=DELETE (content-deduped writes - an
--     edit that changes nothing burns no version).
--   Idempotent: guarded via IF NOT EXISTS so a re-run is a no-op.
--   Note: Flyway is not enabled - apply by hand (DBA channel), same as the
--   rest of db/migration.
-- Usage: docker exec -i -e PGPASSWORD="$DB_PASSWORD" testpike-postgres psql -U "$DB_USERNAME" -d testpike -f - < this file

ALTER TABLE tp_requirement ADD COLUMN IF NOT EXISTS version smallint NOT NULL DEFAULT 1;
ALTER TABLE tp_requirement ADD COLUMN IF NOT EXISTS deleted smallint NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS tp_requirement_version (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    requirement_id bigint NOT NULL,
    version       integer    NOT NULL,
    snapshot_json jsonb      NOT NULL,
    change_type   smallint   NOT NULL,
    operator_id   bigint,
    created_at    timestamp  NOT NULL DEFAULT now(),
    CONSTRAINT uk_requirement_version UNIQUE (requirement_id, version),
    CONSTRAINT ck_requirement_version_change_type CHECK (change_type IN (1, 2, 3))
);

CREATE INDEX IF NOT EXISTS idx_requirement_version_req
    ON tp_requirement_version (requirement_id, version DESC);

-- Factory switch for requirement draft cards in the online AI dialog (R32:
-- AI-driven features ship ON, zero configuration; closing the key makes a
-- write-tool call journal one state frame naming the setting path and key).
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES
    ('ai.chat.requirement-draft.enabled', 'true', '系统', 'bool', 0,
     '需求点草案开关（默认开启；对话中 AI 提出新建或修订需求点时附草案卡，确认后走需求页同一保存链——标题长度、状态取值、项目归属照常在确认时复核；编辑草案带版本锚，需求点在此期间被他人改过会被拒绝并提示重新分析；关闭时回答照常给出，不再附草案卡）')
ON CONFLICT (config_key) DO NOTHING;
