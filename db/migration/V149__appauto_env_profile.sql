
CREATE TABLE IF NOT EXISTS tp_appauto_env_profile (
    id           BIGSERIAL    PRIMARY KEY,
    project_id   BIGINT       NOT NULL REFERENCES tp_project (id),
    name         VARCHAR(200) NOT NULL,
    profile_kind VARCHAR(32)  NOT NULL,
    payload      JSONB        NOT NULL DEFAULT '{}'::jsonb,
    remark       VARCHAR(500),
    created_at   TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at   TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT uq_appauto_env_profile_name UNIQUE (project_id, name),
    CONSTRAINT ck_appauto_env_profile_kind CHECK (profile_kind IN
        ('MP_DEVTOOLS', 'ANDROID_SDK', 'IOS_SIMULATOR', 'HARMONY_HDC'))
);

COMMENT ON TABLE tp_appauto_env_profile IS '环境档案库（机器环境项目级可复用，多用例共享改一处全生效）';
COMMENT ON COLUMN tp_appauto_env_profile.profile_kind IS '档案型白名单：MP_DEVTOOLS(微信开发者工具)/ANDROID_SDK/IOS_SIMULATOR/HARMONY_HDC（加型=扩 CHECK+服务层白名单，禁字典表）';
COMMENT ON COLUMN tp_appauto_env_profile.payload IS '机器环境事实 jsonb（键随型而异：appid/projectPath/cliPath/autoPort/sdkRoot/adbPath/simulatorUdid/hdcPath…），扩展字段走本列禁加列';
COMMENT ON COLUMN tp_appauto_env_profile.remark IS '备注（如登录态/发布渠道注意事项，- 行「登录探针」提示位消费）';

CREATE INDEX IF NOT EXISTS ix_appauto_env_profile_project
    ON tp_appauto_env_profile (project_id, profile_kind);
