-- Migration: login-state ledger keep-alive (XC batch).
-- Seven columns on tp_login_state drive a per-row opt-in sliding-window
-- keep-alive: a scheduler fires a plain GET with the row's vault material
-- so idle-timeout sessions do not expire while nobody uses them.
--   * keepalive_enabled is the per-row switch (default 0: outbound
--     periodic traffic against a foreign system always needs a named row),
--   * keepalive_url is the protected GET target the pre-check gates,
--   * keepalive_interval_minutes overrides the global cadence per row
--     (NULL = follow the global key),
--   * last_keepalive_at is the scheduler's pacing anchor,
--   * last_keepalive_verdict records VALID/EXPIRED/UNKNOWN/FAILED,
--   * keepalive_fail_streak drives the exponential backoff and the
--     circuit stop,
--   * keepalive_stop_reason names why the switch flipped itself off.
-- Four hot keys in tp_sys_config bound the global behaviour (factory
-- defaults mirrored by SessionGovernanceKeys; drift = seed contract red).
-- Absolute expiry (expires_at) is never rescued by keep-alive: due rows
-- past it are skipped with zero outbound requests. Rotation write-back,
-- automatic re-login and response-content assertions are explicitly out
-- of scope (design ruling section 8). Applied manually to the dev DB
-- (Flyway not enabled; db/migration is reference + replay source for
-- schema-init, regenerated via tools/ops/gen_schema_init.sh).

ALTER TABLE tp_login_state ADD COLUMN IF NOT EXISTS keepalive_enabled SMALLINT NOT NULL DEFAULT 0;
ALTER TABLE tp_login_state ADD COLUMN IF NOT EXISTS keepalive_url VARCHAR(500);
ALTER TABLE tp_login_state ADD COLUMN IF NOT EXISTS keepalive_interval_minutes SMALLINT;
ALTER TABLE tp_login_state ADD COLUMN IF NOT EXISTS last_keepalive_at TIMESTAMP;
ALTER TABLE tp_login_state ADD COLUMN IF NOT EXISTS last_keepalive_verdict VARCHAR(16);
ALTER TABLE tp_login_state ADD COLUMN IF NOT EXISTS keepalive_fail_streak SMALLINT NOT NULL DEFAULT 0;
ALTER TABLE tp_login_state ADD COLUMN IF NOT EXISTS keepalive_stop_reason VARCHAR(500);

COMMENT ON COLUMN tp_login_state.keepalive_enabled IS '该行保活开关：0=关闭（默认，新建行不产生周期流量），1=开启（调度器按间隔携带会话材料发送 GET）';
COMMENT ON COLUMN tp_login_state.keepalive_url IS '保活探测的受保护资源地址（http/https）；修改地址后需要重新开启预检';
COMMENT ON COLUMN tp_login_state.keepalive_interval_minutes IS '行级保活间隔（分钟，5~1440）；留空=跟随全局设置';
COMMENT ON COLUMN tp_login_state.last_keepalive_at IS '最近一次保活执行时刻（节奏锚点，连续异常时按 2 倍间隔退避、封顶 4 倍）';
COMMENT ON COLUMN tp_login_state.last_keepalive_verdict IS '最近一次保活判定：VALID/EXPIRED/UNKNOWN/FAILED';
COMMENT ON COLUMN tp_login_state.keepalive_fail_streak IS '连续 UNKNOWN/FAILED 轮数；达到上限后保活自动暂停（登录状态本身不受影响）';
COMMENT ON COLUMN tp_login_state.keepalive_stop_reason IS '保活自动暂停的原因与恢复入口说明';

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('session.keepalive.enabled', '1', '系统', 'int', 0, '登录态保活总开关（默认开启；关闭后所有行的周期保活全部停跑，保存即生效）'),
    ('session.keepalive.interval-minutes', '10', '系统', 'int', 0, '登录态保活的全局间隔（分钟，可设 5~1440，默认 10；行级设置可单独覆盖，保存即生效）'),
    ('session.keepalive.max-per-tick', '50', '系统', 'int', 0, '单个调度轮最多执行的保活探测条数（可设 1~500，默认 50；超出条数顺延到下一轮）'),
    ('session.keepalive.max-fail-streak', '6', '系统', 'int', 0, '连续异常轮数达到该值后该行保活自动暂停（可设 1~20，默认 6；只暂停保活，不影响登录状态本身）')
ON CONFLICT (config_key) DO NOTHING;
