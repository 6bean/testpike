UPDATE tp_appauto_engine SET capabilities = capabilities || '{
  "install": true, "uninstall": true, "verifyVersion": true
}'::jsonb, updated_at = now()
WHERE code IN ('MOBILEWRIGHT','MAESTRO','APPIUM');

UPDATE tp_appauto_engine SET capabilities = capabilities || '{
  "install": true, "uninstall": true, "verifyVersion": false
}'::jsonb, updated_at = now()
WHERE code IN ('HMNEXTAUTO','XCUITEST');

UPDATE tp_appauto_engine SET capabilities = capabilities || '{
  "install": false, "uninstall": false, "verifyVersion": false
}'::jsonb, updated_at = now()
WHERE code IN ('MINIUM','MINIPROGRAM_AUTOMATOR');
