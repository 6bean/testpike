-- Catalog tool wording for the layered domain index .
--
-- Context: an empty-keyword catalog browse that exceeds ai.api-access.catalog-max-rows now
-- delivers a per-domain index (prefix + count + drill hint) instead of a truncated segment of
-- details. The seed initializer (ApiAccessToolSeedInitializer) only registers missing rows --
-- it never overwrites existing ones -- so an already-seeded database would keep advertising the
-- old browse semantics to the model. This UPDATE rewrites the live catalog tool row's
-- description and input schema to the same wording the seed ships for fresh installs, matching
-- by intent rather than by a per-install id (the row is 26054 on the development database).
--
-- Flyway is not wired in this project: apply manually to the target database (DBA path in
-- production).

UPDATE tp_tool
   SET description = '检索本项目接口目录：列出可直读的接口条目、其参数与拦截原因；空词浏览条目过多时按域汇总交付索引（一行一域=前缀+条数+下钻方法），用域前缀作关键词即可下钻明细（对话代读的入口，不含任何写操作）',
       input_schema = '{"type":"object","properties":{"keyword":{"type":"string","description":"按接口路径、参数名或登记说明检索目录（留空=列出可直读条目；条目总数超过阈值时交付按域汇总的索引，把索引行中的域前缀填入 keyword 重新检索即可下钻该域明细）"},"onlyReachable":{"type":"boolean","description":"只列当前可直读的条目（缺省 true；false 时同时列出被拦条目及其拦截原因）"}}}'::jsonb,
       updated_at = now()
 WHERE exec_spec ->> 'intent' = 'apiAccess.catalog';
