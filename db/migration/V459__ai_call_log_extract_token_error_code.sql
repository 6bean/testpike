-- DD batch : extraction-generation ledger columns on tp_ai_call_log.
-- Purpose: every provider call of the document-extraction plane (success
-- AND failure/timeout) lands in the cost ledger tagged with the run's
-- generation token, so a preempted generation's burns are reconcilable
-- and a timed-out call is no longer invisible.
-- Past failures pre-dating this migration cannot be backfilled — stated
-- honestly in the close-out report (R18), no fabricated history rows.
-- error_code vocabulary: BizErrorCode numeric as text (e.g. 50401=AI
-- timeout) or the exception simple name for non-service failures.

ALTER TABLE tp_ai_call_log
    ADD COLUMN IF NOT EXISTS extract_token VARCHAR(64);
ALTER TABLE tp_ai_call_log
    ADD COLUMN IF NOT EXISTS error_code VARCHAR(64);

COMMENT ON COLUMN tp_ai_call_log.extract_token IS
    '提取轮次 token（tp_document.extract_token 同值；非提取链路为 NULL）';
COMMENT ON COLUMN tp_ai_call_log.error_code IS
    '失败/超时调用的错误码（BizErrorCode 数值文本或异常简名；成功行为 NULL）';

-- Reconcile key face: per-generation burns of one document run.
CREATE INDEX IF NOT EXISTS idx_ai_call_log_extract_token
    ON tp_ai_call_log (extract_token, created_at);
