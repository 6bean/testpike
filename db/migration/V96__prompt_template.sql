
CREATE TABLE IF NOT EXISTS tp_prompt_template (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name         VARCHAR(100) NOT NULL,
    domain       VARCHAR(50)  NOT NULL,
    version      INT          NOT NULL,
    content      TEXT         NOT NULL,
    status       SMALLINT     NOT NULL DEFAULT 1,
    created_by   BIGINT,
    rollback_to  BIGINT,
    created_at   TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at   TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT uk_prompt_template_name_domain_version UNIQUE (name, domain, version)
);

CREATE INDEX IF NOT EXISTS idx_prompt_template_name_domain
    ON tp_prompt_template (name, domain, status);

COMMENT ON TABLE tp_prompt_template IS 'Prompt 模板（D4/；01 表 23 声明补建，修改即新版本/回滚/灰度）';
COMMENT ON COLUMN tp_prompt_template.name         IS '模板名（生成链消费标识，如 case-gen-prompt/script-gen-prompt）';
COMMENT ON COLUMN tp_prompt_template.domain       IS '业务域（用例生成/脚本生成等分组维度）';
COMMENT ON COLUMN tp_prompt_template.version      IS '族内版本号（同 name+domain 递增；任务钉住此 version 所在行）';
COMMENT ON COLUMN tp_prompt_template.content      IS '模板正文（占位符渲染骨架，动态段由生成链拼装）';
COMMENT ON COLUMN tp_prompt_template.status       IS '1=灰度(默认) 2=全量 3=历史';
COMMENT ON COLUMN tp_prompt_template.rollback_to  IS '回滚来源模板行 id（本版本内容复制自该行；NULL=非回滚产物）';

ALTER TABLE tp_ai_task ADD COLUMN IF NOT EXISTS template_version_id BIGINT;
COMMENT ON COLUMN tp_ai_task.template_version_id IS '任务创建时钉住的 Prompt 模板版本行 id（D4/；NULL=未钉住回退内置默认；执行中改模板不回拽在跑任务）';

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES
('prompt.gen.traffic-pct', '0', '系统', 'int', 0, 'D4 灰度流量百分比 0~100（T253；0=零流量默认安全，hash(projectId)%100<此值 命中灰度模板；非法值写侧 400）')
ON CONFLICT (config_key) DO NOTHING;
