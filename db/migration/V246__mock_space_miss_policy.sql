-- AG batch : space-level miss policy — the three-rung ladder from
-- the design (passthrough | reject | llm-fallback) becomes a saved per-space
-- choice instead of being derived only from "is a recording box open".
--
-- Why this file exists:
--   1) Until now a miss escalated to the proxy rung ONLY while a cassette box
--      was open, and to the AI rung whenever the gates allowed. Operators had
--      no honest way to say "this space always rejects misses with 404+trace"
--      or "always try the bound target". The policy column is that declaration.
-- 2) Default 'llm-fallback' reproduces the derived ladder exactly
--      (recording-proxy rung → gated AI rung → 404), so every existing space
--      keeps its served semantics without a data migration beyond the default.
--
-- Usage: docker exec -i -e PGPASSWORD=... testpike-postgres psql -U ... -d testpike -f - < this file
ALTER TABLE tp_mock_space
    ADD COLUMN IF NOT EXISTS miss_policy VARCHAR(20) NOT NULL DEFAULT 'llm-fallback';

COMMENT ON COLUMN tp_mock_space.miss_policy IS
    'What a no-stub-match request does (passthrough: forward to the bound target; reject: honest 404+trace only; llm-fallback: recording-proxy rung then gated AI rung then 404 — the derived pre-policy ladder)';

-- Side-door guard mirrors the core validator spectrum (0-100 chaos posture).
ALTER TABLE tp_mock_space DROP CONSTRAINT IF EXISTS ck_mock_space_miss_policy;
ALTER TABLE tp_mock_space
    ADD CONSTRAINT ck_mock_space_miss_policy
    CHECK (miss_policy IN ('passthrough', 'reject', 'llm-fallback'));
