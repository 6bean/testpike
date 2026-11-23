-- Migration: token ceiling for ONE final-extraction call .
--
-- Why this file exists:
--   Document 8491 (642 chunks) burned the 300s provider window inside a single
--   monolithic final-extract request. The final plane now splits its input
--   into budget-bounded chapter buckets through the SAME greedy loop and
--   overlap tail as the summarisation grouping, re-rolls a malformed bucket
--   alone (a bad bucket never re-bills the good ones) and caps cross-bucket
--   concurrency at three.
--   Value semantics: empty = automatic ceiling (routed catalog row input
--   limit x 0.6 headroom x ai.doc.budget-safety-factor); "0" = disabled, the
--   legacy single monolithic call stays byte-identical; any other value must
--   be a positive integer in tokens. The write side rejects negatives and
--   garbage with a plain 400 naming the rule; the reader falls back to the
--   automatic face and names the key in the log.
INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description) VALUES
    ('ai.doc.final-bucket-max-tokens', '', '系统', 'string', 0, '最终提取单次调用的输入 token 上限（留空=按所路由模型目录行的输入上限自动；0=关闭分桶、保持旧的单次调用；正整数=手动上限。超限时自动按章节分桶多次调用，单个桶输出不合格只重跑该桶。保存即生效，无需重启）')
ON CONFLICT (config_key) DO NOTHING;
