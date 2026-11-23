
CREATE TABLE IF NOT EXISTS tp_appauto_takeover_request (
    id              BIGSERIAL    PRIMARY KEY,
    device_id       BIGINT       NOT NULL REFERENCES tp_appauto_device (id),
    project_id      BIGINT       NOT NULL REFERENCES tp_project (id),
    task_id         BIGINT,
    requester_id    BIGINT       NOT NULL,
    occupier_id     BIGINT       NOT NULL,
    state           VARCHAR(16)  NOT NULL DEFAULT 'pending',
    reason          VARCHAR(200),
    decided_by      BIGINT,
    decided_at      TIMESTAMP,
    expires_at      TIMESTAMP    NOT NULL,
    recording_ref   VARCHAR(200),
    created_at      TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT ck_appauto_takeover_state CHECK (state IN
        ('pending', 'agreed', 'rejected', 'forced', 'active', 'ended', 'expired'))
);

CREATE INDEX IF NOT EXISTS ix_appauto_takeover_device ON tp_appauto_takeover_request (device_id, id DESC);
CREATE INDEX IF NOT EXISTS ix_appauto_takeover_requester ON tp_appauto_takeover_request (requester_id);

COMMENT ON TABLE tp_appauto_takeover_request IS '设备接管申请（占用者同意或 ADMIN 强接，接管≠租约转移；留痕切片位写）';

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
('appauto.takeover.request-ttl-seconds', '300', 'APP自动化', 'int', 0, '设备接管申请待批有效窗秒（T332；pending 超时=expired 拒批，热更即生效，缺失/非法回退默认 300）')
ON CONFLICT (config_key) DO NOTHING;
