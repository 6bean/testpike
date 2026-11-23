
CREATE TABLE IF NOT EXISTS tp_appauto_platform (
    id                 BIGSERIAL    PRIMARY KEY,
    code               VARCHAR(64)  NOT NULL,
    name               VARCHAR(200) NOT NULL,
    device_kind_codes  JSONB        NOT NULL DEFAULT '[]'::jsonb,
    carrier_types      JSONB        NOT NULL DEFAULT '[]'::jsonb,
    note               VARCHAR(500),
    status             SMALLINT     NOT NULL DEFAULT 1,
    created_at         TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at         TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT uq_appauto_platform_code UNIQUE (code),
    CONSTRAINT ck_appauto_platform_status CHECK (status IN (0, 1))
);

COMMENT ON TABLE tp_appauto_platform IS '平台描述符目录（加平台=插行，WebView 混合不是新平台=载体旗标）';
COMMENT ON COLUMN tp_appauto_platform.name IS '展示名（i18n 键口径，前端 appauto 视图 消费）';
COMMENT ON COLUMN tp_appauto_platform.carrier_types IS '应用载体类型：APK/IPA/HAP/小程序 appid+projectPath/快应用 hap+scheme';
COMMENT ON COLUMN tp_appauto_platform.status IS '1=启用 0=预留停用（预留位不出现在建档可选面）';

INSERT INTO tp_appauto_platform (code, name, device_kind_codes, carrier_types, note, status)
VALUES
('ANDROID', 'Android', '["EMULATOR","REAL"]'::jsonb, '["apk"]'::jsonb,
 'AOSP 兼容鸿蒙设备请选本平台并按设备档案 device_type 防撞（独立鸿蒙 NEXT 引擎走 HARMONY_NEXT 平台）', 1),
('IOS', 'iOS', '["SIMULATOR","REAL"]'::jsonb, '["ipa"]'::jsonb,
 'iOS 族引擎全部锁 macOS 宿主（fleet 能力路由按此过滤，§1.3 矩阵推论 1）', 1),
('MP', '微信小程序', '["DEVTOOLS_IDE","EMULATOR","SIMULATOR","REAL"]'::jsonb, '["appid+project_path"]'::jsonb,
 '开发者工具形态仅 Mac/Win；发布渠道字段归应用档案 T299 消费', 1),
('HARMONY_NEXT', 'HarmonyOS NEXT', '["EMULATOR","REAL"]'::jsonb, '["hap"]'::jsonb,
 'API12+ 纯血鸿蒙；老鸿蒙（AOSP 兼容）走 ANDROID 平台引导，不另建行', 1),
('QUICKAPP', '快应用', '["EMULATOR","REAL"]'::jsonb, '["hap+scheme"]'::jsonb,
 '载体=hap 包+scheme 拉起，渲染走厂商快应用引擎无障碍树；厂商 ROM 碎片化=限时实证口径', 1),
('WEB_MOBILE', '移动 Web（预留）', '[]'::jsonb, '[]'::jsonb, NULL, 0),
('TV', '智能电视（预留）', '[]'::jsonb, '[]'::jsonb, NULL, 0)
ON CONFLICT (code) DO NOTHING;

ALTER TABLE tp_appauto_app
    ADD COLUMN IF NOT EXISTS platform_code VARCHAR(64);

COMMENT ON COLUMN tp_appauto_app.platform_code IS '平台描述符引用（tp_appauto_platform.code， 末注补列；历史行回填随应用档案写面刀接线）';

CREATE INDEX IF NOT EXISTS ix_appauto_app_platform ON tp_appauto_app (platform_code);
