-- Migration: seed the shipped signature-auth presets for API test requests.
-- Purpose: the auth editor offers enterprise signature templates (HMAC-SHA256 /
--   MD5 / SHA1) as data, so adding an internal algorithm later means inserting a
--   config row — no code change, hot-update on next read.
--   value is JSON {label, assemblies:[{into,name,value}]}. Assembly templates use
--   the canonical ${gen.*} function dialect (docs/04 rule 6a — single executor
--   parser) and reference vault secrets via {{CRED:key}} (rule 6b). The seeded
--   appKey/appSecret references are starters: users swap them for credential keys
--   registered in their own project before running.
--   encrypt_flag=0: rows carry template text only, never secret values.
-- Idempotent: ON CONFLICT (config_key) DO NOTHING.
-- Usage: docker exec -i -e PGPASSWORD=testpike testpike-postgres psql -U testpike -d testpike -f - < db/migration/V257__apitest_auth_preset_seed.sql

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES
('apitest.auth.preset.hmac-sha256', '{"label":"HMAC-SHA256 签名（时间戳+随机串+摘要头）","assemblies":[{"into":"header","name":"X-Timestamp","value":"${gen.timestamp(unit=s)}"},{"into":"header","name":"X-Nonce","value":"${gen.nonce(length=16)}"},{"into":"header","name":"X-Sign","value":"${gen.hmacSha256(text={{CRED:appKey}}{{CRED:appSecret}},key={{CRED:appSecret}})}"}]}', 'API测试', 'json', 0, '认证签名预设模板（编辑器选择后预填装配行；CRED 引用的 appKey/appSecret 需替换为本项目已建档的凭据键；时间戳/随机串/摘要用 ${gen.*} 函数；企业内部算法直接新增同前缀配置即可，热更生效）'),
('apitest.auth.preset.md5', '{"label":"MD5 摘要签名（密钥拼接摘要）","assemblies":[{"into":"header","name":"X-Timestamp","value":"${gen.timestamp(unit=ms)}"},{"into":"header","name":"X-Sign","value":"${gen.md5(text={{CRED:appKey}}{{CRED:appSecret}})}"}]}', 'API测试', 'json', 0, '认证签名预设模板（编辑器选择后预填装配行；CRED 引用的 appKey/appSecret 需替换为本项目已建档的凭据键；时间戳/随机串/摘要用 ${gen.*} 函数；企业内部算法直接新增同前缀配置即可，热更生效）'),
('apitest.auth.preset.sha1', '{"label":"SHA-1 摘要签名（密钥拼接摘要）","assemblies":[{"into":"header","name":"X-Timestamp","value":"${gen.timestamp(unit=ms)}"},{"into":"header","name":"X-Sign","value":"${gen.sha1(text={{CRED:appKey}}{{CRED:appSecret}})}"}]}', 'API测试', 'json', 0, '认证签名预设模板（编辑器选择后预填装配行；CRED 引用的 appKey/appSecret 需替换为本项目已建档的凭据键；时间戳/随机串/摘要用 ${gen.*} 函数；企业内部算法直接新增同前缀配置即可，热更生效）')
ON CONFLICT (config_key) DO NOTHING;
