-- Migration V482: factory app-component pack (design 5.6, W5 phase-two B2).
-- Ships five built-in step components out of the box: login / logout / permission
-- handling / pull-to-refresh / common form fill, so a fresh install can insert APP
-- steps through the "/" component palette without anyone publishing them first.
--
-- Ownership: tp_asset.project_id is NOT NULL, so the pack needs exactly one owner
-- row. tp_project (code='SYS') is the factory owner: cross-project readers gain
-- discoverability through the asset-chain head projection (see tp_asset V358
-- comment), never write access — seeding per project instead would be a data
-- migration account that new projects still need a hook for (same reasoning as
-- the system-level default-visible ruling: pass-through + explicit opt-out).
--
-- Payload contract (single source = frontend/src/utils/assetRef.ts):
--   case_fragment = step-array snapshot, action_component = one step object.
-- Every verb stays inside the IR verb baseline (common UiActionContract, 29 verbs);
-- credential surfaces are {{CRED:key}} reference names only — no secret value ships.
--
-- Idempotent: every INSERT guards on its unique key, replay is free.
-- Flyway not enabled: apply manually to dev db.
-- Usage: docker exec -i testpike-postgres psql -U testpike -d testpike < db/migration/V482__appauto_factory_component_pack.sql

INSERT INTO tp_project (name, code, status)
SELECT '系统内置', 'SYS', 1
WHERE NOT EXISTS (SELECT 1 FROM tp_project WHERE code = 'SYS');

INSERT INTO tp_asset (project_id, kind, name, asset_version, status, payload_json)
SELECT p.id, 'case_fragment', '内置登录', 1, 1,
'[{"action":"launch","target":"被测 APP","timeoutMs":30000},
  {"action":"waitVisible","target":"登录页-用户名输入框","timeoutMs":15000},
  {"action":"tap","target":"登录页-用户名输入框"},
  {"action":"clearInput","target":"登录页-用户名输入框"},
  {"action":"input","target":"登录页-用户名输入框","value":"{{CRED:account}}"},
  {"action":"tap","target":"登录页-密码输入框"},
  {"action":"clearInput","target":"登录页-密码输入框"},
  {"action":"input","target":"登录页-密码输入框","value":"{{CRED:password}}"},
  {"action":"tap","target":"登录页-登录按钮"},
  {"action":"waitVisible","target":"首页-登录成功标志","timeoutMs":15000},
  {"action":"assertVisible","target":"首页-登录成功标志"}]'
FROM tp_project p
WHERE p.code = 'SYS'
  AND NOT EXISTS (SELECT 1 FROM tp_asset a
                  WHERE a.project_id = p.id AND a.kind = 'case_fragment'
                    AND a.name = '内置登录' AND a.asset_version = 1);

INSERT INTO tp_asset (project_id, kind, name, asset_version, status, payload_json)
SELECT p.id, 'case_fragment', '内置登出', 1, 1,
'[{"action":"tap","target":"首页-我的入口"},
  {"action":"waitVisible","target":"我的页-设置入口","timeoutMs":10000},
  {"action":"tap","target":"我的页-设置入口"},
  {"action":"scroll","target":"设置页-设置列表","direction":"down"},
  {"action":"tap","target":"设置页-退出登录按钮"},
  {"action":"waitVisible","target":"退出确认弹窗-确认按钮","timeoutMs":8000},
  {"action":"tap","target":"退出确认弹窗-确认按钮"},
  {"action":"waitVisible","target":"登录页-登录按钮","timeoutMs":15000},
  {"action":"assertVisible","target":"登录页-登录按钮"}]'
FROM tp_project p
WHERE p.code = 'SYS'
  AND NOT EXISTS (SELECT 1 FROM tp_asset a
                  WHERE a.project_id = p.id AND a.kind = 'case_fragment'
                    AND a.name = '内置登出' AND a.asset_version = 1);

INSERT INTO tp_asset (project_id, kind, name, asset_version, status, payload_json)
SELECT p.id, 'case_fragment', '内置权限处理', 1, 1,
'[{"action":"grantPermission","target":"{{VAR:appPackage}}","value":"{{VAR:permissionList}}"},
  {"action":"waitVisible","target":"系统权限弹窗-允许按钮","timeoutMs":5000},
  {"action":"tap","target":"系统权限弹窗-允许按钮"},
  {"action":"assertNotVisible","target":"系统权限弹窗-允许按钮"}]'
FROM tp_project p
WHERE p.code = 'SYS'
  AND NOT EXISTS (SELECT 1 FROM tp_asset a
                  WHERE a.project_id = p.id AND a.kind = 'case_fragment'
                    AND a.name = '内置权限处理' AND a.asset_version = 1);

INSERT INTO tp_asset (project_id, kind, name, asset_version, status, payload_json)
SELECT p.id, 'case_fragment', '内置下拉刷新', 1, 1,
'[{"action":"waitVisible","target":"列表页-顶部内容锚点","timeoutMs":10000},
  {"action":"screenshot","target":"下拉刷新前现场"},
  {"action":"swipe","target":"列表页-内容区","direction":"down"},
  {"action":"waitVisible","target":"列表页-刷新完成标志","timeoutMs":10000},
  {"action":"assertText","target":"列表页-首条数据标题","value":"{{DATA:首条标题}}"}]'
FROM tp_project p
WHERE p.code = 'SYS'
  AND NOT EXISTS (SELECT 1 FROM tp_asset a
                  WHERE a.project_id = p.id AND a.kind = 'case_fragment'
                    AND a.name = '内置下拉刷新' AND a.asset_version = 1);

INSERT INTO tp_asset (project_id, kind, name, asset_version, status, payload_json)
SELECT p.id, 'case_fragment', '内置常见表单填充', 1, 1,
'[{"action":"waitVisible","target":"表单页-表单容器","timeoutMs":10000},
  {"action":"tap","target":"表单页-姓名输入框"},
  {"action":"input","target":"表单页-姓名输入框","value":"{{DATA:姓名}}"},
  {"action":"tap","target":"表单页-手机号输入框"},
  {"action":"input","target":"表单页-手机号输入框","value":"{{DATA:手机号}}"},
  {"action":"tap","target":"表单页-邮箱输入框"},
  {"action":"input","target":"表单页-邮箱输入框","value":"{{DATA:邮箱}}"},
  {"action":"tap","target":"表单页-备注输入框"},
  {"action":"input","target":"表单页-备注输入框","value":"{{DATA:备注}}"},
  {"action":"assertVisible","target":"表单页-提交按钮"}]'
FROM tp_project p
WHERE p.code = 'SYS'
  AND NOT EXISTS (SELECT 1 FROM tp_asset a
                  WHERE a.project_id = p.id AND a.kind = 'case_fragment'
                    AND a.name = '内置常见表单填充' AND a.asset_version = 1);
