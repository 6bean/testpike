UPDATE tp_appauto_engine SET capabilities = capabilities || '{
  "perf-collect": true
}'::jsonb, updated_at = now()
WHERE code IN ('MOBILEWRIGHT','MAESTRO','APPIUM','XCUITEST');

UPDATE tp_appauto_engine SET capabilities = capabilities || '{
  "perf-engine-native": true
}'::jsonb, updated_at = now()
WHERE code = 'HMNEXTAUTO';
