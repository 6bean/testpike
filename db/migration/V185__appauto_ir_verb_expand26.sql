UPDATE tp_appauto_engine SET capabilities = capabilities || '{
  "deepLink": true, "openUrl": true, "home": true, "clearData": true,
  "grantPermission": true, "wakeUnlock": true, "rotation": true,
  "background": false, "foreground": false, "networkProfile": false
}'::jsonb, updated_at = now()
WHERE code IN ('MOBILEWRIGHT','MAESTRO','APPIUM','HMNEXTAUTO');

UPDATE tp_appauto_engine SET capabilities = capabilities || '{
  "deepLink": true, "openUrl": true, "home": false, "clearData": false,
  "grantPermission": false, "wakeUnlock": false, "rotation": false,
  "background": false, "foreground": false, "networkProfile": true
}'::jsonb, updated_at = now()
WHERE code IN ('MINIUM','MINIPROGRAM_AUTOMATOR');

UPDATE tp_appauto_engine SET capabilities = capabilities || '{
  "deepLink": true, "openUrl": true, "home": false, "clearData": false,
  "grantPermission": true, "wakeUnlock": false, "rotation": false,
  "background": false, "foreground": false, "networkProfile": true
}'::jsonb, updated_at = now()
WHERE code = 'XCUITEST';
