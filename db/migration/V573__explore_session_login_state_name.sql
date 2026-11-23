-- Explore channel namespace split: the login-state ledger handle gets its own
-- column instead of riding cred_ref (a vault cred_key namespace). Historical
-- rows never resolved through cred_ref as a state name (the claim side looks up
-- tp_login_state.state_name only), so there is no misaligned data to migrate —
-- the new column starts NULL for every existing session (= anonymous explore,
-- unchanged behaviour).
ALTER TABLE tp_explore_session ADD COLUMN IF NOT EXISTS login_state_name VARCHAR(200);

COMMENT ON COLUMN tp_explore_session.login_state_name IS
    '登录态账本句柄（tp_login_state.state_name，web 探索形态申报；留空则探索以匿名身份运行；与测试账号凭据引用是两个不同字段）';
