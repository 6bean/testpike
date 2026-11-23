
CREATE TABLE IF NOT EXISTS tp_appauto_device (
    id                   BIGSERIAL    PRIMARY KEY,
    project_id           BIGINT       NOT NULL REFERENCES tp_project (id),
    name                 VARCHAR(200) NOT NULL,
    serial               VARCHAR(128) NOT NULL,
    device_type          VARCHAR(20)  NOT NULL,
    adb_address          VARCHAR(160),
    model                VARCHAR(100),
    os_version           VARCHAR(50),
    resolution           VARCHAR(50),
    dpi                  INT,
    labels               JSONB        NOT NULL DEFAULT '[]'::jsonb,
    health_state         VARCHAR(16)  NOT NULL DEFAULT 'UNKNOWN',
    consecutive_failures INT          NOT NULL DEFAULT 0,
    last_seen_at         TIMESTAMP,
    created_at           TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at           TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT ck_appauto_device_type CHECK (device_type IN ('EMULATOR', 'ADB', 'USB')),
    CONSTRAINT ck_appauto_device_health CHECK (health_state IN ('GREEN', 'YELLOW', 'RED', 'QUARANTINED', 'UNKNOWN')),
    CONSTRAINT ck_appauto_device_dpi CHECK (dpi IS NULL OR dpi > 0),
    CONSTRAINT uq_appauto_device_serial UNIQUE (project_id, serial)
);

CREATE INDEX IF NOT EXISTS ix_appauto_device_project ON tp_appauto_device (project_id, health_state);
CREATE INDEX IF NOT EXISTS ix_appauto_device_labels ON tp_appauto_device USING GIN (labels);

COMMENT ON TABLE tp_appauto_device IS 'APP 设备档案（注册三态：本地模拟器/远程 ADB ip:port/USB 直连）';
COMMENT ON COLUMN tp_appauto_device.adb_address IS '远程 ADB 接入地址 ip:port（device_type=ADB 时探活用，USB/EMULATOR 可空）';
COMMENT ON COLUMN tp_appauto_device.labels IS '设备组 label 数组（计划/套件绑组不绑单机，组内换机重跑=失败重试语义）';
COMMENT ON COLUMN tp_appauto_device.health_state IS '健康态：GREEN/YELLOW/RED/QUARANTINED（连续失联 N 次自动隔离，N3 同款）/UNKNOWN';

CREATE TABLE IF NOT EXISTS tp_appauto_app (
    id                    BIGSERIAL    PRIMARY KEY,
    project_id            BIGINT       NOT NULL REFERENCES tp_project (id),
    name                  VARCHAR(200) NOT NULL,
    package_name          VARCHAR(200) NOT NULL,
    version               VARCHAR(50),
    apk_artifact_key      VARCHAR(500),
    permission_rule_ref   VARCHAR(200),
    created_at            TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at            TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT uq_appauto_app_pkg UNIQUE (project_id, package_name, version)
);

CREATE INDEX IF NOT EXISTS ix_appauto_app_project ON tp_appauto_app (project_id);

COMMENT ON TABLE tp_appauto_app IS '被测应用档案（包名/版本/APK MinIO 工件引用/权限规则集引用）';
COMMENT ON COLUMN tp_appauto_app.apk_artifact_key IS 'APK 工件 MinIO object key（launch 动作 install/覆盖安装/清数据的工件源）';

CREATE TABLE IF NOT EXISTS tp_appauto_element (
    id                    BIGSERIAL    PRIMARY KEY,
    project_id            BIGINT       NOT NULL REFERENCES tp_project (id),
    name                  VARCHAR(200) NOT NULL,
    locator_type          VARCHAR(16)  NOT NULL,
    locator_config        JSONB        NOT NULL,
    variants              JSONB        NOT NULL DEFAULT '[]'::jsonb,
    confidence_threshold  DOUBLE PRECISION NOT NULL DEFAULT 0.80,
    created_at            TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at            TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT ck_appauto_element_type CHECK (locator_type IN ('IMAGE', 'COORD', 'REGION', 'OCR')),
    CONSTRAINT ck_appauto_element_confidence CHECK (confidence_threshold >= 0 AND confidence_threshold <= 1),
    CONSTRAINT uq_appauto_element_name UNIQUE (project_id, name)
);

CREATE INDEX IF NOT EXISTS ix_appauto_element_project ON tp_appauto_element (project_id, locator_type);

COMMENT ON TABLE tp_appauto_element IS '图像元素库四态（图片模板/坐标/区域/OCR 文本；三路定位=模板+OCR+可选视觉 AI）';
COMMENT ON COLUMN tp_appauto_element.variants IS '多分辨率变体数组（[{resolution,artifact_key,ref_w,ref_h}]，命中率优先选同分辨率变体）';

CREATE TABLE IF NOT EXISTS tp_appauto_lease_run (
    id                 BIGSERIAL   PRIMARY KEY,
    device_id          BIGINT      NOT NULL REFERENCES tp_appauto_device (id),
    project_id         BIGINT      NOT NULL REFERENCES tp_project (id),
    batch_id           BIGINT,
    holder_executor_id VARCHAR(64) NOT NULL,
    state              VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    acquired_at        TIMESTAMP   NOT NULL DEFAULT now(),
    expires_at         TIMESTAMP   NOT NULL,
    released_at        TIMESTAMP,
    release_reason     VARCHAR(200),
    CONSTRAINT ck_appauto_lease_run_state CHECK (state IN ('ACTIVE', 'RELEASED', 'EXPIRED'))
);

CREATE INDEX IF NOT EXISTS ix_appauto_lease_run_device ON tp_appauto_lease_run (device_id, id DESC);
CREATE INDEX IF NOT EXISTS ix_appauto_lease_run_batch ON tp_appauto_lease_run (batch_id) WHERE batch_id IS NOT NULL;

COMMENT ON TABLE tp_appauto_lease_run IS '设备占用流水（排队显示/换机事件流展示面；活跃互斥权威=tp_resource_lease kind=device，本表禁作第二把锁）';
COMMENT ON COLUMN tp_appauto_lease_run.release_reason IS '归因可读（完成/超时回收/人工释放/换机），排队明细与审计展示用';

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES
    ('appauto.device-lease-ttl-seconds', '1800', 'APP自动化', 'int', 0, '设备租约档 TTL 秒（T276a；缺键/非法回退 lease.lease-ttl-seconds 全局键）')
ON CONFLICT (config_key) DO NOTHING;
