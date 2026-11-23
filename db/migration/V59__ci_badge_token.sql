ALTER TABLE tp_exec_batch
    ADD COLUMN badge_token uuid NOT NULL DEFAULT gen_random_uuid();

CREATE UNIQUE INDEX IF NOT EXISTS uk_tp_exec_batch_badge_token
    ON tp_exec_batch (badge_token);
