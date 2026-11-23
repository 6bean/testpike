-- Migration: downstream impact ledger + started/unstarted detection predicates
-- .
-- Why this table exists: when an upstream object is saved, the linkage bus
-- finds the invalidated downstream rows (registered edges in
-- tp_change_linkage). Those findings need one display surface — the stale
-- mark on an unstarted plan reference, the disclosure of an issued batch row,
-- the pending grouped-draft that awaits the one-click human accept. Keeping
-- the findings as ledger rows instead of writing status columns into each
-- downstream business table is the boundary adjudicated in the batch design
-- (section 3.1): marking is automatic, changing business content is not, and
-- no detection pass may rewrite an issued record.
-- Idempotency anchor: a row is keyed by (edge_key, downstream_object_type,
-- downstream_id) and stamped with the upstream version it reflects;
-- re-announcing the same version changes nothing, a newer version re-arms a
-- consumed row.
CREATE TABLE IF NOT EXISTS tp_linkage_impact (
    id                     BIGSERIAL    PRIMARY KEY,
    edge_key               VARCHAR(120) NOT NULL,
    upstream_object_type   VARCHAR(64)  NOT NULL,
    upstream_object_id     BIGINT       NOT NULL,
    upstream_version       INT          NOT NULL DEFAULT 0,
    downstream_object_type VARCHAR(64)  NOT NULL,
    downstream_id          BIGINT       NOT NULL,
    disposition_strategy   VARCHAR(32)  NOT NULL,
    status                 SMALLINT     NOT NULL DEFAULT 1,
    annotated_at           TIMESTAMP    NOT NULL DEFAULT now(),
    consumed_at            TIMESTAMP,
    CONSTRAINT uq_linkage_impact UNIQUE (edge_key, downstream_object_type, downstream_id),
    CONSTRAINT ck_linkage_impact_disposition CHECK (disposition_strategy IN
        ('mark_only', 'grouped_draft', 'disclose_only', 'notify')),
    CONSTRAINT ck_linkage_impact_status CHECK (status IN (1, 2, 3, 4))
);

CREATE INDEX IF NOT EXISTS idx_linkage_impact_upstream
    ON tp_linkage_impact (upstream_object_type, upstream_object_id);
CREATE INDEX IF NOT EXISTS idx_linkage_impact_pending
    ON tp_linkage_impact (downstream_object_type, status) WHERE status IN (1, 2, 3);

COMMENT ON TABLE tp_linkage_impact IS 'Downstream rows the linkage bus invalidated by an upstream save; the single surface for stale marks, disclosures and pending grouped drafts';
COMMENT ON COLUMN tp_linkage_impact.edge_key IS 'Registry key of the edge that found this impact (tp_change_linkage.edge_key)';
COMMENT ON COLUMN tp_linkage_impact.upstream_version IS 'Upstream row version this impact reflects; re-announcing it writes nothing, a newer version re-arms the row';
COMMENT ON COLUMN tp_linkage_impact.disposition_strategy IS 'Shape copied from the edge at detection time: mark_only=reversible stale mark, grouped_draft=pending draft for the one-click accept, disclose_only=read-only disclosure of an issued record, notify=reminder pipeline';
COMMENT ON COLUMN tp_linkage_impact.status IS '1=stale mark, 2=draft pending, 3=disclosed, 4=consumed (accepted or acknowledged)';
COMMENT ON COLUMN tp_linkage_impact.consumed_at IS 'Set when a human or automation consumed the pending row; null while it still waits';

-- Revision of two seeded detector predicates (ledger data, not code): the
-- batch semantics split by whether the execution was issued. An unstarted
-- plan reference gets the automatic stale mark; once the plan is finished or
-- canceled the ledger would only be noise. A batch reference is disclosed
-- only after the batch actually started; a draft batch is not an issued
-- record, so its downstream edit path stays with the draft edges.
UPDATE tp_change_linkage
   SET detector_ref = 'SELECT pc.id FROM tp_plan_case pc JOIN tp_exec_plan p'
       || ' ON p.id = pc.plan_id WHERE pc.case_id = :objectId AND p.status < 4',
       updated_at = now()
 WHERE edge_key = 'case->plan_case'
   AND detector_ref IS DISTINCT FROM 'SELECT pc.id FROM tp_plan_case pc'
       || ' JOIN tp_exec_plan p ON p.id = pc.plan_id WHERE pc.case_id = :objectId AND p.status < 4';

UPDATE tp_change_linkage
   SET detector_ref = 'SELECT bc.id FROM tp_batch_case bc JOIN tp_exec_batch b'
       || ' ON b.id = bc.batch_id WHERE bc.case_id = :objectId AND b.status >= 4',
       updated_at = now()
 WHERE edge_key = 'case->batch_case'
   AND detector_ref IS DISTINCT FROM 'SELECT bc.id FROM tp_batch_case bc'
       || ' JOIN tp_exec_batch b ON b.id = bc.batch_id WHERE bc.case_id = :objectId AND b.status >= 4';
