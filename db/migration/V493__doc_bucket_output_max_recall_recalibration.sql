-- Migration: recalibrate the per-call extraction output budget key.
--
-- Why this file exists:
-- EF batch added the exhaustive-recall clauses to the extraction
--   prompt face on purpose (item count follows the source, no ceiling).
--   The routed thinking model also bills reasoning tokens inside the
--   completion total, so a published max_tokens no longer holds the billed
--   figure below it. The audit line therefore stayed on the old pre-recall
--   prediction (24000) while real calls measured far above it: over the
--   post-booking-seam window the live chain booked 225 plane rows with
--   completion p90=34157 / p95=39940 / p99=47843 and an observed maximum
--   of 69136. The gate's D6 face read red on every batch-close run — a
--   stale ceiling, not a runaway call.
-- Behaviour notes:
--   * 72000 = observed maximum with headroom, inside the 1000~200000
--     write-side window; it stays below the provider output wall the same
--     way the old value did (the same call face kept billing 69136 tokens
--     without truncation);
--   * grouping granularity is untouched: the output-derived input ceiling
--     (72000 / amplification 2.94 ~ 24489) stays above the 8000-token node
--     cap, so sealed groups keep their chapter-aligned size;
--   * value and description are rewritten together so the configuration
--     page never shows a number the reader does not use; replay-safe by
--     IS DISTINCT FROM on the new value (no churn on re-runs).
UPDATE tp_sys_config
   SET config_value = '72000',
       description = '单次提取调用产出 token 的预估上限（默认 72000，允许 1000~200000；穷尽召回口径下产出条数跟随原文且模型推理量计入产出，上限按实测分布重定。超上限的调用会被拒绝并提示超出单次产出预算；预估产出接近上限时会自动按更小的分组多次调用，避免结果被供应商输出上限截断。保存即生效，无需重启）'
 WHERE config_key = 'ai.doc.bucket-output-max-tokens'
   AND (config_value IS DISTINCT FROM '72000'
        OR description IS DISTINCT FROM '单次提取调用产出 token 的预估上限（默认 72000，允许 1000~200000；穷尽召回口径下产出条数跟随原文且模型推理量计入产出，上限按实测分布重定。超上限的调用会被拒绝并提示超出单次产出预算；预估产出接近上限时会自动按更小的分组多次调用，避免结果被供应商输出上限截断。保存即生效，无需重启）');
