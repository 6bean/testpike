-- AP-4 (prefix stabilization): per-model cache hint switch and the
-- provider-reported cached prompt tokens carried on every usage sample.
-- tp_llm_config.cache_hint: 1 = the caller may attach provider prompt-cache
-- hints on multi-turn requests (factory default ON per the AI-feature rule:
-- ships enabled, zero configuration; a provider that does not support cache
-- hints simply ignores them, so there is no failure mode to guard). 0 = never
-- send hints for this model (opt-out row, auditable).
-- tp_llm_usage_sample.cached_tokens: number of prompt tokens the provider
-- served from its prefix cache for this call (usage.prompt_tokens_details
-- .cached_tokens; absent block reads an honest 0). The E9 usage calibration
-- page shows the before/after difference of this column, so the AP-6 benefit
-- is measured from real traffic instead of promised upfront.
ALTER TABLE tp_llm_config ADD COLUMN IF NOT EXISTS cache_hint SMALLINT NOT NULL DEFAULT 1;
COMMENT ON COLUMN tp_llm_config.cache_hint IS
    'Prompt-cache hint switch for this model (1 = allowed, factory default; 0 = opt out). Controls request-side cache hints only; never bypasses the write-confirmation gate.';

ALTER TABLE tp_llm_usage_sample ADD COLUMN IF NOT EXISTS cached_tokens INTEGER NOT NULL DEFAULT 0;
COMMENT ON COLUMN tp_llm_usage_sample.cached_tokens IS
    'Prompt tokens served from the provider prefix cache for this call (usage.prompt_tokens_details.cached_tokens; 0 = none reported).';
