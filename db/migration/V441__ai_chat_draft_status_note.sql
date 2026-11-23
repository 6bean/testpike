-- piece 2b: durable carrier for the queue-renewal outcome on draft cards.
-- After one card of a target is confirmed, still-pending siblings are
-- re-anchored, turned stale (new state 4: the one-click-regenerate exit) or
-- voided by the exclusivity gate. Both non-pending transitions must tell the
-- human WHY in plain words (which versions, which surviving card), so the
-- ledger gains a status_note column. Service-side state machine only (K8
-- convention: no DB CHECK on status transitions).
ALTER TABLE tp_ai_chat_draft ADD COLUMN IF NOT EXISTS status_note VARCHAR(512);

COMMENT ON COLUMN tp_ai_chat_draft.status IS '1=pending 2=applied 3=voided 4=stale (anchor moved and the proposal would collide; the human regenerates it with one click) — transitions guarded service-side per K8 convention';
COMMENT ON COLUMN tp_ai_chat_draft.status_note IS 'Plain-word reason recorded when a renewal turn moves the card to stale (4) or voided (3); NULL for pending/applied rows and for anchors that merely re-anchored';
