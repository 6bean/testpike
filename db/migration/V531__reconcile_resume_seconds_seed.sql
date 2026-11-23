-- Migration: seed the reconcile-round recovery window key (D2 defect ledger,
-- option C of the recovery-channel adjudication).
--
-- Why this file exists:
--   A grouped extraction whose task deadline (ai.doc.extract-deadline-seconds)
--   dies mid reconcile loop used to bleed out silently: every further round
--   failed the budget clamp instantly, the loop exited with pending gap rows
--   in the coverage ledger, and NO face of the pipeline ever ran the booked
--   rounds again (live 9071: after 11:42:10 zero tp_ai_call_log rows, the
--   preview page only kept flagging 96 uncovered blocks). The sweeper cannot
--   see it (it scans status=3, these runs finish at status=4) and a re-publish
--   is absorbed by the idempotency lock — a structural gap, not a race.
--   The recovery face: when the reconcile loop exits on a spent window while
--   the ledger still has pending rounds, it re-anchors the window ONCE (same
--   generation, same round quota, one resume max) and finishes the booked
--   rounds. Value 0 turns the recovery off (legacy bleed-out, byte-identical);
--   the write guard (validateE8CompressionKey) accepts 0 or 300~3600 only.
--   Guard rail (invariant, spelled in the description): the resume window must
--   stay well under the delivery-ack headroom (ack window = 2x deadline,
--   D1 ledger), so a resume can never reintroduce the 406 near-miss D1 fixed.
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.doc.extract-reconcile-resume-seconds', '1800', '系统', 'string', 0, '复核轮超时后允许再续一轮的窗口秒数（默认 1800，允许 300~3600；填 0 表示不续。需求点提取的时长预算耗尽时，已挂账的复核轮次可在此窗口内续跑一轮，剩余轮次照常推进。超出区间的取值会被拒绝并提示允许范围。保存即生效，无需重启）')
ON CONFLICT (config_key) DO NOTHING;
