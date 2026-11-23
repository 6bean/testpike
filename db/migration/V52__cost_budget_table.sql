
-- ---------------------------------------------------------------------
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS tp_cost_budget (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id           BIGINT      NOT NULL REFERENCES tp_project(id),
    budget_cents         BIGINT      NOT NULL DEFAULT 0,
    alert_pct            INT         NOT NULL DEFAULT 80,
    break_override_until TIMESTAMP,
    break_override_reason VARCHAR(500),
    created_by           BIGINT,
    created_at           TIMESTAMP   NOT NULL DEFAULT now(),
    updated_at           TIMESTAMP   NOT NULL DEFAULT now(),
    CONSTRAINT uq_cost_budget_project UNIQUE (project_id)
);
COMMENT ON TABLE tp_cost_budget IS '项目成本预算与熔断状态（E3；无行=继承 ai.cost.* 全局默认）';
COMMENT ON COLUMN tp_cost_budget.budget_cents IS '月度预算分（0=不限）；与 tp_ai_call_log.cost DECIMAL(10,4) 元比较经 ×100 换算（core CostBudgetReader 单一来源）';
COMMENT ON COLUMN tp_cost_budget.alert_pct    IS '预警水位百分比（默认 80；80% 发 COST_BUDGET_ALERT 日去重站内信，100% 熔断 AI 生成拒启）';
COMMENT ON COLUMN tp_cost_budget.break_override_until IS '熔断临时恢复到期（≤72h 时限由 服务层守；到期 Elastic Job 自动重熔=）';

-- ---------------------------------------------------------------------
-- ---------------------------------------------------------------------
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
('ai.cost.budget-default-cents', '0',  '系统', 'int',  0, '项目默认月度 Token 预算（分，0=不限；tp_cost_budget 覆写优先，E3/T201）'),
('ai.cost.alert-pct',            '80', '系统', 'int',  0, '成本预警水位百分比（默认 80；80% 发 COST_BUDGET_ALERT，T202 消费）'),
('ai.cost.break-enabled',        'true', '系统', 'bool', 0, '超预算熔断 AI 生成开关（默认 true；熔断=生成任务拒启，已有任务不中断，T202 消费）')
ON CONFLICT (config_key) DO NOTHING;
