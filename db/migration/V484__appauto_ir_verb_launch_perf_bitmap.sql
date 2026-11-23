-- W5 phase-two B6: grant every engine row the cold/hot launch-timing verb.
-- The launch sample is recorded on the executor host through the adb channel
-- (executor/driver/mobile/launch_perf.py); engines whose device lane has no
-- adb access fail the step honestly instead of fabricating a reading, so the
-- bit is a truthful best-effort grant — standby rows included (V483 precedent).
-- Idempotent jsonb merge: replaying this migration twice changes nothing.
UPDATE tp_appauto_engine
   SET capabilities = capabilities || '{"launchPerf": true}'::jsonb,
       updated_at   = now();
