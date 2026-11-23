-- W5 app automation phase 2, segment B1b: an env profile may bind one shared login recipe.
-- The nullable column references a tp_asset row of kind 'login_recipe'. Dispatch always
-- replays the CURRENT head version of that chain, so re-publishing the recipe takes effect
-- on the next dispatch without touching the profile row. Rows without a binding keep the
-- historical behavior (case steps only, nothing is prepended).
ALTER TABLE tp_appauto_env_profile
    ADD COLUMN IF NOT EXISTS login_recipe_asset_id BIGINT NULL;

COMMENT ON COLUMN tp_appauto_env_profile.login_recipe_asset_id IS
    'Optional reference to a shared login_recipe asset row; at dispatch the current chain head is resolved and its app.* steps are prepended before the case steps, then both run through the single credential-injection point (W5 phase-2 slice B1b)';

CREATE INDEX IF NOT EXISTS idx_appauto_env_profile_login_recipe
    ON tp_appauto_env_profile (login_recipe_asset_id)
    WHERE login_recipe_asset_id IS NOT NULL;
