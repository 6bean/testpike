
CREATE TABLE IF NOT EXISTS tp_perf_whitelist (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id       BIGINT       NOT NULL REFERENCES tp_project (id),
    env_id           BIGINT       NOT NULL REFERENCES tp_project_env (id),
    max_concurrency  INT          NOT NULL,
    allowed_modes    JSONB        NOT NULL DEFAULT '[]'::jsonb,
    created_by       BIGINT,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_perf_whitelist_env UNIQUE (project_id, env_id),
    CONSTRAINT ck_perf_whitelist_concurrency CHECK (max_concurrency > 0)
);

COMMENT ON TABLE tp_perf_whitelist IS '压测目标白名单（环境档案×压力档位双维；allowed_modes=获批隔离模式集 SHADOW_TABLE/NS_ACCOUNT/HEADER_MARK）';
COMMENT ON COLUMN tp_perf_whitelist.max_concurrency IS '该环境备案过的压力档位上限（RPS 口径；压测并发 > 上限=闸拒）';

CREATE TABLE IF NOT EXISTS tp_perf_approval (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id            BIGINT       NOT NULL REFERENCES tp_project (id),
    env_id                BIGINT       NOT NULL REFERENCES tp_project_env (id),
    requested_concurrency INT          NOT NULL,
    isolation_mode        VARCHAR(20)  NOT NULL,
    reason                VARCHAR(500) NOT NULL,
    reject_reason         VARCHAR(500),
    applicant_id          BIGINT       NOT NULL REFERENCES tp_user (id),
    status                SMALLINT     NOT NULL DEFAULT 2,
    approver_id           BIGINT       REFERENCES tp_user (id),
    handled_at            TIMESTAMP,
    created_at            TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at            TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT ck_perf_approval_status CHECK (status IN (2, 3, 4)),
    CONSTRAINT ck_perf_approval_mode CHECK (isolation_mode IN ('SHADOW_TABLE', 'NS_ACCOUNT', 'HEADER_MARK')),
    CONSTRAINT ck_perf_approval_concurrency CHECK (requested_concurrency > 0)
);

CREATE INDEX IF NOT EXISTS ix_perf_approval_lookup
    ON tp_perf_approval (project_id, env_id, status, requested_concurrency DESC);

COMMENT ON TABLE tp_perf_approval IS '生产压测审批单（A4=乙：压生产唯一通道；三层门=全局开关+白名单+本单）';
COMMENT ON COLUMN tp_perf_approval.handled_at IS '审批生效时刻（有效期 perf.approval-valid-hours 起点）';

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES
    ('perf.production-enabled', 'false', '系统', 'bool', 0, '生产压测全局开关（三层门第一层，默认关）'),
    ('perf.blocked-target-segments', '', '系统', 'string', 0, '压测目标禁入段字典（逗号分隔 host 段，命中硬拒不可越）'),
    ('perf.approval-valid-hours', '72', '系统', 'int', 0, '生产压测审批有效期（小时，<=0=不过期）')
ON CONFLICT (config_key) DO NOTHING;
