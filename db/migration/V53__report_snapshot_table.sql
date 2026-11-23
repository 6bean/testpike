
-- ---------------------------------------------------------------------
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS tp_report (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    scope_type   SMALLINT    NOT NULL,
    scope_id     BIGINT      NOT NULL DEFAULT 0,
    report_type  SMALLINT    NOT NULL,                  -- 1=weekly 2=monthly
    period_start TIMESTAMP   NOT NULL,
    period_end   TIMESTAMP   NOT NULL,
    payload      JSONB       NOT NULL,
    generated_at TIMESTAMP   NOT NULL DEFAULT now(),
    created_by   BIGINT,
    created_at   TIMESTAMP   NOT NULL DEFAULT now(),
    updated_at   TIMESTAMP   NOT NULL DEFAULT now(),
    CONSTRAINT uq_report_scope_period
        UNIQUE (scope_type, scope_id, report_type, period_start),
    CONSTRAINT ck_report_scope_type CHECK (scope_type IN (1, 2)),
    CONSTRAINT ck_report_type       CHECK (report_type IN (1, 2)),
    CONSTRAINT ck_report_period_ordered CHECK (period_start < period_end)
);
COMMENT ON TABLE  tp_report IS '报表快照（v2.26 O10 报表中心）：周/月报 payload=口径注册表取数+版本内嵌；同键覆盖重生成=幂等';
COMMENT ON COLUMN tp_report.scope_id     IS '项目维=项目 ID；全局维=0（禁 NULL：PG UNIQUE 对 NULL 不相等会破坏幂等覆盖）';
COMMENT ON COLUMN tp_report.payload      IS 'JSONB：{report_type,period,scope,calibers:{code:{value,version,display_name}},generated_by_job}——TC-O10-02 快照版本号内嵌不失真';
COMMENT ON COLUMN tp_report.period_start IS '期间起（含）：周报=周一 00:00（TC-O10-03 周一起算）；月报=自然月 1 日';

CREATE INDEX IF NOT EXISTS idx_report_scope  ON tp_report (scope_type, scope_id, report_type, period_start DESC);
CREATE INDEX IF NOT EXISTS idx_report_period ON tp_report (period_start DESC);

-- ---------------------------------------------------------------------
-- ---------------------------------------------------------------------
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
('report.retention-days',  '365',    '系统', 'int', 0, '报表快照保留天数（默认 365；清理链消费，O10/T204）'),
('report.export-max-rows', '100000', '系统', 'int', 0, '报表导出行数上限（默认 10 万；超限 400 可读缩范围，IT-O10-03，T204 包②消费）')
ON CONFLICT (config_key) DO NOTHING;

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
('report.weekly-enabled',  'true', '系统', 'bool', 0, '周报定时生成开关（默认开；关=Job 链路整体停发，O10/T204 包②b）'),
('report.monthly-enabled', 'true', '系统', 'bool', 0, '月报定时生成开关（默认开；关=Job 链路整体停发，O10/T204 包②b）')
ON CONFLICT (config_key) DO NOTHING;
