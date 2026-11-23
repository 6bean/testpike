
CREATE TABLE IF NOT EXISTS tp_appauto_popup_rule (
    id              BIGSERIAL    PRIMARY KEY,
    project_id      BIGINT       NOT NULL REFERENCES tp_project (id),
    source_task_id  BIGINT       NULL,
    title           VARCHAR(200) NULL,
    rule            JSONB        NOT NULL,
    status          SMALLINT     NOT NULL DEFAULT 1,
    source          VARCHAR(16)  NOT NULL DEFAULT 'AI',
    created_at      TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT ck_appauto_popup_rule_status CHECK (status IN (1, 2, 3)),
    CONSTRAINT ck_appauto_popup_rule_source CHECK (source IN ('AI', 'MANUAL'))
);

COMMENT ON TABLE tp_appauto_popup_rule IS 'APP 弹窗规则草稿（规则库最小落地切片，两层质量门=人批准才入库）';
COMMENT ON COLUMN tp_appauto_popup_rule.rule IS '交换格式 JSONB（{"detect":…,"dismiss":{action,target}}，结构闸在服务层）';
COMMENT ON COLUMN tp_appauto_popup_rule.status IS '1 待审/2 已入库/3 已驳回（RecordDraft 状态机同值域）';
COMMENT ON COLUMN tp_appauto_popup_rule.source IS 'AI=自学（本波唯一生产者）/MANUAL=人工建档预留位';

CREATE INDEX IF NOT EXISTS ix_appauto_popup_rule_board
    ON tp_appauto_popup_rule (project_id, status, created_at DESC);

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES ('ai.appauto.popup-rule-learn', '1', 'APP自动化', 'int', 0,
        '弹窗规则自学开关（默认关；关=从失败录屏帧学习弹窗模式的入口恒 403 拒绝并说明原因零模型外呼，开=监看帧剔除敏感帧后识别新弹窗模式→规则草稿进人批准门，批准才入库。失败任务监看帧消费）')
ON CONFLICT (config_key) DO NOTHING;
