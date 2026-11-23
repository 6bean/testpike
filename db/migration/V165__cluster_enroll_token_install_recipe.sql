
CREATE TABLE IF NOT EXISTS tp_cluster_enroll_token (
    id             BIGSERIAL    PRIMARY KEY,
    token_digest   VARCHAR(64)  NOT NULL,
    role           VARCHAR(20)  NOT NULL,
    project_scope  VARCHAR(200),
    max_uses       INT          NOT NULL DEFAULT 1,
    used_count     INT          NOT NULL DEFAULT 0,
    status         SMALLINT     NOT NULL DEFAULT 0,
    bound_fingerprint VARCHAR(200),
    expires_at     TIMESTAMP    NOT NULL,
    remark         VARCHAR(500),
    created_at     TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at     TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT uq_cluster_enroll_token_digest UNIQUE (token_digest),
    CONSTRAINT ck_cluster_enroll_token_role CHECK (role IN ('executor', 'ai-node', 'platform')),
    CONSTRAINT ck_cluster_enroll_token_status CHECK (status IN (0, 1, 2, 3))
);

COMMENT ON TABLE tp_cluster_enroll_token IS '入池一次性令牌（安全红线：只存摘要、一次一机、TTL+指纹绑定、永不持 SSH）';
COMMENT ON COLUMN tp_cluster_enroll_token.token_digest IS '令牌 sha256 hex 摘要（明文只在生成响应出现一次，禁入库禁入日志）';
COMMENT ON COLUMN tp_cluster_enroll_token.status IS '0=有效待用 1=已核销 2=已过期 3=已撤销（核销/过期流转在服务层）';
COMMENT ON COLUMN tp_cluster_enroll_token.bound_fingerprint IS '机器指纹（首用绑定，异机核销 fail-closed）';

ALTER TABLE tp_appauto_engine
    ADD COLUMN IF NOT EXISTS install_recipe JSONB NOT NULL DEFAULT '{}'::jsonb;

COMMENT ON COLUMN tp_appauto_engine.install_recipe IS ' 引擎×宿主安装配方（probe/install/uninstall/version_pin+manual_steps）；{}=未实装诚实缺省（全量）';

UPDATE tp_appauto_engine
SET install_recipe = '{
  "version_pin": "0.0.63",
  "hosts": {
    "windows": {
      "probe": ["node --version", "mobilewright --version"],
      "install": [{"cmd": "npm install -g mobilewright@0.0.63", "mirror_rewrite": "registry"}],
      "uninstall": [{"cmd": "npm uninstall -g mobilewright"}]
    },
    "macos": {
      "probe": ["node --version", "mobilewright --version"],
      "install": [{"cmd": "npm install -g mobilewright@0.0.63", "mirror_rewrite": "registry"}],
      "uninstall": [{"cmd": "npm uninstall -g mobilewright"}]
    },
    "linux": {
      "probe": ["node --version", "mobilewright --version"],
      "install": [{"cmd": "npm install -g mobilewright@0.0.63", "mirror_rewrite": "registry"}],
      "uninstall": [{"cmd": "npm uninstall -g mobilewright"}]
    }
  },
  "manual_steps": [
    "mobilecli 二进制需自行安装并在 PATH（testPike 不随包分发）",
    "Android 设备侧需 ADB；iOS 模拟器/真机需 macOS+Xcode 宿主",
    "建议先关闭 mobilewright 遥测再使用"
  ]
}'::jsonb,
    updated_at = now()
WHERE code = 'MOBILEWRIGHT' AND install_recipe = '{}'::jsonb;

UPDATE tp_appauto_engine
SET install_recipe = '{
  "version_pin": "1.6.1",
  "hosts": {
    "windows": {
      "probe": ["python -m pip show minium"],
      "install": [{"cmd": "python -m pip install minium==1.6.1", "mirror_rewrite": "index_url"}],
      "uninstall": [{"cmd": "python -m pip uninstall -y minium"}]
    },
    "macos": {
      "probe": ["python3 -m pip show minium"],
      "install": [{"cmd": "python3 -m pip install minium==1.6.1", "mirror_rewrite": "index_url"}],
      "uninstall": [{"cmd": "python3 -m pip uninstall -y minium"}]
    },
    "linux": {
      "probe": ["python3 -m pip show minium"],
      "install": [{"cmd": "python3 -m pip install minium==1.6.1", "mirror_rewrite": "index_url"}],
      "uninstall": [{"cmd": "python3 -m pip uninstall -y minium"}]
    }
  },
  "manual_steps": [
    "微信开发者工具无法静默安装：① 官方页下载并安装 ② 打开设置→安全→服务端口开关 ③ 扫码登录小程序账号",
    "安装后请在环境档案选择微信开发者工具安装路径（本机无法替你猜路径）",
    "连通探针=开发者工具服务端口 9420，recipe 消费方装机完成后自动验证"
  ]
}'::jsonb,
    updated_at = now()
WHERE code = 'MINIUM' AND install_recipe = '{}'::jsonb;

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES
    ('installkit.token-ttl-seconds', '3600', 'APP自动化', 'int', 0,
     '装机入池一次性令牌有效期秒（默认 1 小时；超时未核销的令牌 enroll 口拒；生成命令过期=页面重新生成一条即可）')
ON CONFLICT (config_key) DO NOTHING;
