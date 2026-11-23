
ALTER TABLE tp_training_sample ADD COLUMN IF NOT EXISTS adopted SMALLINT NOT NULL DEFAULT 1;

COMMENT ON COLUMN tp_training_sample.adopted IS 'D5 措辞采纳留痕（1=已采纳默认 0=已撤销；撤销后不再注入生成约束段，可逆不删行——TC-D5-01）';

CREATE INDEX IF NOT EXISTS idx_training_sample_adopted_type
    ON tp_training_sample (sample_type, adopted, id DESC);
