-- Migration: WU batch - site profile ledger and whole-site survey run ledger.
-- tp_site_profile keeps one profile per project and per in-site path scope: the
-- scope_prefix column is derived server-side from start_url (segment aligned,
-- never trusted from the client), so two sites on one host (/pr/ and /md/) keep
-- separate scopes and a re-survey reuses the stored rules instead of re-typing
-- them. tp_site_survey_run keeps one row per whole-site survey dispatch with
-- the honest counters (pages, links, out-of-scope, sibling segments, excluded,
-- ledger writes, naming fallbacks, failure notes). profile_id carries no
-- foreign key on purpose: run history and already-collected elements outlive a
-- deleted profile row (adjudication D7 - delete keeps the ledger).
-- Flyway is not enabled; this file is the replay source, applied manually to
-- the dev and chain-slot databases.
CREATE TABLE IF NOT EXISTS tp_site_profile (
    id                  BIGSERIAL PRIMARY KEY,
    project_id          BIGINT        NOT NULL REFERENCES tp_project (id),
    name                VARCHAR(100)  NOT NULL,
    start_url           VARCHAR(500)  NOT NULL,
    scope_prefix        VARCHAR(200)  NOT NULL,
    exclude_globs       JSONB         NOT NULL DEFAULT '[]'::jsonb,
    ignore_params       JSONB         NOT NULL DEFAULT '[]'::jsonb,
    verbs_guard_enabled BOOLEAN       NOT NULL DEFAULT TRUE,
    budget_overrides    JSONB         NOT NULL DEFAULT '{}'::jsonb,
    login_state_name    VARCHAR(100),
    enabled             BOOLEAN       NOT NULL DEFAULT TRUE,
    revision            INT           NOT NULL DEFAULT 0,
    created_at          TIMESTAMP     NOT NULL DEFAULT now(),
    updated_at          TIMESTAMP     NOT NULL DEFAULT now(),
    CONSTRAINT uk_site_profile_project_name UNIQUE (project_id, name)
);

CREATE INDEX IF NOT EXISTS idx_site_profile_project
    ON tp_site_profile (project_id, updated_at DESC);

COMMENT ON TABLE tp_site_profile IS 'Whole-site survey profile: one project x one in-site path scope; carries exclusion globs, ignored params, verb guard, budget overrides and login state binding by name.';
COMMENT ON COLUMN tp_site_profile.scope_prefix IS 'Server-derived segment-aligned path scope (e.g. /pr/; a leading /pr prefix never swallows /print; root scope = whole host). The survey frontier only queues URLs inside it.';
COMMENT ON COLUMN tp_site_profile.login_state_name IS 'Name of the saved login state used for the survey; only the name is stored, no credentials.';

