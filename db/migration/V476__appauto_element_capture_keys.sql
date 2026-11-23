-- Migration: seed the live-frame element capture tuning keys.
-- appauto.element.capture-default-scales = the scale list applied when the
-- operator crops a region out of a live monitor frame without choosing scales
-- explicitly (comma-separated decimals; 1.0 keeps the untouched crop).
-- appauto.element.capture-max-variants = how many scaled variants one capture
-- call may produce before the request is rejected with the reason named.
-- Both are hot keys read per capture through AppautoConfigReader (no hardcode
-- in the implementation face). Idempotent: ON CONFLICT DO NOTHING.
-- Flyway disabled: apply with manual psql.
-- Usage: psql < db/migration/V476__appauto_element_capture_keys.sql

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES
    ('appauto.element.capture-default-scales', '1.0,0.8,1.25', 'APP自动化', 'string', 0,
     '实时画面取图的默认倍率集（默认 1.0,0.8,1.25；取图时未选倍率即按此集各存一份，调整后即时生效）'),
    ('appauto.element.capture-max-variants', '6', 'APP自动化', 'int', 0,
     '单次实时画面取图可生成的倍率变体上限（默认 6；超出时取图会被拒绝并提示当前请求数量，调整即时生效）')
ON CONFLICT (config_key) DO NOTHING;
