
ALTER TABLE tp_review_log ADD COLUMN IF NOT EXISTS feedback_types text[];

COMMENT ON COLUMN tp_review_log.feedback_types IS '反馈问题类型（中文label数组，PRD D1 冻结六类；驳回/需修改必填，通过/存量=NULL=报表未分类桶）';
