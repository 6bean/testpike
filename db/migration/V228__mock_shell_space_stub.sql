-- API Mock module, migration shell part 1 : the two core
-- aggregates of the mock rule source plus the K8 governance key seeds.
-- What this file lands and why:
--   1) tp_mock_space = one externally callable mock endpoint container. space_key is
--      the global routing handle (/mocks/{space_key}/**), alias allows a custom path
--      segment; env_id binds an environment profile and the production ban is enforced
--      at the save-chain service (a CHECK here cannot see the env table);
--      base_path_mode prefix passes sub-paths through, strict requires full match;
--      status is the three-state lifecycle draft/published/retired; priority breaks
--      cross-space routing ties without changing ids.
--   2) tp_mock_stub = one mock rule, the SINGLE rule source consumed by the UI, the
--      snapshot loader in mock-server and the executor route interception alike
--      (red line: never a second rule table). matchers_json holds the per-matcher
--      rows (query/header/cookie/JSONPath-body), response_json the response builder
--      payload (status/headers/body template/latency/fault keys); the three scenario
--      columns are declared empty in this shell and filled by M5 — the column shape
--      is frozen now so later blades never ALTER the hot path.
--   3) mock_stale on tp_apitest_request = twin of contract_stale: when a contract
--      diff marks an endpoint changed/removed, stubs derived from it are flagged and
--      this column mirrors the badge onto the request row (cleared on edit).
--   4) K8 key seeds (ON CONFLICT DO NOTHING, hot-updatable, never hardcoded):
--      polling interval, hit batching/retention, scenario/session TTL, default rate
--      limit and quota, exit-plan ban (default 1 = plans exclude mock-backed cases),
--      AI master switch (default 0, per-application switches land with their
--      blades). Descriptions stay Chinese: they render in the config management UI.
-- Execution: manual psql (Flyway not enabled):
--   docker exec -i -e PGPASSWORD=... testpike-postgres psql -U ... -d testpike -f - < this file

CREATE TABLE IF NOT EXISTS tp_mock_space (
    id              BIGSERIAL    PRIMARY KEY,
    project_id      BIGINT       NOT NULL,
    name            VARCHAR(120) NOT NULL,
    space_key       VARCHAR(60)  NOT NULL,
    alias           VARCHAR(60),
    env_id          BIGINT,
    base_path_mode  VARCHAR(20)  NOT NULL DEFAULT 'prefix',
    status          SMALLINT     NOT NULL DEFAULT 0,
    auth_mode       VARCHAR(20)  NOT NULL DEFAULT 'none',
    rate_limit_rps  INT,
    quota_daily     BIGINT,
    priority        INT          NOT NULL DEFAULT 100,
    version         INT          NOT NULL DEFAULT 0,
    created_at      TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT uq_mock_space_key UNIQUE (space_key),
    CONSTRAINT ck_mock_space_status CHECK (status IN (0, 1, 2)),
    CONSTRAINT ck_mock_space_base_path_mode CHECK (base_path_mode IN ('prefix', 'strict')),
    CONSTRAINT ck_mock_space_auth_mode CHECK (auth_mode IN ('none', 'token', 'cred'))
);
CREATE INDEX IF NOT EXISTS idx_mock_space_project ON tp_mock_space (project_id);

COMMENT ON TABLE tp_mock_space IS 'Mock space: one externally callable mock endpoint container (routing root under /mocks/{space_key})';
COMMENT ON COLUMN tp_mock_space.project_id IS 'Owning project (H1 isolation: members see their project spaces only)';
COMMENT ON COLUMN tp_mock_space.space_key IS 'Globally unique short routing handle; alias gives a second optional path segment';
COMMENT ON COLUMN tp_mock_space.env_id IS 'Bound environment profile; production bindings are refused at the save-chain gate (fail-closed), NULL = no binding';
COMMENT ON COLUMN tp_mock_space.base_path_mode IS 'prefix = any sub-path passes through the matched stub; strict = full path must match';
COMMENT ON COLUMN tp_mock_space.status IS 'Lifecycle: 0 draft / 1 published / 2 retired (retired stops serving, history kept)';
COMMENT ON COLUMN tp_mock_space.auth_mode IS 'none = open traffic port (default); token / cred = credential reference checked at the gateway stage (M2+)';
COMMENT ON COLUMN tp_mock_space.rate_limit_rps IS 'Per-space global rate cap; NULL = fall back to the K8 default mock.rate-limit.default-rps';
COMMENT ON COLUMN tp_mock_space.quota_daily IS 'Per-space daily request quota; NULL = fall back to mock.quota.default-daily';
COMMENT ON COLUMN tp_mock_space.priority IS 'Routing tie-breaker across spaces on the same host (lower number wins); never a hidden id dependency';
COMMENT ON COLUMN tp_mock_space.version IS 'Optimistic lock column (save-chain conflicts reported in plain words)';

CREATE TABLE IF NOT EXISTS tp_mock_stub (
    id               BIGSERIAL    PRIMARY KEY,
    space_id         BIGINT       NOT NULL REFERENCES tp_mock_space (id),
    name             VARCHAR(120) NOT NULL,
    priority         INT          NOT NULL DEFAULT 100,
    method           VARCHAR(20)  NOT NULL,
    path_match_type  VARCHAR(20)  NOT NULL DEFAULT 'exact',
    path_pattern     VARCHAR(300) NOT NULL,
    matchers_json    JSONB        NOT NULL DEFAULT '[]',
    response_json    JSONB        NOT NULL DEFAULT '{}',
    scenario_name    VARCHAR(80),
    required_state   VARCHAR(80),
    new_state        VARCHAR(80),
    source           VARCHAR(20)  NOT NULL DEFAULT 'manual',
    source_ref       VARCHAR(300),
    enabled          BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at       TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT ck_mock_stub_match_type CHECK (path_match_type IN ('exact', 'prefix', 'regex', 'template')),
    CONSTRAINT ck_mock_stub_source CHECK (source IN ('manual', 'openapi', 'record', 'nl-ai', 'case-flip')),
    CONSTRAINT ck_mock_stub_matchers CHECK (jsonb_typeof(matchers_json) = 'array'),
    CONSTRAINT ck_mock_stub_response CHECK (jsonb_typeof(response_json) = 'object')
);
CREATE INDEX IF NOT EXISTS idx_mock_stub_space ON tp_mock_stub (space_id, enabled, priority);

COMMENT ON TABLE tp_mock_stub IS 'Mock stub: one simulation rule; single rule source shared by UI, mock-server snapshot loader and executor route interception (no second copy)';
COMMENT ON COLUMN tp_mock_stub.priority IS 'Within-space evaluation order: ascending, first full match wins; the inspector shows the per-matcher verdict trace';
COMMENT ON COLUMN tp_mock_stub.method IS 'HTTP method verb (ANY handled as a service-level wildcard, stored uppercase)';
COMMENT ON COLUMN tp_mock_stub.path_match_type IS 'exact / prefix / regex / template (path_pattern carries {var} captures usable from response templates)';
COMMENT ON COLUMN tp_mock_stub.matchers_json IS 'Per-matcher rows as jsonb array (query/header/cookie/JSONPath-body/form), shape frozen by the T358 match kernel';
COMMENT ON COLUMN tp_mock_stub.response_json IS 'Response builder payload (status/headers/body template/latency/fault keys), validated by MockRuleValidator at save time';
COMMENT ON COLUMN tp_mock_stub.scenario_name IS 'Stateful scenario this stub belongs to (NULL = stateless); machine definition lands in tp_mock_scenario with M5';
COMMENT ON COLUMN tp_mock_stub.source IS 'Provenance: manual / openapi (contract derived) / record (cassette promoted) / nl-ai (drafted, always previewed) / case-flip (promoted from a test case)';
COMMENT ON COLUMN tp_mock_stub.source_ref IS 'Provenance anchor (spec id, recording id, case id...) so badges and stale flags can retrace the origin';

ALTER TABLE tp_apitest_request
    ADD COLUMN IF NOT EXISTS mock_stale BOOLEAN NOT NULL DEFAULT FALSE;
COMMENT ON COLUMN tp_apitest_request.mock_stale IS
    'Twin of contract_stale: true while an upstream contract diff marked changed/removed endpoints touch stubs derived from this request; cleared on edit (badge semantics reused from W1b)';

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES
    ('mock.traffic.base-path', '/mocks', '接口仿真', 'string', 0,
     'Mock 流量口路由根前缀：所有空间在 /{base-path}/{space_key}/ 下接客；改值经装载周期生效（热更，禁硬编码）'),
    ('mock.rule-load.interval-seconds', '3', '接口仿真', 'int', 0,
     'mock-server 规则快照轮询间隔秒数：装载断链时按最后钉版续客并如实上报 staleness（fail-closed）'),
    ('mock.hit.batch-size', '200', '接口仿真', 'int', 0,
     '命中账本攒批条数：达到即经 MQ testpike.mock-hit 回传 core 落账（数据面禁直写 PG）'),
    ('mock.hit.flush-seconds', '5', '接口仿真', 'int', 0,
     '命中账本攒批最长等待秒数：未达批量也按时冲刷，防检视器流水长时间缺条'),
    ('mock.hit-retention-days', '30', '接口仿真', 'int', 0,
     '命中流水保留天数：回收随 T362 的 mock-hit-sweep 定时任务点火（本壳只定口径）'),
    ('mock.scenario.ttl-minutes', '120', '接口仿真', 'int', 0,
     'Stateful 场景运行态（Redis 会话泳道键）空闲过期分钟数'),
    ('mock.session.ttl-minutes', '120', '接口仿真', 'int', 0,
     '会话泳道标识的空闲过期分钟数（X-TP-Mock-Session 划道，CI 并行互不串扰）'),
    ('mock.rate-limit.default-rps', '50', '接口仿真', 'int', 0,
     '空间未单独设 rate_limit_rps 时的全局限流缺省（RPS）'),
    ('mock.quota.default-daily', '100000', '接口仿真', 'int', 0,
     '空间未单独设 quota_daily 时的日配额缺省条数'),
    ('mock.proxy.allowlist', '', '接口仿真', 'string', 0,
     '代理转发目标主机白名单（逗号分隔）：SSRF 前置闸，空=代理态一律拒（fail-closed）'),
    ('mock.exit-plan.forbid', '1', '接口仿真', 'bool', 0,
     '回归计划准出禁含 Mock 用例开关：1=禁（默认，仿真通过不得充当真实链路准出证据）；0=放行'),
    ('mock.llm-fallback.max-tokens-per-response', '2000', '接口仿真', 'int', 0,
     'AI 兜底单次响应最大 token 成本上限（超预算直接 miss，不烧钱）'),
    ('ai.mock.enabled', '1', 'AI 配置', 'bool', 0,
     'Mock 模块 AI 总闸：默认关；与 §7 各应用点分开关做与运算，任一关则该应用点不生效')
ON CONFLICT (config_key) DO NOTHING;
