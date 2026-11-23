-- Migration: seed the shipped press-test scenario templates.
-- Purpose: the wizard offers proven playbooks (e-commerce checkout, flash-sale
--   spike, query baseline, AI-inference prefill) as data, so adding an internal
--   scenario shape later means inserting a config row — no code change,
--   hot-update on next read.
--   JSON {label, scenario} where scenario is shaped exactly like the scenario
--   save input (pressureModel/pressureConfig/steps/slaJson) and is pre-judged
--   against the same single-source domains the save gate uses — a template the
--   wizard pre-fills can never become a scenario the user cannot save.
--   Step urls carry {{ENV:BASE}} placeholders resolved at dispatch; example
--   paths describe the traffic shape and should be adapted to the system under
--   test before launching.
--   encrypt_flag=0: rows carry template text only, never credentials.
-- The AI-inference row is a connectivity/throughput prefill: first-token
--   latency and token-rate collection become available with the inference
--   metrics engine and are honestly absent from this template until then.
-- Idempotent: ON CONFLICT (config_key) DO NOTHING.
-- Usage: docker exec -i -e PGPASSWORD=testpike testpike-postgres psql -U testpike -d testpike -f - < db/migration/V288__perf_scenario_template_seed.sql

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES
('perf.template.ecom-checkout', '{"label":"电商下单链路（全链路配比）","scenario":{"pressureModel":"RAMP","pressureConfig":{"startRps":20,"peakRps":300,"rampSeconds":60,"holdSeconds":120},"steps":[{"action":"http.request","method":"POST","url":"{{ENV:BASE}}/cart/items","transaction":"下单链路","weight":1},{"action":"http.request","method":"POST","url":"{{ENV:BASE}}/orders","transaction":"下单链路","weight":1},{"action":"http.request","method":"POST","url":"{{ENV:BASE}}/orders/pay","transaction":"下单链路","weight":1}],"slaJson":{"p95Ms":800,"errorRate":0.01,"breakerErrorRate":0.05}}}', '系统', 'json', 0, '出厂压测场景模板：购物车加购→下单→支付三步按 1:1:1 配比组成一条下单链路，阶梯爬坡形态（选中后向导自动预填压力形态、参数、步骤与 SLA，示例路径请改为被测系统真实接口再发起）'),
('perf.template.flash-sale', '{"label":"秒杀脉冲（基线+峰值两波）","scenario":{"pressureModel":"SPIKE","pressureConfig":{"baseRps":50,"peakRps":800,"durationSeconds":300},"steps":[{"action":"http.request","method":"GET","url":"{{ENV:BASE}}/goods/detail","expect":{"status":200}},{"action":"http.request","method":"POST","url":"{{ENV:BASE}}/seckill/order"}],"slaJson":{"p95Ms":1000,"errorRate":0.02,"minRps":400}}}', '系统', 'json', 0, '出厂压测场景模板：商详浏览打底流量+秒杀下单在峰值窗口集中施压，峰值 RPS 需高于基线 RPS（选中后向导自动预填压力形态、参数、步骤与 SLA，示例路径请改为被测系统真实接口再发起）'),
('perf.template.query-baseline', '{"label":"查询检索基线（恒定吞吐）","scenario":{"pressureModel":"CONSTANT_RPS","pressureConfig":{"targetRps":200,"durationSeconds":180},"steps":[{"action":"http.request","method":"GET","url":"{{ENV:BASE}}/api/search?q=phone","expect":{"status":200}}],"slaJson":{"p95Ms":500,"errorRate":0.01,"minRps":180,"breakerErrorRate":0.1}}}', '系统', 'json', 0, '出厂压测场景模板：单接口恒定 RPS 摸底，用于建立响应时间基线与容量参照（选中后向导自动预填压力形态、参数、步骤与 SLA，示例路径请改为被测系统真实接口再发起）'),
('perf.template.ai-inference', '{"label":"AI 推理服务预检（吞吐摸底）","scenario":{"pressureModel":"CONSTANT_RPS","pressureConfig":{"targetRps":5,"durationSeconds":120},"steps":[{"action":"http.request","method":"POST","url":"{{ENV:BASE}}/v1/chat/completions","expect":{"status":200}}],"slaJson":{"p95Ms":15000,"errorRate":0.02}}}', '系统', 'json', 0, '出厂压测场景模板：大模型推理接口连通性与吞吐预检（低并发恒定 RPS，首字延迟与 token 速率采集将随推理指标引擎接入后在本模板生效）')
ON CONFLICT (config_key) DO NOTHING;
