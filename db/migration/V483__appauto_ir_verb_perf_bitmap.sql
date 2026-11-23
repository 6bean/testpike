-- W5 phase-two B6: grant every engine row the host-side perf assertion verb.
-- The app.perf verdict is computed on the executor host from the perf sampling
-- session rows (executor/driver/mobile/perf_assert.py), so it is engine-
-- independent by construction — standby rows included, truthful best-effort
-- bit. Idempotent jsonb merge: replaying this migration twice changes nothing.
UPDATE tp_appauto_engine
   SET capabilities = capabilities || '{"perf": true}'::jsonb,
       updated_at   = now();
