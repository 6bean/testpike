-- Migration: W5 phase-two B8 — POS/PDA device group for the retail edge fleet.
-- Semantics:
--   1) tp_appauto_device.device_type value range grows POS and PDA. The existing
--      three values name the access channel (EMULATOR/ADB/USB); POS/PDA name the
--      device class of Android-based checkout terminals and inventory handhelds
--      that share the same app_worker channel as phones (design 5.11 B8). Group
--      membership is carried by tp_appauto_device.labels (plan/suite binds a
--      label group, never a single machine; failed runs retry on another member).
--   2) tp_appauto_platform gains POS and PDA active rows. The platform whitelist
--      is data-driven (adding a platform = inserting a row; AppautoPlatformWhitelist
--      consumes the status=1 face with zero code change). device_kind_codes=
--      ["REAL"] keeps matrix fan-out on the physical-device kind; carrier_types=
--      ["apk"] reflects the Android runtime of these edge fleets.
-- Idempotent: constraint re-created via DROP IF EXISTS, seeds ON CONFLICT DO NOTHING.
-- Execution: manual psql against the chain slot database (Flyway not enabled).
-- Usage: psql "$DB_URL" -f db/migration/V485__appauto_pos_device_group.sql

ALTER TABLE tp_appauto_device
    DROP CONSTRAINT IF EXISTS ck_appauto_device_type;

ALTER TABLE tp_appauto_device
    ADD CONSTRAINT ck_appauto_device_type
    CHECK (device_type IN ('EMULATOR', 'ADB', 'USB', 'POS', 'PDA'));

COMMENT ON CONSTRAINT ck_appauto_device_type ON tp_appauto_device IS
    'Device registration value range: EMULATOR/ADB/USB = access channel, POS/PDA = retail edge device class (design 5.11 B8)';

INSERT INTO tp_appauto_platform (code, name, device_kind_codes, carrier_types, note, status)
VALUES
('POS', 'POS 收银终端', '["REAL"]'::jsonb, '["apk"]'::jsonb,
 '零售定制收银终端；应用载体与手机同走 APK 通道，机群用设备标签圈组绑定', 1),
('PDA', '手持 PDA', '["REAL"]'::jsonb, '["apk"]'::jsonb,
 '仓储/门店盘点手持终端；应用载体与手机同走 APK 通道，机群用设备标签圈组绑定', 1)
ON CONFLICT (code) DO NOTHING;
