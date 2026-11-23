ALTER TABLE tp_appauto_app
    ADD COLUMN IF NOT EXISTS artifact_size_bytes BIGINT,
    ADD COLUMN IF NOT EXISTS artifact_sha256     VARCHAR(64),
    ADD COLUMN IF NOT EXISTS source              VARCHAR(32) NOT NULL DEFAULT 'MANUAL';

COMMENT ON COLUMN tp_appauto_app.artifact_size_bytes IS '包工件字节数（上传实测，档案列表展示与水位统计源）';
COMMENT ON COLUMN tp_appauto_app.artifact_sha256     IS '包工件 SHA-256 摘要（重传覆盖对账+执行侧下载完整性校验锚）';
COMMENT ON COLUMN tp_appauto_app.source              IS '包来源：MANUAL=页面上传（本波唯一入口）；CI=开放口预留位（二期，不建）';

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES ('appauto.pkg-max-size', '104857600', 'APP自动化', 'int', 0,
        '应用包上传尺寸上限字节数（T326；默认 104857600=100MB，热更即生效，超限上传拒绝并说明原因）')
ON CONFLICT (config_key) DO NOTHING;
