-- AX batch : goal-plan ledger for the online dialog. The ReAct loop used
-- to end a multi-step task as soon as the model wrote a plain-text answer,
-- silently leaving half the plan undone; this table carries the step ledger
-- (title/state/evidence per step) so the runner can nudge exactly once when
-- pending steps remain, and a "continue" turn can resume from the open tail.
-- * done steps only persist as done with an evidence anchor (anti fake-done —
--   the app-layer GoalPlanLedger downgrades an anchor-less done to pending).
-- * one row per (session, plan_version): a replayed identical step set is a
--   zero-write no-op and the revision only rides real changes (idempotency
--   discipline); plan_version 2+ is minted when a new goal replaces the old.
-- * governance key ai.chat.goal-reconcile-nudges: how many reconcile reminder
--   frames one run may inject (factory default 1; 0 closes the nudge, an
--   unparsable value falls back to 1 — a silently-wrong budget would either
--   loop the model or never remind).
CREATE TABLE IF NOT EXISTS tp_ai_chat_goal_plan (
    id           BIGSERIAL    PRIMARY KEY,
    session_id   BIGINT       NOT NULL REFERENCES tp_ai_chat_session(id) ON DELETE CASCADE,
    plan_version INT          NOT NULL DEFAULT 1,
    steps_json   TEXT         NOT NULL,
    revision     INT          NOT NULL DEFAULT 0,
    created_at     TIMESTAMP  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMP  NOT NULL DEFAULT now()
);

COMMENT ON TABLE  tp_ai_chat_goal_plan IS 'Goal-plan ledger of one dialog session (step list with done-evidence, resumed by the "continue" turn)';
COMMENT ON COLUMN tp_ai_chat_goal_plan.plan_version IS 'Minted per goal: 1 = first plan of the session, incremented when a new goal replaces a closed/open one; (session_id, plan_version) is the ledger identity';
COMMENT ON COLUMN tp_ai_chat_goal_plan.steps_json IS 'JSON array [{title,state=pending|done|skipped|blocked,evidence}]; a done step without an evidence anchor is normalised back to pending (fake-done guard)';
COMMENT ON COLUMN tp_ai_chat_goal_plan.revision IS 'Bumps only on a real step-set change; a replayed identical set writes nothing (idempotent-save guard)';

CREATE UNIQUE INDEX IF NOT EXISTS uq_ai_chat_goal_plan_session_version
    ON tp_ai_chat_goal_plan (session_id, plan_version);

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.chat.goal-reconcile-nudges', '1', '系统', 'int', 0,
     '对话续跑提醒次数上限（默认 1；AI 准备结束回答但目标账本还有未完成步骤时，系统最多提醒它的次数，达到上限后按现状结束并列出未完成步骤；填 0 表示不提醒；改值下一轮对话即时生效，无需重启）')
ON CONFLICT (config_key) DO NOTHING;
