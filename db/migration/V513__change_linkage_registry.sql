-- Migration: change-linkage edge registry .
-- Why this table exists: every mutable object (case, requirement, api contract,
-- mock stub, plan, template, skill, ...) can be edited and saved repeatedly, and
-- each save may invalidate downstream rows. Before this change that fan-out lived
-- in scattered hooks inside each save chain, so nobody could answer "which
-- downstream objects does saving X touch?" with data. The registry makes an
-- upstream->downstream edge a ROW instead of code: adding a new entity to the
-- change-propagation loop = insert one row, and the orphan-edge gate can then
-- diff foreign-key columns in the live database against this table.
-- Boundary (adjudicated in the batch design doc, section 3.1): risk_level splits
-- what may happen automatically from what needs a human. 1 = reversible mark or
-- display state (stale flag, affected counter, pending-disposition list) which the
-- linkage bus may apply with zero clicks; 2 = a real change to downstream business
-- content (stub match, case step, request parameter) which must surface as a
-- grouped draft card and only lands after the one-click human accept; 3 = an
-- immutable or already-issued record which may only be disclosed, never rewritten.
-- No row authorizes content changes by itself: the disposition strategy names the
-- shape, the write confirmation gate stays where it is.
-- exempt: rows whose upstream->downstream relation is intentionally non-propagating
-- (per the opt-out asset design) are registered with exempt=1 and a written reason,
-- so the orphan-edge scan reads them as covered instead of reporting them red.
-- Version-number collision note: both the main tree and this chain tree were at
-- V512 when this segment opened; V513 is taken by the registry.
CREATE TABLE IF NOT EXISTS tp_change_linkage (
    id                     BIGSERIAL    PRIMARY KEY,
    edge_key               VARCHAR(120) NOT NULL,
    upstream_object_type   VARCHAR(64)  NOT NULL,
    downstream_object_type VARCHAR(64)  NOT NULL,
    detector_kind          SMALLINT     NOT NULL DEFAULT 1,
    detector_ref           VARCHAR(200),
    trigger_field_predicate VARCHAR(500),
    disposition_strategy   VARCHAR(32)  NOT NULL,
    risk_level             SMALLINT     NOT NULL,
    exempt                 SMALLINT     NOT NULL DEFAULT 0,
    exempt_reason          VARCHAR(500),
    enabled                SMALLINT     NOT NULL DEFAULT 1,
    edge_note              VARCHAR(500),
    created_at             TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at             TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT uq_change_linkage_edge_key UNIQUE (edge_key),
    CONSTRAINT ck_change_linkage_detector_kind CHECK (detector_kind IN (1, 2, 3)),
    CONSTRAINT ck_change_linkage_disposition CHECK (disposition_strategy IN
        ('mark_only', 'grouped_draft', 'disclose_only', 'notify', 'exempt')),
    CONSTRAINT ck_change_linkage_risk_level CHECK (risk_level IN (1, 2, 3)),
    CONSTRAINT ck_change_linkage_exempt CHECK (exempt IN (0, 1)),
    CONSTRAINT ck_change_linkage_enabled CHECK (enabled IN (0, 1)),
    -- A detector-less row is only legal when it is a registered exemption.
    CONSTRAINT ck_change_linkage_detector_required CHECK (
        exempt = 1 OR (detector_ref IS NOT NULL AND btrim(detector_ref) <> '')),
    CONSTRAINT ck_change_linkage_exempt_reason CHECK (
        exempt = 0 OR (exempt_reason IS NOT NULL AND btrim(exempt_reason) <> ''))
);

COMMENT ON TABLE tp_change_linkage IS 'Registry of upstream->downstream change-propagation edges; one row per edge, read by the linkage bus to decide what a save must re-check downstream';
COMMENT ON COLUMN tp_change_linkage.edge_key IS 'Stable identity of the edge (upstream->downstream pair), used by detectors, audit lines and the orphan-edge gate';
COMMENT ON COLUMN tp_change_linkage.upstream_object_type IS 'Object type whose save announces the change on the event bus (same token as the routing-key prefix, e.g. case, requirement, api_spec)';
COMMENT ON COLUMN tp_change_linkage.downstream_object_type IS 'Object type that may be invalidated by that save';
COMMENT ON COLUMN tp_change_linkage.detector_kind IS 'How the downstream impact is found: 1=spring bean detector, 2=sql predicate stored in detector_ref, 3=no detector (registered exemption)';
COMMENT ON COLUMN tp_change_linkage.detector_ref IS 'Detector bean name or SQL predicate text; mandatory unless the row is an exemption';
COMMENT ON COLUMN tp_change_linkage.trigger_field_predicate IS 'Which upstream fields actually arm this edge (field list or predicate text); NULL=any field change arms it';
COMMENT ON COLUMN tp_change_linkage.disposition_strategy IS 'Outcome shape when the edge fires: mark_only=reversible mark/display state applied automatically, grouped_draft=AI pre-disposition card needing the one-click accept, disclose_only=report without touching rows, notify=reminder/escalation, exempt=no propagation by design';
COMMENT ON COLUMN tp_change_linkage.risk_level IS '1=reversible mark or display state (zero-click allowed), 2=downstream business content change (grouped card + human confirm), 3=immutable or already-issued record (disclosure only)';
COMMENT ON COLUMN tp_change_linkage.exempt IS '1=this upstream->downstream relation deliberately does not propagate; read by the orphan-edge gate so the relation counts as covered instead of red';
COMMENT ON COLUMN tp_change_linkage.exempt_reason IS 'Written reason for the exemption, reviewed as part of the edge ledger';
COMMENT ON COLUMN tp_change_linkage.enabled IS '0=kept for audit but not consumed by the bus; 1=active edge';
COMMENT ON COLUMN tp_change_linkage.edge_note IS 'Plain-language note of what this edge covers and why';

-- The bus looks up edges by upstream type on every save announcement; the
-- orphan-edge gate walks the whole table, so only the hot lookup needs an index.
CREATE INDEX IF NOT EXISTS idx_change_linkage_upstream
    ON tp_change_linkage (upstream_object_type) WHERE enabled = 1;
