-- Extend the SSE frame ledger CHECK with the multi-step plan card frame type.
-- Plan card (AO multi-step plan adjudication): when the estimated tool-call
-- count of a turn reaches the ai.chat.plan-threshold value, the turn first emits
-- a 'plan' frame carrying the step list, so the user aligns on what will run
-- before it runs instead of watching the chain work. The frame is
-- ledger-persisted, so a reload shows the plan that was actually confirmed.
-- The enum ChatFrameType.PLAN and this list are pinned equal by
-- ChatFrameProtocolTest.ledgerCheckEnumeratesExactlyTheLedgerPersistedFrameTypes.
ALTER TABLE tp_ai_chat_frame
    DROP CONSTRAINT ck_ai_chat_frame_type;

ALTER TABLE tp_ai_chat_frame
    ADD CONSTRAINT ck_ai_chat_frame_type CHECK (event_type IN
        ('state', 'reason', 'trace', 'delta', 'draft', 'cite', 'done', 'error', 'user-inject', 'plan'));
