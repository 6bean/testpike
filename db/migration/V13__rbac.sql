-- =====================================================================
-- =====================================================================

CREATE TABLE IF NOT EXISTS tp_role (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name        VARCHAR(50)  NOT NULL,
    code        VARCHAR(50)  NOT NULL UNIQUE,
    description VARCHAR(200),
    data_scope  SMALLINT     NOT NULL DEFAULT 2,
    status      SMALLINT     NOT NULL DEFAULT 1,
    builtin     SMALLINT     NOT NULL DEFAULT 0,
    created_by  BIGINT,
    created_at  TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT now()
);
COMMENT ON TABLE tp_role IS '角色（RBAC， K4）';
COMMENT ON COLUMN tp_role.data_scope IS '数据权限范围：1=全部项目 2=项目内 3=仅本人';
COMMENT ON COLUMN tp_role.builtin IS '1=内置角色不可删除/修改 code';

CREATE TABLE IF NOT EXISTS tp_role_func (
    id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    role_id   BIGINT      NOT NULL REFERENCES tp_role (id),
    perm_code VARCHAR(50) NOT NULL,   -- case:review / case:assign / config:manage / llm:config / user:manage / executor:view ...
    CONSTRAINT uk_role_func UNIQUE (role_id, perm_code)
);
COMMENT ON TABLE tp_role_func IS '角色功能权限点（多对多）';

CREATE TABLE IF NOT EXISTS tp_user_role (
    id      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES tp_user (id),
    role_id BIGINT NOT NULL REFERENCES tp_role (id),
    CONSTRAINT uk_user_role UNIQUE (user_id, role_id)
);
COMMENT ON TABLE tp_user_role IS '用户-角色关联（替代 tp_user.role，兼容迁移）';

INSERT INTO tp_role (name, code, description, data_scope, status, builtin) VALUES
    ('测试人员',   'tester',   '执行测试用例、提交缺陷', 3, 1, 1),
    ('测试负责人', 'test_lead','管理测试计划、分配用例、评审', 2, 1, 1),
    ('开发人员',   'developer','查看用例与缺陷（只读）', 2, 1, 1),
    ('系统管理员', 'admin',    '系统配置、用户与角色管理', 1, 1, 1)
ON CONFLICT (code) DO NOTHING;

INSERT INTO tp_user_role (user_id, role_id)
SELECT u.id, r.id
FROM tp_user u
JOIN tp_role r ON r.code = CASE u.role
    WHEN 1 THEN 'tester'
    WHEN 2 THEN 'test_lead'
    WHEN 3 THEN 'developer'
    WHEN 4 THEN 'admin'
END
WHERE u.deleted = 0
  AND u.role BETWEEN 1 AND 4
ON CONFLICT (user_id, role_id) DO NOTHING;

ALTER TABLE tp_user     ADD COLUMN IF NOT EXISTS avatar VARCHAR(200);
ALTER TABLE tp_test_case ADD COLUMN IF NOT EXISTS phase SMALLINT;
ALTER TABLE tp_project  ADD COLUMN IF NOT EXISTS llm_config_id BIGINT;
ALTER TABLE tp_exec_task ADD COLUMN IF NOT EXISTS log TEXT;

INSERT INTO tp_role_func (role_id, perm_code)
SELECT r.id, p.perm FROM tp_role r
JOIN (VALUES
    ('admin',      'case:review'), ('admin', 'case:assign'), ('admin', 'config:manage'),
    ('admin',      'llm:config'),  ('admin', 'user:manage'), ('admin', 'executor:view'),
    ('test_lead',  'case:review'), ('test_lead', 'case:assign'), ('test_lead', 'executor:view'),
    ('tester',     'executor:view'),
    ('developer',  'executor:view')
) AS p(code, perm) ON p.code = r.code
ON CONFLICT (role_id, perm_code) DO NOTHING;
