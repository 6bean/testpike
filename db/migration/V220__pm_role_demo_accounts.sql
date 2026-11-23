
INSERT INTO tp_role (name, code, description, data_scope, status, builtin) VALUES
    ('项目经理', 'project_manager', '管理测试计划与评审审批、导出报表（不执行测试）', 2, 1, 1)
ON CONFLICT (code) DO NOTHING;

INSERT INTO tp_user (username, password_hash, display_name, email, role, status) VALUES
    ('demo_developer',       '$2a$10$pdY8h803LkaPEr3Hxdxl.OZnerfK8EFpKjIQ7R2sYeZqNjZPrlNv2', '演示-开发人员',   'demo.developer@testpike.local',   3, 1),
    ('demo_tester',          '$2a$10$KwqhJnCiv20WeriLdA64/.bvEJxzpTbs1uTNSnINxZXOT/HUCm4Xm', '演示-测试人员',   'demo.tester@testpike.local',      1, 1),
    ('demo_test_lead',       '$2a$10$5ErgWCs3/0Riq4yk3yCwJONXNhMJ5ZjWhZZSlunRGVckSEiQq7Yp.', '演示-测试负责人', 'demo.testlead@testpike.local',    2, 1),
    ('demo_project_manager', '$2a$10$L/FOqLKcDVdaNzvTlmTKVOkWLAteZXZLOFy4DUFI2dIpc6qX1zxcq', '演示-项目经理',   'demo.pm@testpike.local',          0, 1),
    ('demo_admin',           '$2a$10$Yvsxbqoi4Qi/yKDMYSnAp.2TmyD.rsJ/TklD2AQq8Qk6AdUIxGCOK', '演示-系统管理员', 'demo.admin@testpike.local',       4, 1)
ON CONFLICT (username) WHERE deleted = 0 DO NOTHING;

INSERT INTO tp_user_role (user_id, role_id)
SELECT u.id, r.id FROM tp_user u
JOIN tp_role r ON r.code = CASE u.username
    WHEN 'demo_developer'       THEN 'developer'
    WHEN 'demo_tester'          THEN 'tester'
    WHEN 'demo_test_lead'       THEN 'test_lead'
    WHEN 'demo_project_manager' THEN 'project_manager'
    WHEN 'demo_admin'           THEN 'admin'
END
WHERE u.username LIKE 'demo_%' AND u.deleted = 0
ON CONFLICT (user_id, role_id) DO NOTHING;

COMMENT ON COLUMN tp_user.role IS '1=测试人员 2=测试负责人 3=开发人员 4=系统管理员（迁移件 起演示-项目经理=0 无旧枚举位，权限走 tp_user_role 新链）';
