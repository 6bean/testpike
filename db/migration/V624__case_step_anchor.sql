-- V624: step-level locator anchor column on tp_case_step .
-- Purpose: each case step may carry the page-element anchor it was produced from
-- (dialect single source {{ELEM:name}} / folded contract keys), so the review and
-- repair UIs can jump from a step to the concrete element evidence.
-- Semantics: nullable jsonb object; NULL = legacy rows and manually authored steps
-- without anchor evidence. No backfill: historical rows stay untouched.
ALTER TABLE tp_case_step ADD COLUMN IF NOT EXISTS anchor jsonb;

COMMENT ON COLUMN tp_case_step.anchor IS 'Step-level element anchor evidence (jsonb object: locator/ref/kind); NULL when no anchor exists. Owned by WY T683 consumer chain; producer side belongs to the WE batch ledger.';
