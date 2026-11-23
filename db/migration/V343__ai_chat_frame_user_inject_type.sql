-- Extend the SSE frame ledger CHECK with the in-flight injection frame type.
-- Mid-turn steering (AO frame-protocol adjudication): a message the user sends
-- while a turn is running is echoed as a 'user-inject' ledger frame, so the
-- live stream, the reconnect replay and a history reload all prove the
-- injection was accepted instead of silently dropping it.
-- The enum ChatFrameType.USER_INJECT and this list are pinned equal by
-- ChatFrameProtocolTest.ledgerCheckEnumeratesExactlyTheLedgerPersistedFrameTypes.
ALTER TABLE tp_ai_chat_frame
    DROP CONSTRAINT ck_ai_chat_frame_type;

ALTER TABLE tp_ai_chat_frame
    ADD CONSTRAINT ck_ai_chat_frame_type CHECK (event_type IN
        ('state', 'reason', 'trace', 'delta', 'draft', 'cite', 'done', 'error', 'user-inject'));
