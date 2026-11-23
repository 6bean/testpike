-- V557__ai_review_auto_approve_keys.sql — factory seeds for the optional quick-review
-- auto-approve channel . Two keys:
--   * ai.review.auto-approve.enabled — ships OFF on purpose. Relaxing the human
--     review quality gate is a red-line exception surface: silence is not consent,
--     any flip must be an explicit user order. While off, every pending-review case
--     keeps waiting for a human reviewer exactly as before.
--   * ai.review.auto-approve.min-grade — the evidence-grade floor a case must reach
--     before the channel may consider it (3 = platform re-proof passed, the grade
--     ScriptWritebackPlatformReproof raises the case to when a trial run succeeds).
-- High AI self-assessed confidence (1) and the security-tag exemption are enforced
-- in code (AiReviewAutoApprovePolicy), never through these keys, and the exemption
-- cannot be lifted by the switch. Idempotent replay.
--
-- Flyway is not wired in this project: apply manually to the target database (DBA
-- path in production), then regenerate db/schema-init.sql with tools/ops/gen_schema_init.sh.

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.review.auto-approve.enabled', '0', '系统', 'string', 0,
     '快速评审自动通过开关（默认关闭：待评审用例照常等待人工评审。打开后：平台复证已通过、AI 自评为高置信的用例自动记为已通过并留评审记录；打了「安全」类标签的用例不受本开关影响，始终人工评审。在本页修改即时生效）'),
    ('ai.review.auto-approve.min-grade', '3', '系统', 'int', 0,
     '快速评审自动通过要求的最低证据分级（默认 3；1=站外自报无实证，2=站外实证在案，3=平台复证通过。证据分级达到该值且 AI 自评为高置信的用例才会被自动通过）')
ON CONFLICT (config_key) DO NOTHING;
