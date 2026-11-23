-- Stage0 scenario preview ledger :
-- one requirement line is first expanded into a bounded scenario list (main
-- flow / equivalence / counter example / boundary / state combination) and the
-- enumeration outcome is persisted here so the human circle-selection step can
-- read, display and confirm it; only after selection does case generation run.
-- The preview is a draft shape: it never feeds the case tables directly.
--   * batch_id / seq group the rows of one batch submission (seq = row order,
--     stable under page reload so the selection UI can map rows back);
--   * scenarios holds the surviving items after the priority filter and cap;
--   * rounds_used / model_calls / converged / residual_claims carry the kernel
--     deliberation-ring accounting so a non-converged list surfaces honestly
--     instead of self-certifying (cost estimate consumes model calls too);
--   * expansion_enabled=false rows (switch off) are never written: the legacy
--     one-line-one-case flow continues with zero drift and zero cost.
--
-- Flyway is not wired in this project: apply manually to the target database
-- (DBA path in production), then regenerate db/schema-init.sql with
-- tools/ops/gen_schema_init.sh.

CREATE TABLE IF NOT EXISTS tp_ai_scenario_preview (
    id                BIGSERIAL PRIMARY KEY,
    batch_id          BIGINT,
    seq               INT NOT NULL DEFAULT 0,
    project_id        BIGINT NOT NULL,
    operator_id       BIGINT,
    requirement_text  TEXT NOT NULL,
    priority          INT NOT NULL DEFAULT 0,
    expansion_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    scenarios         JSONB NOT NULL DEFAULT '[]'::jsonb,
    residual_claims   JSONB NOT NULL DEFAULT '[]'::jsonb,
    rounds_used       INT NOT NULL DEFAULT 0,
    model_calls       INT NOT NULL DEFAULT 0,
    converged         BOOLEAN NOT NULL DEFAULT TRUE,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_ai_scenario_preview_batch
    ON tp_ai_scenario_preview (batch_id, seq);

COMMENT ON TABLE  tp_ai_scenario_preview IS 'Stage0 场景枚举预览台账（一行一清单，圈选确认的读取源；预览形态不直写用例表）';
COMMENT ON COLUMN tp_ai_scenario_preview.batch_id IS '所属批量提交 tp_ai_batch 逻辑引用（无 FK；单条生成可空）';
COMMENT ON COLUMN tp_ai_scenario_preview.seq IS '批内行序（与提交顺序一致，页面刷新后圈选可回填映射）';
COMMENT ON COLUMN tp_ai_scenario_preview.priority IS '需求优先级（GeneratedCase 约定 0=P0 1=P1 2=P2；P0 扩反例与边界、P2 仅主流程）';
COMMENT ON COLUMN tp_ai_scenario_preview.scenarios IS '幸存场景清单 JSONB（优先级过滤与上限裁剪之后；元素 {kind,title,anchor,note}）';
COMMENT ON COLUMN tp_ai_scenario_preview.residual_claims IS '推理确认环未收敛的残余分歧（空数组=已收敛）';
COMMENT ON COLUMN tp_ai_scenario_preview.rounds_used IS '推理确认环实际轮数';
COMMENT ON COLUMN tp_ai_scenario_preview.model_calls IS '本预览消耗的模型调用段数（成本预估消费项）';
COMMENT ON COLUMN tp_ai_scenario_preview.converged IS '终稿是否零分歧收敛（false=清单带残余分歧随圈选诚实亮出）';
