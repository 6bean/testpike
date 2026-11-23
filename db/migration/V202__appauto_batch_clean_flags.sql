
ALTER TABLE tp_exec_batch ADD COLUMN IF NOT EXISTS clean_app_data smallint NOT NULL DEFAULT 0;
ALTER TABLE tp_batch_template ADD COLUMN IF NOT EXISTS clean_app_data smallint NOT NULL DEFAULT 0;
ALTER TABLE tp_test_case ADD COLUMN IF NOT EXISTS clean_app_data smallint NOT NULL DEFAULT 0;

COMMENT ON COLUMN tp_exec_batch.clean_app_data IS '跑批自动清机旗标（0关默认/1开）：批次后对占用设备清数据+卸载恢复初始态，best-effort 失败留痕不红批';
COMMENT ON COLUMN tp_batch_template.clean_app_data IS '模板清机默认位：按模板建批时落批行（默认值非覆盖强制）';
COMMENT ON COLUMN tp_test_case.clean_app_data IS '用例级清机旗标（0关默认/1开）：批级关时仍纳入该用例脚本显式声明包的清机集';
