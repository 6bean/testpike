-- Migration: seed mock.public-base-url, the one config the workbench URL card was
-- missing . The card and the copy-curl example need the
-- externally reachable address of the traffic plane (default port 18086); a
-- deployment behind a reverse proxy or a domain changes this single K8 key
-- instead of editing code anywhere. Idempotent via WHERE NOT EXISTS.
-- Usage: docker exec -i -e PGPASSWORD=... testpike-postgres psql -U ... -d testpike -f - < this file
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
SELECT 'mock.public-base-url', 'http://localhost:18086', '接口仿真', 'string', 0,
       'Mock 数据面对外访问基址：URL 卡与 curl 示例按 {本值}{mock.traffic.base-path}/{空间键} 拼装；反代/域名部署改本值为对外可达地址（K8 热更，禁硬编码）'
WHERE NOT EXISTS (SELECT 1 FROM tp_sys_config WHERE config_key = 'mock.public-base-url');
