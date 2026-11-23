-- Migration: seed the two long-tail removal keys for grouped raw extraction
-- , and align the fan-out row's description with the
-- write-side interval guard added in the same segment.
--
-- Why this file exists:
-- segment 1 taught the grouped raw-plane loop to converge a failing group's
--   inline retries to one bound and to move the removed groups into a bounded
--   deferred re-submission round. Both bounds were hard-coded; the AY ruling
--   requires every tuned parameter to ride a K8 hot key so an operator can
--   retune the long-tail strategy without a restart (and without burning the
--   whole task deadline on one stubborn group). The bounds come from the
-- worst-case ledger (per-call p90 224.6s / max 901.6s against the
--   untouched 7200s deadline), so their accepted interval is 0~2 with factory
--   1 — the reader clamps and the new ConfigServiceImpl guard rejects beyond
--   cap, because raising a cap is an adjudicated change, never a config edit.
--   Keys live in the cross-domain ai.pipeline.* namespace (AY redline 5:
--   never borrowed into ai.doc.*), value_type stays 'string' to match the
--   V529 sibling row.
--   The second UPDATE rewords the V529 fan-out description: with the interval
--   guard in place, an above-cap value is now refused at save time with the
--   allowed range spelled out, so the old "silently clamped back" wording no
--   longer matches what the config page does (IS DISTINCT FROM keeps replays
--   idempotent — zero write when the row already carries the new text).
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.pipeline.group-inline-retry-limit', '1', '系统', 'string', 0, '分组提取中单个分组调用失败后就地重试的次数（默认 1，允许 0~2；设为 0 表示失败一次即将该分组摘出热循环、转入延后重提轮，摘出的分组不会拖累其余已落账分组的重复调用。超出区间的取值会被拒绝并提示允许范围。保存即生效，无需重启）'),
    ('ai.pipeline.group-deferred-rounds', '1', '系统', 'string', 0, '长尾分组延后重提的最大轮数（默认 1，允许 0~2；热循环中被摘出的分组在独立轮次里重新提交的最大次数，设为 0 表示不再重提、缺口直接进入覆盖账。超出区间的取值会被拒绝并提示允许范围。保存即生效，无需重启）')
ON CONFLICT (config_key) DO NOTHING;

UPDATE tp_sys_config
SET description = '文档与生成任务分组并行调用数（默认 3，允许 1~3；提取、契约生成、出题三类任务的每组大模型调用同时发起的数量上限，设为 1 表示逐组串行、与并行改造前的行为完全一致。超出区间的取值会被拒绝并提示允许范围。保存即生效，无需重启）'
WHERE config_key = 'ai.pipeline.group-parallel'
  AND description IS DISTINCT FROM '文档与生成任务分组并行调用数（默认 3，允许 1~3；提取、契约生成、出题三类任务的每组大模型调用同时发起的数量上限，设为 1 表示逐组串行、与并行改造前的行为完全一致。超出区间的取值会被拒绝并提示允许范围。保存即生效，无需重启）';
