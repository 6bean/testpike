-- =============================================================================
-- V509: Login exemption declaration slot (AV5, face 2)
--
-- CAPTCHA scenarios in systems whose source the team controls are cured at
-- the root: the environment itself drops the captcha, fixes a master code,
-- or lists code-exempt accounts. The platform only RECORDS that declaration
-- as one structured jsonb slot on the environment profile and later re-checks
-- it honestly — it never recognises, solves or bypasses a captcha (red line 1:
-- zero commitment on the adversarial surface). NULL = undeclared, so every
-- existing environment row and read path keeps its current shape (zero drift).
-- =============================================================================

ALTER TABLE tp_project_env
    ADD COLUMN IF NOT EXISTS login_exempt_json JSONB;

COMMENT ON COLUMN tp_project_env.login_exempt_json IS
    'Login exemption declaration (AV5): {"kind":"none|master_code|whitelist_accounts","credKey":...,"accounts":[...],"reviewMethod":"trial_login|manual","reviewStatus":"pending|passed|failed","reviewDetail":...,"reviewedAt":...}; the master-code value itself lives only in tp_credential (single vault) and is referenced by credKey here; NULL = undeclared';
