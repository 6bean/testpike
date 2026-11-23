-- Release artifact supply chain (AF batch S5: from "knowing there is a new
-- version" to "actually being able to upgrade" — no package, no one-click).
-- tp_release_manifest gains three columns:
--   artifacts_jsonb   per-component download entries [{component_family,url,sha256,size}];
--                     empty array = "announce-only" release (upgrade button greys out,
--                     never a hard break for intranet/manual deployments).
--   release_notes_md  human-readable release notes (markdown, changelog drawer source).
--   published_at      release publish timestamp (defaults to the registration instant;
--                     import feeds may overwrite it with the upstream publish time).
-- New K8 key testpike.cluster.artifact-allowed-hosts: CSV of hosts whose artifact
-- URLs the platform may fetch from (SSRF/supply-chain gate — payload URLs are
-- never followed outside this list; the installkit.mirror-base-url host is
-- always additionally allowed at read time for mirror re-routing).
-- Manual execution (Flyway not enabled):
--   docker exec -i testpike-postgres psql -U testpike -d testpike < db/migration/V223__release_artifact_supply.sql
-- After landing the new key, rebuild the config snapshot (pit 21 bookkeeping
-- defence): python3 scripts/tmp_m8p4_build_snapshot.py

ALTER TABLE tp_release_manifest
    ADD COLUMN IF NOT EXISTS artifacts      JSONB     NOT NULL DEFAULT '[]',
    ADD COLUMN IF NOT EXISTS release_notes_md TEXT,
    ADD COLUMN IF NOT EXISTS published_at   TIMESTAMP NOT NULL DEFAULT now();

ALTER TABLE tp_release_manifest
    DROP CONSTRAINT IF EXISTS ck_release_manifest_artifacts;
ALTER TABLE tp_release_manifest
    ADD CONSTRAINT ck_release_manifest_artifacts CHECK (jsonb_typeof(artifacts) = 'array');

COMMENT ON COLUMN tp_release_manifest.artifacts IS 'Download artifacts jsonb array ([{component_family,url,sha256,size}]; empty = announce-only, pinned matrix items require their artifact)';
COMMENT ON COLUMN tp_release_manifest.release_notes_md IS 'Release notes (markdown, human wording; changelog drawer content source)';
COMMENT ON COLUMN tp_release_manifest.published_at IS 'Publish time (registration instant by default; upstream feeds may stamp the real publish time)';

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES
    ('testpike.cluster.artifact-allowed-hosts', 'github.com,objects.githubusercontent.com', '机群与升级', 'string', 0,
     '发版工件下载白名单主机（逗号分隔；命中域之外的 artifacts.url 登记即拒，堵 SSRF/供应链替换；镜像改道键 installkit.mirror-base-url 的域自动并入白名单，改键即生效）')
ON CONFLICT (config_key) DO NOTHING;
