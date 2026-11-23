-- Migration: realign the catalog delivery ceiling with the live serving standard.
--
-- Why: the api pass-through catalog door truncates any keyword browse to
--   ai.api-access.catalog-max-rows entries in key order, so the shipped 120 hid
--   every endpoint sorting past the 120th key — including GET /internal/projects
--   the entry they need. The live deployment was steered to 2000 (2026-11-07)
--   without ever landing a seed migration, which left fresh installs and chain
--   slots on the too-small default and drifted the three sources (live DB,
--   migration seed, shipped init artifact) apart. This file moves the factory
--   default to the serving standard so new slots recall the full catalog; the
--   ceiling itself stays hot-updatable on the settings page (parameter
-- Idempotent: the guard clause makes a re-run a no-op (no updated_at churn).
-- Execution: manual psql (Flyway not enabled):
--   docker exec -i testpike-postgres psql -U *** -d <db> -f - < this file
--   then re-run scripts/gen_schema_init.sh to fold the seed into the init artifact.

UPDATE tp_sys_config
   SET config_value = '2000',
       description  = '接口目录一次交付的条目数（默认 2000；超出提示带 keyword 精确检索）',
       updated_at   = now()
 WHERE config_key = 'ai.api-access.catalog-max-rows'
   AND (config_value IS DISTINCT FROM '2000'
        OR description IS DISTINCT FROM '接口目录一次交付的条目数（默认 2000；超出提示带 keyword 精确检索）');
