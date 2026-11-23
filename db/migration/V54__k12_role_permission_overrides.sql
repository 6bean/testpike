
-- ---------------------------------------------------------------------
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS tp_role_permission (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    role_id    BIGINT      NOT NULL REFERENCES tp_role(id),
    perm_key   VARCHAR(80) NOT NULL,
    effect     SMALLINT    NOT NULL DEFAULT 1,
    created_by BIGINT,
    created_at TIMESTAMP   NOT NULL DEFAULT now(),
    updated_at TIMESTAMP   NOT NULL DEFAULT now(),
    CONSTRAINT uq_role_permission UNIQUE (role_id, perm_key)
);
COMMENT ON TABLE tp_role_permission IS '自定义角色权限覆写集（K12；有效集=模板基线∪allow−deny，deny 优先；4 预定义角色不落行=代码模板）';
COMMENT ON COLUMN tp_role_permission.perm_key IS '权限键（perm:{域}:{动作}，值域=common PermissionCatalog 147 键单点校验）';
COMMENT ON COLUMN tp_role_permission.effect IS '1=allow 显式允许 2=deny 显式拒绝（优先级最高；同键并存被 UNIQUE 拒=）';
CREATE INDEX IF NOT EXISTS idx_role_permission_role ON tp_role_permission (role_id);
