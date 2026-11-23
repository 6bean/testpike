-- Migration: request transport protocol column .
-- Why: WebSocket requests had nowhere to declare their transport — the request
--      table only carried HTTP columns, so the executor could never branch on
--      protocol=ws and users were pushed into "fill ws:// into an HTTP form and
--      hope". This first-class column is the single dispatch discriminator for
--      the envelope and the form-face switch in the workbench.
-- Semantics: 'http' | 'ws', NOT NULL DEFAULT 'http' so every legacy row keeps
--      its exact old behavior with zero backfill. Vocabulary is validated at the
--      core save gate (single source common ApiWsContract); the DB stays a plain
--      varchar by project convention (shape checks live at the gate, not in CHECKs).

ALTER TABLE tp_apitest_request
    ADD COLUMN IF NOT EXISTS protocol VARCHAR(10) NOT NULL DEFAULT 'http';

COMMENT ON COLUMN tp_apitest_request.protocol IS
    'Transport protocol discriminator: http | ws (default http = legacy behavior). Save-gate validated by common ApiWsContract; envelope dispatch and the workbench form switch consume this value.';
