-- Read-only dialog snapshot shares .
--
-- Why this migration exists:
--   A user wants to hand a conversation to a colleague who has no account and
--   no business in the source session. The rule from the design is that the
--   link carries a frozen snapshot: the permission-domain pruning and the
--   masking chain run ONCE at share-creation time and their output is stored
--   here, so later changes to the source session (new turns, renames, even
--   permission changes) never retroactively alter what a holder of the link
--   can read, and an expired link self-destructs the row on next touch.
--   Share token shape follows the CI badge precedent (V59): a server-generated
--   non-enumerable UUID, never the numeric session id, so the public URL cannot
--   be walked. revoked=1 kills a live link before its expiry (the owner changed
--   their mind); expiry sweep is lazy (read path deletes) - shares are small
--   rows and a scheduler would be a second lifecycle source.
--   Idempotent: guarded via IF NOT EXISTS so a re-run is a no-op.
--   Note: Flyway is not enabled - apply by hand (DBA channel), same as the
--   rest of db/migration.
-- Usage: docker exec -i -e PGPASSWORD="$DB_PASSWORD" testpike-postgres psql -U "$DB_USERNAME" -d testpike -f - < this file

CREATE TABLE IF NOT EXISTS tp_ai_chat_share (
    id                BIGSERIAL    PRIMARY KEY,
    share_token       UUID         NOT NULL DEFAULT gen_random_uuid(),
    session_id        BIGINT       NOT NULL,
    owner_user_id     BIGINT       NOT NULL,
    title             VARCHAR(200) NOT NULL,
    snapshot_markdown TEXT         NOT NULL,
    turn_count        INT          NOT NULL DEFAULT 0,
    expires_at        TIMESTAMP    NOT NULL,
    revoked           SMALLINT     NOT NULL DEFAULT 0,
    created_at        TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT fk_ai_chat_share_session
        FOREIGN KEY (session_id) REFERENCES tp_ai_chat_session (id) ON DELETE CASCADE,
    CONSTRAINT ck_ai_chat_share_revoked CHECK (revoked IN (0, 1)),
    CONSTRAINT ck_ai_chat_share_turn_count CHECK (turn_count >= 0)
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_tp_ai_chat_share_token
    ON tp_ai_chat_share (share_token);

CREATE INDEX IF NOT EXISTS ix_tp_ai_chat_share_expiry
    ON tp_ai_chat_share (expires_at);
