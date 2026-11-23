
CREATE TABLE IF NOT EXISTS tp_monitor_target (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id            BIGINT        NOT NULL REFERENCES tp_project (id),
    env_id                BIGINT        NOT NULL REFERENCES tp_project_env (id),
    name                  VARCHAR(100)  NOT NULL,
    target_type           VARCHAR(10)   NOT NULL,
    probe_config          JSONB         NOT NULL DEFAULT '{}'::jsonb,
    check_interval_seconds INT          NOT NULL DEFAULT 60,
    enabled               BOOLEAN       NOT NULL DEFAULT TRUE,
    silence_until         TIMESTAMPTZ,
    silence_reason        VARCHAR(200),
    last_state            VARCHAR(12)   NOT NULL DEFAULT 'UNKNOWN',
    last_check_at         TIMESTAMPTZ,
    last_cause            VARCHAR(300),
    next_check_at         TIMESTAMPTZ,
    backoff_level         SMALLINT      NOT NULL DEFAULT 0,
    stable_cycles         INT           NOT NULL DEFAULT 0,
    consecutive_failures  INT           NOT NULL DEFAULT 0,
    consecutive_successes INT           NOT NULL DEFAULT 0,
    env_version           INT           NOT NULL DEFAULT 0,
    created_by            BIGINT,
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_monitor_target_project_name UNIQUE (project_id, name),
    CONSTRAINT ck_monitor_target_type CHECK (target_type IN ('HTTP', 'LOGIN', 'ONLINE', 'DB', 'CERT')),
    CONSTRAINT ck_monitor_target_state CHECK (last_state IN ('UNKNOWN', 'UP', 'DOWN', 'RECOVERING', 'FLAPPING'))
);

CREATE INDEX IF NOT EXISTS ix_monitor_target_due
    ON tp_monitor_target (enabled, next_check_at);

COMMENT ON TABLE tp_monitor_target IS '监控探测目标（监控中心：五类业务探针，环境档案引用继承 TLS 配置）';
COMMENT ON COLUMN tp_monitor_target.probe_config IS '探针参数 JSONB（HTTP=url+断言组 / LOGIN=凭据引用禁明文 / DB=连接引用 / ONLINE=关键交易窗口定义）';
COMMENT ON COLUMN tp_monitor_target.backoff_level IS '探测间隔退避档位：0=基准档（monitor.min-interval-seconds），任一失败/配置变更/环境档案 version 变化复位 0';
COMMENT ON COLUMN tp_monitor_target.silence_until IS '静默窗口到期时间（发布窗口期一键静默，进审计带理由，到期自动恢复）';

CREATE TABLE IF NOT EXISTS tp_monitor_check_log (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    target_id   BIGINT       NOT NULL REFERENCES tp_monitor_target (id) ON DELETE CASCADE,
    checked_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    outcome     VARCHAR(8)   NOT NULL,
    latency_ms  INT,
    detail      VARCHAR(500)
);

CREATE INDEX IF NOT EXISTS ix_monitor_check_log_target_time
    ON tp_monitor_check_log (target_id, checked_at DESC);

COMMENT ON TABLE tp_monitor_check_log IS '监控逐轮探测留痕（趋势=探测自身耗时，非指标采集；保留策略挂 retention 配置域）';
COMMENT ON COLUMN tp_monitor_check_log.outcome IS '本轮结局：ok/fail/deferred（deferred=静默窗口或探针未接线，不计失败）';
COMMENT ON COLUMN tp_monitor_check_log.detail IS '失败现象摘要（脱敏，含账号/IP 打码，禁明文凭证）';

CREATE TABLE IF NOT EXISTS tp_monitor_episode (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    target_id        BIGINT       NOT NULL REFERENCES tp_monitor_target (id) ON DELETE CASCADE,
    started_at       TIMESTAMPTZ  NOT NULL,
    recovered_at     TIMESTAMPTZ,
    failure_count    INT          NOT NULL DEFAULT 0,
    notice_count     INT          NOT NULL DEFAULT 0,
    last_notified_at TIMESTAMPTZ,
    cause_summary    VARCHAR(300),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_monitor_episode_target_time
    ON tp_monitor_episode (target_id, started_at DESC);
CREATE INDEX IF NOT EXISTS ix_monitor_episode_open
    ON tp_monitor_episode (recovered_at) WHERE recovered_at IS NULL;

COMMENT ON TABLE tp_monitor_episode IS '监控告警事件（DOWN 持续期合并 1 条；归因联动=失败时间窗与 episode 交集查此表）';
COMMENT ON COLUMN tp_monitor_episode.recovered_at IS 'NULL=进行中 episode；批次归因按 [started_at, coalesce(recovered_at, now())] 与失败窗取交集';
