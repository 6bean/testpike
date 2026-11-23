-- Extend the SSE frame ledger CHECK with the clarification card frame type.
-- Clarification card : when a task is missing a key slot and
-- guessing wrong is expensive, the turn ends on a 'clarify' frame carrying the
-- question plus 2-4 one-click options instead of the model answering on the
-- user's behalf. The frame is ledger-persisted, so a reload still shows the
-- question that was actually asked. The enum ChatFrameType.CLARIFY and this
-- list are pinned equal by
-- ChatFrameProtocolTest.ledgerCheckEnumeratesExactlyTheLedgerPersistedFrameTypes.
ALTER TABLE tp_ai_chat_frame
    DROP CONSTRAINT ck_ai_chat_frame_type;

ALTER TABLE tp_ai_chat_frame
    ADD CONSTRAINT ck_ai_chat_frame_type CHECK (event_type IN
        ('state', 'reason', 'trace', 'delta', 'draft', 'cite', 'done', 'error', 'user-inject', 'plan', 'clarify'));

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.chat.clarify-per-turn-max', '1', '系统', 'int', 0,
     '对话追问卡每轮上限（默认 1；AI 一轮回答最多向用户弹出几次追问选择卡，填 0 表示不再弹卡、追问内容改为纯文本显示；改值下一轮对话即时生效，无需重启）')
ON CONFLICT (config_key) DO NOTHING;
