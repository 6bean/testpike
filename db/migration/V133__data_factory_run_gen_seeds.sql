ALTER TABLE tp_data_factory_run ADD COLUMN IF NOT EXISTS gen_seeds JSONB;

COMMENT ON COLUMN tp_data_factory_run.gen_seeds IS 'gen 模板随机族调用留痕 [{fn,seed}]（同 seed 可复现重放，零实值）';
