-- V365__requirement_review_log.sql — requirement review ledger (batched-review design)
-- Conversation edits only roll the requirement version; the review state stays
-- untouched until an explicit submit-review lands one of these rows. A PASS row
-- whose applied_version equals the live row version is what unlocks knowledge
-- base ingest (review gate: un-reviewed content never reaches grounding, which
-- seals the AI self-contamination feedback loop).
CREATE TABLE IF NOT EXISTS tp_requirement_review_log (
    id              BIGSERIAL PRIMARY KEY,
    requirement_id  BIGINT      NOT NULL REFERENCES tp_requirement(id),
    user_id         BIGINT      NOT NULL,
    action          VARCHAR(20) NOT NULL CHECK (action IN ('PASS', 'NEED_CHANGE', 'REJECT')),
    remark          VARCHAR(500),
    applied_version INT         NOT NULL,
    created_at      TIMESTAMP   NOT NULL DEFAULT now()
);

COMMENT ON TABLE  tp_requirement_review_log IS 'Requirement review ledger (submit-review PASS/NEED_CHANGE/REJECT with the version it was granted on)';
COMMENT ON COLUMN tp_requirement_review_log.applied_version IS 'tp_requirement.version the review was granted on; a newer version makes the review stale';
COMMENT ON COLUMN tp_requirement_review_log.remark IS 'Review note (mandatory for NEED_CHANGE/REJECT, same posture as case review)';

-- Newest-review lookups per requirement (freshness probe and detail-page history).
CREATE INDEX IF NOT EXISTS idx_requirement_review_log_req
    ON tp_requirement_review_log (requirement_id, id DESC);
