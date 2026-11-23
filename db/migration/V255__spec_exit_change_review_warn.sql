-- Migration V255: seed the plan-exit change-review hint switch (K8 key, hot-reload).
-- Semantics: api-spec.exit.change-review.warn = 0 (default) hides the hint;
--   non-zero makes completionCheck surface the project's pending contract
--   change-review count as a plain-language hint. Hint only — it never blocks
--   exit and never changes the core verdict message.
-- Idempotent: ON CONFLICT DO NOTHING (dev database applies migrations manually,
--   Flyway not enabled; db/migration is the authoritative ledger).
-- Usage: docker exec -i testpike-postgres psql -U testpike -d testpike -v ON_ERROR_STOP=1 -f - < this file

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
('api-spec.exit.change-review.warn', '0', 'API测试', 'int', 0, '计划准出检查的契约变更待复核提示开关：0=不提示（默认）；非0=准出结果附带待复核条数提示，只提示不拦截准出，热更新即时生效')
ON CONFLICT (config_key) DO NOTHING;
