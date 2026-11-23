-- Migration: batch failure-insight ledger .
--
-- Why this table exists:
--   When a batch finalizes, core broadcasts exec.batch.finalized; the ai-service
--   consumer clusters the failed tasks of that batch by root cause (AI attribution
--   class when it has landed, executor error category as the honest fallback with
--   the unattributed count kept visible). The clustering result is a per-batch
--   snapshot the report page and the notification center render, so it needs its
--   own ledger row — business tables stay untouched by ai-service (two-sides
--   separation: results land through the core internal write-back endpoint).
--   batch_id is UNIQUE: a replayed event overwrites the latest snapshot
--   (same latest-wins posture as the per-task classify write-back V94), so the
--   ledger never doubles for one batch.
--   clusters_json holds the ranked group list [{class,label,count,sampleCaseTitle}]
--   as TEXT (app-layer serialization, tp_batch_report JSONB-column precedent not
--   needed here since readers are display-only); project_id is server-derived from
--   the batch row at landing time (H1 isolation anchor — payload project ids are
--   never trusted).
-- Execution: manual psql (Flyway not enabled):
--   docker exec -i -e PGPASSWORD="$DB_PASSWORD" testpike-postgres psql -U "$DB_USERNAME" -d testpike -f - < this file
--   then re-run scripts/gen_schema_init.sh

CREATE TABLE IF NOT EXISTS tp_batch_insight (
    id                BIGSERIAL PRIMARY KEY,
    batch_id          BIGINT       NOT NULL UNIQUE,
    project_id        BIGINT       NOT NULL,
    fail_count        INT          NOT NULL DEFAULT 0,
    unattributed_count INT         NOT NULL DEFAULT 0,
    clusters_json     TEXT,
    summary           VARCHAR(500),
    created_at        TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at        TIMESTAMP    NOT NULL DEFAULT now()
);

COMMENT ON TABLE tp_batch_insight IS 'Per-batch failure root-cause insight ledger (AO-7 T432): written by ai-service clustering through the core internal write-back endpoint; UNIQUE batch_id means a replayed event refreshes the latest snapshot instead of stacking rows.';
COMMENT ON COLUMN tp_batch_insight.batch_id IS 'Owning batch (unique key: one insight card per batch)';
COMMENT ON COLUMN tp_batch_insight.project_id IS 'Owning project, read back from the batch row by the server at landing time (payload project ids are never trusted - H1 isolation anchor)';
COMMENT ON COLUMN tp_batch_insight.fail_count IS 'Total failed tasks that took part in the clustering';
COMMENT ON COLUMN tp_batch_insight.unattributed_count IS 'How many of them had no AI attribution yet and were grouped by executor error category instead - stated honestly on the card';
COMMENT ON COLUMN tp_batch_insight.clusters_json IS 'Ranked group list as JSON text [{class,label,count}]';
COMMENT ON COLUMN tp_batch_insight.summary IS 'Plain-word one-line summary shared by the station letter and the report-page card';
