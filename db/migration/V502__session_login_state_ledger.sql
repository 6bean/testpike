-- Migration: session login-state ledger foundation .
-- Gives the credential vault honest lifecycle columns and creates the
-- login-state ledger table that references it.
-- tp_credential gains three nullable columns (source_kind / expires_at /
-- last_verified_at). Every existing row keeps NULL = legacy manual-registration
-- shape, so all current read paths are untouched (zero-drift guarantee booked
-- in the acceptance row; the source_kind=1 backfill is a batch-tail
-- ledger action, not this migration).
-- Scope note (D11 ruling): the origin scope lives ONLY on tp_login_state
-- (scope_origins jsonb list). tp_credential deliberately gets no scope column
-- — keeping the same fact in two tables is a drift source.
-- tp_login_state = one row per captured/renewed login state of one account:
-- unique (project_id, state_name); re-capture under the same name updates the
-- row (a login state is consumable — renewal is an update, never an append);
-- different accounts = different state_name rows. Ciphertext stays single-sourced
-- in tp_credential.secret_cipher via credential_id (no second vault).
-- Usage: docker exec -i testpike-postgres psql -U testpike -d testpike -f - < db/migration/V502__session_login_state_ledger.sql
ALTER TABLE tp_credential ADD COLUMN IF NOT EXISTS source_kind SMALLINT;
ALTER TABLE tp_credential ADD COLUMN IF NOT EXISTS expires_at TIMESTAMP;
ALTER TABLE tp_credential ADD COLUMN IF NOT EXISTS last_verified_at TIMESTAMP;

COMMENT ON COLUMN tp_credential.source_kind IS 'Credential provenance: 1=manually registered 2=produced by a login chain 3=captured from a browser session; NULL=legacy row (shape before this column existed)';
COMMENT ON COLUMN tp_credential.expires_at IS 'Absolute expiry when it could be parsed (JWT exp claim / cookie Expires); NULL=expiry unknown, the state light shows "validity unknown" instead of guessing';
COMMENT ON COLUMN tp_credential.last_verified_at IS 'Last server-side verification outcome timestamp; consumption hits 401/403 mark the row invalid (AV3 trigger three)';

CREATE TABLE IF NOT EXISTS tp_login_state (
    id               BIGSERIAL    PRIMARY KEY,
    project_id       BIGINT       NOT NULL REFERENCES tp_project (id),
    state_name       VARCHAR(200) NOT NULL,
    role_scope       VARCHAR(64),
    account_label    VARCHAR(200),
    kind             VARCHAR(32)  NOT NULL,
    credential_id    BIGINT       REFERENCES tp_credential (id),
    state_object_key VARCHAR(500),
    scope_origins    JSONB        NOT NULL DEFAULT '[]'::jsonb,
    source_kind      SMALLINT     NOT NULL DEFAULT 3,
    expires_at       TIMESTAMP,
    last_verified_at TIMESTAMP,
    status           SMALLINT     NOT NULL DEFAULT 1,
    remark           VARCHAR(500),
    created_at       TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at       TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT uq_login_state_name UNIQUE (project_id, state_name),
    CONSTRAINT ck_login_state_kind CHECK (kind IN ('token', 'storage_state', 'cookie_set'))
);

COMMENT ON TABLE tp_login_state IS 'Login-state ledger (AV2): one row per account login state captured or produced for a project; secret material stays in tp_credential (single vault), snapshots ride MinIO state_object_key scoped to the project';
COMMENT ON COLUMN tp_login_state.state_name IS 'Human-readable state name, unique per project; re-capture under the same name updates the row, new accounts get new names';
COMMENT ON COLUMN tp_login_state.role_scope IS 'Test-role dimension (role-level sharing is the default granularity, D1 ruling); resolution by role with multiple un-disambiguated rows fails closed and lists candidates';
COMMENT ON COLUMN tp_login_state.account_label IS 'Account label (e.g. "approver A") — disambiguation slot when one role has several accounts';
COMMENT ON COLUMN tp_login_state.kind IS 'State shape: token=bearer value, storage_state=full Playwright storageState snapshot, cookie_set=cookie pairs; SSO redirect chains default-recommend storage_state (keeps the silent re-login chain, D11)';
COMMENT ON COLUMN tp_login_state.credential_id IS 'FK into tp_credential — the ciphertext single source (no second vault, D3 ruling)';
COMMENT ON COLUMN tp_login_state.state_object_key IS 'MinIO object key of the storageState snapshot (project-level scope; NULL for non-snapshot kinds)';
COMMENT ON COLUMN tp_login_state.scope_origins IS 'Origin whitelist as a jsonb list (D11: SSO chains spread state over target-system + IdP domains; reading outside the list is refused fail-closed)';
COMMENT ON COLUMN tp_login_state.source_kind IS 'Provenance mirror of tp_credential.source_kind: 2=login chain output 3=browser capture (ledger rows are never manual)';
COMMENT ON COLUMN tp_login_state.status IS '1=enabled 0=disabled (managed via perm:session:manage; disabling takes effect from the next session, never cuts running sessions)';

CREATE INDEX IF NOT EXISTS ix_login_state_project
    ON tp_login_state (project_id, status);
CREATE INDEX IF NOT EXISTS ix_login_state_credential
    ON tp_login_state (credential_id);
