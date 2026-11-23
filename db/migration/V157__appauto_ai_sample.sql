
CREATE TABLE IF NOT EXISTS tp_appauto_ai_sample (
    id            BIGSERIAL    PRIMARY KEY,
    project_id    BIGINT       NOT NULL REFERENCES tp_project (id),
    case_id       BIGINT       NOT NULL,
    device_id     BIGINT       NULL,
    step_no       INT          NOT NULL,
    anchor        VARCHAR(500) NOT NULL,
    hit_source    VARCHAR(16)  NOT NULL,
    review_result VARCHAR(16)  NOT NULL,
    x             INT          NULL,
    y             INT          NULL,
    source_task_id BIGINT      NULL,
    created_at    TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT ck_appauto_ai_sample_source CHECK (
        hit_source IN ('vision', 'a11y', 'cache', 'manual')),
    CONSTRAINT ck_appauto_ai_sample_review CHECK (
        review_result IN ('confirmed', 'rejected')),
    CONSTRAINT ck_appauto_ai_sample_coord CHECK (x IS NULL OR x >= 0)
);

COMMENT ON TABLE tp_appauto_ai_sample IS 'AI 定位/自愈样本库（-A2 三源共识第三源+-A9 回灌面；命中率看板数据源）';
COMMENT ON COLUMN tp_appauto_ai_sample.anchor IS '同屏锚签名（步定位语义归一串，读写样同源构造）';
COMMENT ON COLUMN tp_appauto_ai_sample.hit_source IS '定位出处：vision(视觉模型)/a11y(无障碍树)/cache(历史缓存)/manual(人工标注)';
COMMENT ON COLUMN tp_appauto_ai_sample.review_result IS '复核结论：confirmed(确认，可作缓存票)/rejected(驳回，仅留痕)';
COMMENT ON COLUMN tp_appauto_ai_sample.device_id IS '设备号（NULL=跨设备通用样本；读样 IS NOT DISTINCT FROM 匹配）';

CREATE INDEX IF NOT EXISTS ix_appauto_ai_sample_lookup
    ON tp_appauto_ai_sample (case_id, step_no, review_result, created_at DESC);
CREATE INDEX IF NOT EXISTS ix_appauto_ai_sample_project
    ON tp_appauto_ai_sample (project_id, case_id);
