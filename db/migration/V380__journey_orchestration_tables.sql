-- Migration V380: cross-project journey orchestration tables .
-- A journey is a new orchestration entity living under a program (project set): an ordered
-- list of segments where each segment belongs to exactly one member project and targets
-- either a published shared-asset version row (pinned through tp_asset_reference, host kind
-- 'journey_segment') or a case inside that project. Execution expands one run into per-segment
-- batches owned by the segment's own project — business tables keep their project_id scoping
-- (H1 isolation intact); tp_program stays a lens holding no business data, so only these new
-- journey tables reference program_id, never the existing project-scoped ones (Y-2g).
-- Sign-off authority: tp_journey_run.signed_by records exactly one approval, and the service
-- re-checks that the signer is the journey's owner_user_id (unique owner = sole sign-off right).
-- Run/segment status literals are small-int enums documented per column below; the run-segment
-- rows carry the traffic-light data for the timeline page (failure_code/message name the
-- failing segment and link straight to its batch detail).
-- Flyway not enabled: apply manually to each chain database via the replay ledger.

CREATE TABLE IF NOT EXISTS tp_journey (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    program_id     BIGINT       NOT NULL REFERENCES tp_program (id),
    name           VARCHAR(200) NOT NULL,
    owner_user_id  BIGINT       NOT NULL,
    status         SMALLINT     NOT NULL DEFAULT 1,
    created_by     BIGINT,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_journey_status CHECK (status IN (1, 2)),
    CONSTRAINT uq_journey_program_name UNIQUE (program_id, name)
);
CREATE INDEX IF NOT EXISTS idx_journey_program ON tp_journey (program_id, status);

CREATE TABLE IF NOT EXISTS tp_journey_segment (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    journey_id          BIGINT       NOT NULL REFERENCES tp_journey (id),
    seq_no              INT          NOT NULL,
    project_id          BIGINT       NOT NULL REFERENCES tp_project (id),
    target_kind         VARCHAR(16)  NOT NULL,
    asset_row_id        BIGINT,
    case_id             BIGINT,
    params_override_json TEXT,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_segment_target_kind CHECK (target_kind IN ('asset', 'case')),
    CONSTRAINT ck_segment_target_present CHECK (
        (target_kind = 'asset' AND asset_row_id IS NOT NULL AND case_id IS NULL)
        OR (target_kind = 'case' AND case_id IS NOT NULL AND asset_row_id IS NULL)),
    CONSTRAINT uq_segment_journey_seq UNIQUE (journey_id, seq_no)
);
CREATE INDEX IF NOT EXISTS idx_segment_journey ON tp_journey_segment (journey_id, seq_no);
CREATE INDEX IF NOT EXISTS idx_segment_project ON tp_journey_segment (project_id);

CREATE TABLE IF NOT EXISTS tp_journey_run (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    journey_id   BIGINT      NOT NULL REFERENCES tp_journey (id),
    status       SMALLINT    NOT NULL DEFAULT 1,
    signed_by    BIGINT,
    signed_at    TIMESTAMPTZ,
    started_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at  TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_run_status CHECK (status IN (1, 2, 3, 4))
);
CREATE INDEX IF NOT EXISTS idx_run_journey ON tp_journey_run (journey_id, started_at DESC);

CREATE TABLE IF NOT EXISTS tp_journey_run_segment (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    run_id           BIGINT      NOT NULL REFERENCES tp_journey_run (id),
    segment_id       BIGINT      NOT NULL REFERENCES tp_journey_segment (id),
    project_id       BIGINT      NOT NULL REFERENCES tp_project (id),
    seq_no           INT         NOT NULL,
    batch_id         BIGINT,
    status           SMALLINT    NOT NULL DEFAULT 0,
    failure_code     VARCHAR(64),
    failure_message  TEXT,
    started_at       TIMESTAMPTZ,
    finished_at      TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_run_segment_status CHECK (status IN (0, 1, 2, 3, 4)),
    CONSTRAINT uq_run_segment UNIQUE (run_id, segment_id)
);
CREATE INDEX IF NOT EXISTS idx_run_segment_run ON tp_journey_run_segment (run_id, seq_no);

COMMENT ON TABLE  tp_journey IS 'Cross-project journey plan under one program: ordered segments, each owned by a member project (T289)';
COMMENT ON COLUMN tp_journey.program_id IS 'Program (project set) the journey belongs to; only journey tables carry program_id — business tables keep H1 project scoping';
COMMENT ON COLUMN tp_journey.owner_user_id IS 'Journey owner: the single account holding exit sign-off authority over runs of this journey';
COMMENT ON COLUMN tp_journey.status IS '1 = active, 2 = archived';
COMMENT ON TABLE  tp_journey_segment IS 'One ordered segment of a journey: target = published asset version row (pin registered on tp_asset_reference as journey_segment) or a case';
COMMENT ON COLUMN tp_journey_segment.seq_no IS 'Execution order inside the journey (unique per journey); the form-style segment table edits this directly';
COMMENT ON COLUMN tp_journey_segment.project_id IS 'Owning member project of the segment; batches expanded from it belong to this project, never to the program';
COMMENT ON COLUMN tp_journey_segment.asset_row_id IS 'Pinned tp_asset version row id when target_kind = asset; concrete row never auto-follows a re-publish (staleness surfaced, re-pin explicit)';
COMMENT ON COLUMN tp_journey_segment.params_override_json IS 'Segment-level parameter overrides merged into the expanded batch; NULL = inherit project defaults';
COMMENT ON TABLE  tp_journey_run IS 'One execution of a journey; sign-off (exit approval) is recorded here by exactly the journey owner';
COMMENT ON COLUMN tp_journey_run.status IS '1 = running, 2 = all segments succeeded, 3 = any segment failed, 4 = cancelled';
COMMENT ON COLUMN tp_journey_run.signed_by IS 'User id of the approving journey owner (NULL until sign-off); service re-checks signer = tp_journey.owner_user_id';
COMMENT ON TABLE  tp_journey_run_segment IS 'Per-segment expansion of a run: the batch this segment started inside its own project plus the traffic-light state';
COMMENT ON COLUMN tp_journey_run_segment.batch_id IS 'Expanded batch id inside the segment project; NULL while pending or when expansion failed before batch creation';
COMMENT ON COLUMN tp_journey_run_segment.status IS '0 = pending, 1 = running, 2 = succeeded, 3 = failed, 4 = skipped (upstream segment failed)';
COMMENT ON COLUMN tp_journey_run_segment.failure_message IS 'Human-readable failure naming for the timeline page; failure_code keeps the machine category';
