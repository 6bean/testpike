
CREATE TABLE IF NOT EXISTS tp_resource_lease (
    id                 bigserial PRIMARY KEY,
    kind               varchar(16) NOT NULL,
    resource_ref       varchar(128) NOT NULL,
    project_id         bigint,
    env_id             bigint,
    batch_id           bigint,
    holder_executor_id varchar(64) NOT NULL,
    state              varchar(16) NOT NULL DEFAULT 'active',
    acquired_at        timestamp NOT NULL DEFAULT now(),
    expires_at         timestamp NOT NULL,
    renew_count        integer NOT NULL DEFAULT 0,
    released_at        timestamp
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_resource_lease_active
    ON tp_resource_lease (kind, resource_ref)
    WHERE state = 'active';

CREATE INDEX IF NOT EXISTS idx_resource_lease_expiry
    ON tp_resource_lease (kind, expires_at)
    WHERE state = 'active';

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
('lease.lease-ttl-seconds', '1800', '系统', 'int', 0, '资源租约统一租期秒（会话工件租约过期权威；缺键回退默认恒等）')
ON CONFLICT (config_key) DO NOTHING;
