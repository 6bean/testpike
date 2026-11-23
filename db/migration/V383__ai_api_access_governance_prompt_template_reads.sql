-- Governance overlay rows that let the dialog read prompt templates through the pass-through door.
--
-- Verdict (registered by a human review, which is what this table is for): every template family
-- in tp_prompt_template is a shared system asset — the same rows the prompt-template page shows to
-- any logged-in operator, with no credential material inside (the value is the prompt text itself).
-- Reads therefore open per entry; the write side stays as it is: POST entries keep requiring a
-- confirmation card, the change-landing switch ai.api-access.write.enabled and their own registered
-- rows before anything lands.
--
-- The "families" key is the enumeration read (GET /internal/prompt-templates/families): without it
-- a reader can only query one known name+domain at a time and can never answer "which template
-- domains and names exist right now".
--
-- Flyway is not wired in this project: apply manually to the target database (DBA path in
-- production), then regenerate db/schema-init.sql with tools/ops/gen_schema_init.sh.

INSERT INTO tp_ai_api_access_governance
    (endpoint_key, scope_verified, reachable, ai_desc, exclude_reason)
VALUES
    ('GET /internal/prompt-templates/families', 1, 1,
     '列出全部提示词模板族：每个业务域(domain)+模板名(name)一行，含最新版本号、版本状态（1=灰度 2=全量 3=历史）与版本数；想读某族正文再用 versions/latest 条目。',
     NULL),
    ('GET /internal/prompt-templates/versions', 1, 1,
     '读一个模板族的全部版本列表（按版本号倒序），含模板正文；族名=业务域(domain)+模板名(name)，先用 families 条目确认有哪些族。',
     NULL),
    ('GET /internal/prompt-templates/latest', 1, 1,
     '读一个模板族的最新版本行（含模板正文）；需要精确的业务域(domain)+模板名(name)两个参数。',
     NULL),
    ('GET /internal/prompt-templates/latest-by-status', 1, 1,
     '读一个模板族指定状态的最新版本（1=灰度 2=全量 3=历史）；该状态无行时返回空。',
     NULL),
    ('GET /internal/prompt-templates/{id}', 1, 1,
     '按模板行 id 读单个版本行（含模板正文与创建时间）；id 来自 families 或 versions 条目。',
     NULL)
ON CONFLICT (endpoint_key) DO NOTHING;
