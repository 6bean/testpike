
CREATE TABLE IF NOT EXISTS tp_cluster_command (
    id              BIGSERIAL    PRIMARY KEY,
    command_type    VARCHAR(30)  NOT NULL,
    target_type     VARCHAR(20)  NOT NULL,
    target_id       BIGINT       NOT NULL,
    payload         JSONB        NOT NULL DEFAULT '{}'::jsonb,
    status          SMALLINT     NOT NULL DEFAULT 0,
    claimed_by      BIGINT,
    claimed_at      TIMESTAMP,
    last_progress   JSONB,
    result          JSONB,
    result_detail   VARCHAR(2000),
    expires_at      TIMESTAMP    NOT NULL,
    operator        VARCHAR(50),
    created_at      TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT ck_cluster_command_type CHECK
        (command_type IN ('engine-install', 'engine-uninstall', 'scale-in', 'node-remove')),
    CONSTRAINT ck_cluster_command_target CHECK (target_type IN ('executor', 'enroll-token')),
    CONSTRAINT ck_cluster_command_status CHECK (status IN (0, 1, 2, 3, 4, 5))
);

CREATE INDEX IF NOT EXISTS idx_cluster_command_poll
    ON tp_cluster_command (target_type, target_id, status);

COMMENT ON TABLE tp_cluster_command IS '机群零命令运维队列（03 契约定义：装/卸引擎/缩容/移除，拉模型 CAS 领取）';
COMMENT ON COLUMN tp_cluster_command.command_type IS '命令型值域（CHECK 四枚，扩型必同步 03）';
COMMENT ON COLUMN tp_cluster_command.target_type IS 'executor=已入池机器直发；enroll-token=等待加入机器预装（绑定指纹=执行器注册名匹配可得）';
COMMENT ON COLUMN tp_cluster_command.status IS '0待下发 1已领取 2执行中 3成功 4失败 5已撤销（仅0可撤；流转见 03）';
COMMENT ON COLUMN tp_cluster_command.payload IS '命令载荷（engine 型必含 engines:[描述符code]；零凭据禁明文 token，红线 4）';
COMMENT ON COLUMN tp_cluster_command.last_progress IS '最近进度投影 {step,message,at}（覆盖写；节点卡实时刷新的数据源）';
COMMENT ON COLUMN tp_cluster_command.result_detail IS '回执详情（≤2000 截断；失败原因=env-doctor 翻译输入）';

ALTER TABLE tp_executor
    ADD COLUMN IF NOT EXISTS drain_requested BOOLEAN NOT NULL DEFAULT false;

COMMENT ON COLUMN tp_executor.drain_requested IS '缩容排空旗（true=停派发新任务，在跑归零后回执释放；独立于 status 三态零涟漪）';

ALTER TABLE tp_cluster_enroll_token
    ADD COLUMN IF NOT EXISTS last_step JSONB;

COMMENT ON COLUMN tp_cluster_enroll_token.last_step IS '装机打点最近一步投影 {step,stepStatus,message,at}（-2 步骤条；覆盖写禁流水表）';

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES
    ('installkit.command-ttl-seconds', '86400', 'APP自动化', 'int', 0,
     '机群命令队列待下发有效期秒（默认 1 天；超时未领取的命令不再下发，页面重新下发一条即可）')
ON CONFLICT (config_key) DO NOTHING;
