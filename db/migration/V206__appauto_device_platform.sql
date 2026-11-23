
ALTER TABLE tp_appauto_device
    ADD COLUMN IF NOT EXISTS platform VARCHAR(64);

COMMENT ON COLUMN tp_appauto_device.platform IS '平台描述符引用（tp_appauto_platform.code 启用面，大小写归一；NULL=未判定诚实位——接管置灰判据/注入载体按此判定）';
