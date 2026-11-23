-- Migration: realign the help-document version description with its shipped value.
--
-- Why this file exists:
--   The M7-era help rewrite (V91) raised the seeded value of help.doc-version
--   to 2.42 but only touched config_value — the settings-page description kept
-- advertising "default 2.40". The wording-drift scan caught
--   it as the one remaining factory-side contradiction: both the live database
--   and the fresh-install replay now end at value 2.42 with a stale 2.40 claim.
--   Operators read the description as the factory default, so the description
--   follows the value here; the value itself stays untouched.
--   Idempotent: the guard clause makes a re-run a no-op (no updated_at churn).
-- Execution: manual psql (Flyway not enabled):
--   docker exec -i testpike-postgres psql -U <user> -d <db> -f - < this file
--   then re-run scripts/gen_schema_init.sh to fold the seed into the init artifact.

UPDATE tp_sys_config
   SET description = '帮助文档全局版本（默认 2.42；热更即时生效）',
       updated_at  = now()
 WHERE config_key = 'help.doc-version'
   AND description IS DISTINCT FROM '帮助文档全局版本（默认 2.42；热更即时生效）';
