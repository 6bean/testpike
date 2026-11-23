-- Migration V264: seed the plan-exit contract-coverage gate key (K8, hot-reload).
-- Semantics: plan.exit.apitest.min-coverage = 0 (default) keeps the gate off so
--   every existing plan's exit verdict stays byte-identical; a value in 1~100
--   makes completionCheck measure project-wide contract-endpoint coverage
--   (denominator = non-deprecated endpoints of each contract's anchor version,
--   the same spec as the inventory coverage card) and block exit below the floor.
--   Absent/blank/unparseable/out-of-range reads as gate off on the exit-check
--   side (fail-open: a misconfigured key must never explode existing exits).
-- Idempotent: ON CONFLICT DO NOTHING (dev database applies migrations manually,
--   Flyway not enabled; db/migration is the authoritative ledger).
-- Usage: docker exec -i testpike-postgres psql -U testpike -d testpike -v ON_ERROR_STOP=1 -f - < this file

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
('plan.exit.apitest.min-coverage', '0', 'API测试', 'int', 0, '计划准出的接口契约端点覆盖率下限百分比（0~100）：0=不校验（默认）；>0=准出前实测本项目契约端点覆盖率，低于下限则拒绝准出并点名实际值，热更新即时生效')
ON CONFLICT (config_key) DO NOTHING;
