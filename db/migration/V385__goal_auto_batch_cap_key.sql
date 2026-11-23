-- Safety cap of the autonomous goal-plan continuation . When a chat
-- turn finishes with the goal ledger still owing open steps, the run picks
-- up the next batch on its own instead of asking the user to type a resume
-- word; at most this many auto batches per turn before it stops and reports
-- the remaining steps honestly. Range 1~10 guarded on the core write side;
-- absent, garbage or out-of-range values fall back to the default 3 on the
-- read side. The manual trigger word keeps working as the fallback door.
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES ('ai.chat.goal.max-auto-batches', '3', '系统', 'int', 0,
        '单次对话自主接续目标计划的最大批次数（默认 3，区间 1~10；达到上限即停止并如实汇报剩余步骤，可发送「继续」手动接续）')
ON CONFLICT (config_key) DO NOTHING;
