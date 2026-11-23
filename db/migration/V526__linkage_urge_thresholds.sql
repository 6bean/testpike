-- Migration: urge/escalation thresholds for the downstream-impact ledger .
-- Two sys_config keys drive LinkageUrgeScheduler: pending impacts older than
-- linkage.urge-hours remind the upstream object's creator (LINKAGE_URGE, the person
-- who owns the grouped card); older than linkage.escalate-hours ALSO notify the
-- project owners (LINKAGE_ESCALATE, forced subscription mirroring Q4 REVIEW_TIMEOUT).
-- 0 disables exactly that stage; both re-read per round (hot update, no restart).
-- Factory defaults 24 / 168 (1 day / 7 days) are pinned by the seed test.
-- Version note: chain and main trees were both at V525 when this segment opened
-- (live db/migration scan both sides). Idempotent replay: conflict-do-nothing keeps
-- re-application on live slots safe (Flyway disabled; manual replay per repo rule).

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('linkage.urge-hours',     '24',  '系统', 'int', 0, '联动影响催办阈值小时（待处置超过此时长提醒执行人，0=关闭该级）'),
    ('linkage.escalate-hours', '168', '系统', 'int', 0, '联动影响升级阈值小时（待处置超过此时长升级项目负责人，0=关闭该级）')
ON CONFLICT (config_key) DO NOTHING;
