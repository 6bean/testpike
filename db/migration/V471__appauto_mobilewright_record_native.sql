-- Flip the native recording capability bit for the Mobilewright engine.
-- The adapter self-reports executed action frames through its record_events
-- hook (recording chain level 1), so a recording session now resolves to
-- "native" instead of the scrcpy fallback, which samples adb getevent kernel
-- events and is physically blind to framework-level input injections.
UPDATE tp_appauto_engine
   SET capabilities = jsonb_set(capabilities, '{record-native}', 'true'::jsonb, false)
 WHERE code = 'MOBILEWRIGHT'
   AND capabilities ->> 'record-native' IS DISTINCT FROM 'true';