CREATE TABLE IF NOT EXISTS tp_site_survey_run (
    id                  BIGSERIAL PRIMARY KEY,
    profile_id          BIGINT        NOT NULL,
    project_id          BIGINT        NOT NULL REFERENCES tp_project (id),
    explore_session_id  BIGINT,
    status              VARCHAR(20)   NOT NULL DEFAULT 'running',
    pages_total         INT           NOT NULL DEFAULT 0,
    pages_ok            INT           NOT NULL DEFAULT 0,
    pages_failed        INT           NOT NULL DEFAULT 0,
    links_seen          INT           NOT NULL DEFAULT 0,
    out_of_scope        INT           NOT NULL DEFAULT 0,
    sibling_counts      JSONB         NOT NULL DEFAULT '{}'::jsonb,
    excluded            INT           NOT NULL DEFAULT 0,
    ledger_upserted     INT           NOT NULL DEFAULT 0,
    naming_degraded     INT           NOT NULL DEFAULT 0,
    failure_notes       JSONB         NOT NULL DEFAULT '[]'::jsonb,
    started_at          TIMESTAMP     NOT NULL DEFAULT now(),
    finished_at         TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_site_survey_run_profile
    ON tp_site_survey_run (profile_id, started_at DESC);
CREATE INDEX IF NOT EXISTS idx_site_survey_run_project
    ON tp_site_survey_run (project_id, started_at DESC);

COMMENT ON TABLE tp_site_survey_run IS 'One row per whole-site survey dispatch with honest page/link counters and failure notes; status is running / completed / partial / failed / cancelled.';
COMMENT ON COLUMN tp_site_survey_run.profile_id IS 'Profile this run was dispatched from. Deliberately no foreign key: deleting a profile keeps its run history (adjudication D7).';
COMMENT ON COLUMN tp_site_survey_run.sibling_counts IS 'JSON object mapping other top-level path segments on the same host to the number of links found there; those links are counted, never queued.';

-- Hot-updatable knobs of the whole-site survey family (idempotent replay;
-- tp_sys_config is the live source). The master switch ships enabled
-- (R32 posture for AI-driven features); the production-domain gate ships
-- closed and opening it must be an explicit operator decision.
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('element.survey-site.enabled', 'true', '系统', 'bool', 0, '整站测绘功能总开关（默认开启；关闭时发起入口会说明在哪里重新开启，单页测绘不受影响）'),
    ('element.survey-site.allow-prod', 'false', '系统', 'bool', 0, '是否允许对生产域名发起整站测绘（默认关闭：命中生产域名名单时发起会被拦下并列出命中的域名；确认风险后可开启）'),
    ('element.survey-site.max-pages', '100', '系统', 'int', 0, '单次整站测绘最多采集的页面数（默认 100，可设 1~2000，保存即生效；达到上限时本次记录为部分完成，已入账元素保留）'),
    ('element.survey-site.max-depth', '6', '系统', 'int', 0, '链接递归的最大深度（默认 6 层，可设 1~50，保存即生效；超过该层的链接不再排队）'),
    ('element.survey-site.interval-ms', '800', '系统', 'int', 0, '相邻两个页面打开之间的间隔毫秒数（默认 800，可设 0~60000，保存即生效；间隔用于降低对被测网站的访问压力）'),
    ('element.survey-site.deadline-seconds', '1800', '系统', 'int', 0, '单次整站测绘的总时限秒数（默认 1800，可设 60~86400，保存即生效；到时本次记录为部分完成并保留已采集结果）'),
    ('element.survey-site.max-links-per-page', '60', '系统', 'int', 0, '每个页面最多收集的链接数（默认 60，可设 1~500，保存即生效；超出部分的链接不排队）'),
    ('element.survey-site.per-template-cap', '1', '系统', 'int', 0, '同类页面的采集上限（默认 1，可设 1~20，保存即生效；折叠为同一模版的页面只采集前 N 个具体地址，列表翻页不再重复采集）'),
    ('element.survey-site.default-exclude-verbs', '["退出","注销","删除","移除","清空","导出","下载","unbind","logout","delete","remove","clear","export","download"]', '系统', 'string', 0, '内置动词排除表：链接路径或锚文本命中其中任一词语时，该页面不会被访问（档案级可关闭或增补；被排除的链接逐条计数，可在测绘记录里查看）'),
    ('element.survey-site.default-ignore-params', '["page","pageNum","pageNo","size","limit","offset","timestamp","_t","rand"]', '系统', 'string', 0, '链接归一时忽略的参数名：地址只差这些翻页/时间参数时视为同一个页面（档案级可修改清单）'),
    ('element.survey-site.tail-cap', '400', '系统', 'int', 0, '「进行中」过程窗保留的事件条数（默认 400，可设 50~5000，保存即生效；超出后最早的过程事件不再显示，入账结果不受影响）'),
    ('element.survey-site.history-frame-cap', '2000', '系统', 'int', 0, '每条测绘记录落库的过程事件条数上限（默认 2000，可设 200~20000，保存即生效；超出时中间段截尾并在回放里注明被截条数）'),
    ('element.survey-site.event-retention-days', '90', '系统', 'int', 0, '过程事件明细保留天数（默认 90，可设 7~730，保存即生效；超期后旧明细被清除，回放入口会说明保留期限与调整位置）')
ON CONFLICT (config_key) DO NOTHING;
