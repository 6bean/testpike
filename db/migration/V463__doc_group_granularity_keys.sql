-- Migration: node-granularity caps for the document-extraction grouping plane
-- .
--
-- Why this file exists:
--   The group budget used to be the full effective model window, so a larger
--   catalog-row limit produced FEWER, fatter nodes and the final extract call
--   read only a fraction of the document (live incident: 121,375 tokens in,
--   4,924 tokens read). The window now decides WHETHER a single send is
--   allowed; these keys decide how finely the document is cut. Factory values
-- come from the calibration report: one node stays within 8,000 tokens
--   AND 45 chunks; one request's estimated output stays within 24,000 tokens
--   at the measured 2.94x completion/prompt amplification (re-feed this ratio
--   from the fidelity ledger instead of guessing). Write side validates the
--   same windows; the reader fails closed to these factory values.
--
-- Paired obligation (set of three, missing one burns the whole run):
--   the task-time budget below is raised to 3600s in this same batch, and the
--   MQ ack window TP_MQ_ACK_TIMEOUT_MS must stay >= 2 x 3600 = 7,200,000 ms.
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.doc.group-max-tokens', '8000', '系统', 'string', 0, '单个提取节点读取的最大内容量（token，默认 8000，允许 256~100000；文档超过此值自动按节点切分逐组提取，避免单次调用读不完全部内容。保存即生效，无需重启）'),
    ('ai.doc.group-max-chunks', '45', '系统', 'string', 0, '单个提取节点包含的最大文本块数（默认 45，允许 1~500；与 token 上限同时生效，先到先封口。保存即生效，无需重启）'),
    ('ai.doc.bucket-output-max-tokens', '24000', '系统', 'string', 0, '单次提取调用预估产出的最大 token 数（默认 24000，允许 1000~200000；预估产出超限时自动按更小的分组多次调用，避免结果被供应商输出上限截断。保存即生效，无需重启）'),
    ('ai.doc.output-amplification-ratio', '2.94', '系统', 'string', 0, '产出放大系数：每 1 token 输入约产生的输出 token 数（默认 2.94，允许 0.1~10；来自真实调用用量记录，用于估算单次调用是否会超出产出上限。保存即生效，无需重启）')
ON CONFLICT (config_key) DO NOTHING;

-- Raise the extraction task-time budget to cover the calibration-projected
-- ~45-minute worst case split across buckets. Only lifts the legacy factory
-- shapes (900 seeded by V294 / 1800 raised earlier); an operator-set value is
-- never overwritten. The description travels with the value so the fresh
-- install bootstrap and this upgrade path tell the same story.
UPDATE tp_sys_config
   SET config_value = '3600',
       description = '文档提取任务级时长预算秒数（默认 3600；提取全程超出该预算即中止并恢复为可重新提取状态，再次发起提取即可；单次模型调用超时按剩余时间自动缩短；热更即生效，非法值按 3600 回退）'
 WHERE config_key = 'ai.doc.extract-deadline-seconds'
   AND config_value IS DISTINCT FROM '3600'
   AND config_value IN ('900', '1800');

-- Re-stamp the description for rows already lifted to 3600 by an earlier run
-- of this file (idempotent replay of the text face only).
UPDATE tp_sys_config
   SET description = '文档提取任务级时长预算秒数（默认 3600；提取全程超出该预算即中止并恢复为可重新提取状态，再次发起提取即可；单次模型调用超时按剩余时间自动缩短；热更即生效，非法值按 3600 回退）'
 WHERE config_key = 'ai.doc.extract-deadline-seconds'
   AND config_value = '3600'
   AND description IS DISTINCT FROM '文档提取任务级时长预算秒数（默认 3600；提取全程超出该预算即中止并恢复为可重新提取状态，再次发起提取即可；单次模型调用超时按剩余时间自动缩短；热更即生效，非法值按 3600 回退）';
