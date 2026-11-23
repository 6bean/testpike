--   docker exec -i testpike-postgres psql -U testpike -d testpike < db/migration/V171__appauto_quickapp_platform_bits.sql

UPDATE tp_appauto_engine
SET platform_codes = platform_codes || '["QUICKAPP"]'::jsonb,
    updated_at = now()
WHERE code IN ('MOBILEWRIGHT', 'APPIUM', 'MAESTRO')
  AND status = 1
  AND NOT platform_codes ? 'QUICKAPP';
