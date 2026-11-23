-- Migration: the process-event ledger keeps the unclipped twin
-- of an over-budget step detail in a dedicated column, so the honest
-- full-text read door (an over-budget write without this copy would be a
-- silent hard cut dressed up as a pointer). Legacy rows keep NULL and the
-- read door answers their plain detail.
ALTER TABLE tp_ai_task_event ADD COLUMN IF NOT EXISTS detail_full TEXT;
COMMENT ON COLUMN tp_ai_task_event.detail_full IS 'Unclipped trail text stored only when the detail preview was cut at the ui.tail.detail-max budget or the response body exceeded session.probe.body-max-bytes; served through the gated full-text read door (login + project membership re-check + audit row), never through the plain tail window';
