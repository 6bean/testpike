
CREATE TABLE IF NOT EXISTS tp_api_spec_preview (
    id          bigserial PRIMARY KEY,
    project_id  bigint NOT NULL REFERENCES tp_project (id),
    spec_id     bigint NOT NULL REFERENCES tp_api_spec (id),
    items       jsonb        NOT NULL DEFAULT '[]'::jsonb,
    applied_keys jsonb       NOT NULL DEFAULT '[]'::jsonb,
    status      varchar(16)  NOT NULL DEFAULT 'pending',
    created_by  bigint,
    created_at  timestamp    NOT NULL DEFAULT now(),
    updated_at  timestamp    NOT NULL DEFAULT now(),
    CONSTRAINT uk_api_spec_preview_spec UNIQUE (spec_id, project_id),
    CONSTRAINT ck_api_spec_preview_status CHECK (status IN ('pending', 'applied', 'dismissed'))
);

CREATE INDEX IF NOT EXISTS idx_api_spec_preview_project
    ON tp_api_spec_preview (project_id, status);

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES
('apitest.import.duration-ms', '3000', 'API测试', 'int', 0, '接口文档导入默认耗时断言阈值毫秒（100~600000，TC-W1b）'),
('apitest.import.body-check-path', '$.code', 'API测试', 'string', 0, '接口文档导入默认响应约定断言 JsonPath（空串=不出该断言行）'),
('apitest.import.body-check-expect', '0', 'API测试', 'string', 0, '接口文档导入默认响应约定断言期望值（公司响应 code 约定）')
ON CONFLICT (config_key) DO NOTHING;
