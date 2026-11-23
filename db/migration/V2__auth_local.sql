-- ============================================================
-- ============================================================

CREATE TABLE IF NOT EXISTS tp_user (
    id            BIGSERIAL PRIMARY KEY,
    username      VARCHAR(50)  NOT NULL,
    password_hash VARCHAR(100),
    display_name  VARCHAR(100),
    email         VARCHAR(100),
    phone         VARCHAR(20),
    role          SMALLINT     NOT NULL DEFAULT 1,
    status        SMALLINT     NOT NULL DEFAULT 1,
    deleted       SMALLINT     NOT NULL DEFAULT 0,
    created_by    BIGINT,
    created_at    TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP    NOT NULL DEFAULT now()
);

COMMENT ON TABLE tp_user IS '本地用户（认证双模式新增；SSO 模式用户由认证中心同步，password_hash 可空）';
COMMENT ON COLUMN tp_user.role IS '1=测试人员 2=测试负责人 3=开发人员 4=系统管理员';
COMMENT ON COLUMN tp_user.status IS '1=启用 0=停用';
COMMENT ON COLUMN tp_user.deleted IS '0=正常 1=软删（列表/登录不可见，历史引用保留）';

CREATE UNIQUE INDEX IF NOT EXISTS uk_tp_user_username ON tp_user (username) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_tp_user_role ON tp_user (role) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_tp_user_status ON tp_user (status) WHERE deleted = 0;
