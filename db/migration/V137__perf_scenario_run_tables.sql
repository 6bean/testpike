
CREATE TABLE IF NOT EXISTS tp_perf_scenario (
    id               BIGSERIAL    PRIMARY KEY,
    project_id       BIGINT       NOT NULL REFERENCES tp_project (id),
    env_id           BIGINT       NOT NULL REFERENCES tp_project_env (id),
    name             VARCHAR(200) NOT NULL,
    pressure_model   VARCHAR(20)  NOT NULL,
    pressure_config  JSONB        NOT NULL,
    login_strategy   VARCHAR(20)  NOT NULL DEFAULT 'PER_VUSER',
    driver_limit     INT,
    steps_json       JSONB        NOT NULL,
    created_at       TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at       TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT ck_perf_scenario_model CHECK (pressure_model IN ('CONSTANT_RPS', 'RAMP', 'SPIKE')),
    CONSTRAINT ck_perf_scenario_driver_limit CHECK (driver_limit IS NULL OR driver_limit > 0),
    CONSTRAINT uq_perf_scenario_name UNIQUE (project_id, name)
);

CREATE INDEX IF NOT EXISTS ix_perf_scenario_project ON tp_perf_scenario (project_id, env_id);

COMMENT ON TABLE tp_perf_scenario IS '压测场景 DSL（三压力形态：恒定 RPS/阶梯爬坡/峰值脉冲；仅接口类动作，D2 闸保存期判定）';
COMMENT ON COLUMN tp_perf_scenario.login_strategy IS 'D4 登录策略：PER_VUSER（默认）/SHARED/PER_SESSION_N';
COMMENT ON COLUMN tp_perf_scenario.driver_limit IS 'D3 驱动上限披露值（报告分界标注用，非执行参数）';

CREATE TABLE IF NOT EXISTS tp_perf_run (
    id                 BIGSERIAL   PRIMARY KEY,
    scenario_id        BIGINT      NOT NULL REFERENCES tp_perf_scenario (id),
    project_id         BIGINT      NOT NULL REFERENCES tp_project (id),
    status             SMALLINT    NOT NULL DEFAULT 1,
    pressure_snapshot  JSONB       NOT NULL,
    stop_reason        VARCHAR(500),
    operator_id        BIGINT      REFERENCES tp_user (id),
    started_at         TIMESTAMP   NOT NULL DEFAULT now(),
    finished_at        TIMESTAMP,
    created_at         TIMESTAMP   NOT NULL DEFAULT now(),
    CONSTRAINT ck_perf_run_status CHECK (status IN (1, 2, 3, 4))
);

CREATE INDEX IF NOT EXISTS ix_perf_run_scenario ON tp_perf_run (scenario_id, id DESC);

COMMENT ON TABLE tp_perf_run IS '压测运行（启动钉版=场景 DSL 快照入 pressure_snapshot，批内不反查源表；1 运行中/2 正常结束/3 熔断停/4 人工停）';

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES
    ('lease.perf-ttl-seconds', '1800', '系统', 'int', 0, 'perf 档执行器租约 TTL 秒（N-c 定版 30 分钟，K8 可配热更）')
ON CONFLICT (config_key) DO NOTHING;
