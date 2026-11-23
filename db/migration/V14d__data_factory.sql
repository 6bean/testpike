
CREATE TABLE IF NOT EXISTS tp_data_pool_item (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id  BIGINT NOT NULL,
    env_id      BIGINT NOT NULL,
    type        VARCHAR(20) NOT NULL,
    value       TEXT NOT NULL,
    value_hash  VARCHAR(64) NOT NULL,
    status      SMALLINT NOT NULL DEFAULT 2 CHECK (status IN (1, 2, 3)),
    expire_at   TIMESTAMP,
    created_by  BIGINT,
    created_at  TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT uq_pool_env_type_hash UNIQUE (env_id, type, value_hash)
);
COMMENT ON TABLE tp_data_pool_item IS '测试数据池项（L5 造数产出；按环境隔离，PRD E10/4.7）';
COMMENT ON COLUMN tp_data_pool_item.value IS '测试数据值（账号/订单号/券码），AES-GCM 加密存储';

CREATE TABLE IF NOT EXISTS tp_data_factory (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id  BIGINT NOT NULL,
    env_id      BIGINT NOT NULL,
    type        SMALLINT NOT NULL CHECK (type IN (1, 2, 3)),
    name        VARCHAR(100) NOT NULL,
    content     TEXT NOT NULL,
    params_json TEXT,
    status      SMALLINT NOT NULL DEFAULT 1 CHECK (status IN (1, 2)),
    created_by  BIGINT,
    created_at  TIMESTAMP NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP NOT NULL DEFAULT now()
);
COMMENT ON TABLE tp_data_factory IS '自动造数脚本定义（L5 数据工厂）';
COMMENT ON COLUMN tp_data_factory.content IS 'type=1: {"method":"POST","url":"...","headers":{},"body":{},"extractExpr":"$.data.id"}；type=2: INSERT SQL 文本；type=3: Python 脚本（stdout 输出 JSON 数组）';

CREATE TABLE IF NOT EXISTS tp_data_factory_run (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    factory_id     BIGINT NOT NULL,
    status         SMALLINT NOT NULL CHECK (status IN (1, 2, 3)),
    result_summary VARCHAR(500),
    created_by     BIGINT,
    created_at     TIMESTAMP NOT NULL DEFAULT now()
);
COMMENT ON TABLE tp_data_factory_run IS '造数运行历史（L5）：执行中/成功/失败 + 结果摘要';

CREATE INDEX IF NOT EXISTS idx_data_factory_project ON tp_data_factory (project_id, id);
CREATE INDEX IF NOT EXISTS idx_data_factory_run_factory ON tp_data_factory_run (factory_id, id);
