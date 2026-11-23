
CREATE TABLE IF NOT EXISTS tp_appauto_reservation (
    id             BIGSERIAL   PRIMARY KEY,
    project_id     BIGINT      REFERENCES tp_project (id),
    device_id      BIGINT      NOT NULL REFERENCES tp_appauto_device (id),
    batch_id       BIGINT,
    holder_user_id BIGINT      NOT NULL REFERENCES tp_user (id),
    starts_at      TIMESTAMP   NOT NULL,
    ends_at        TIMESTAMP   NOT NULL,
    state          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    reason         VARCHAR(500),
    approved_by    BIGINT,
    approved_at    TIMESTAMP,
    released_at    TIMESTAMP,
    release_reason VARCHAR(200),
    created_at     TIMESTAMP   NOT NULL DEFAULT now(),
    updated_at     TIMESTAMP   NOT NULL DEFAULT now(),
    CONSTRAINT ck_appauto_reservation_state CHECK (state IN ('PENDING_APPROVAL', 'ACTIVE', 'RELEASED', 'EXPIRED', 'CANCELLED', 'REJECTED')),
    CONSTRAINT ck_appauto_reservation_window CHECK (ends_at > starts_at)
);

CREATE INDEX IF NOT EXISTS ix_appauto_reservation_device_state
    ON tp_appauto_reservation (device_id, state);
CREATE INDEX IF NOT EXISTS ix_appauto_reservation_project_start
    ON tp_appauto_reservation (project_id, starts_at);
