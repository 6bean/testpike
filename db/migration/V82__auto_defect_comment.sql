
ALTER TABLE tp_defect ADD COLUMN IF NOT EXISTS source SMALLINT NOT NULL DEFAULT 1;
ALTER TABLE tp_defect ADD COLUMN IF NOT EXISTS fail_signature VARCHAR(64);
ALTER TABLE tp_defect ADD COLUMN IF NOT EXISTS last_seen_at TIMESTAMP;
ALTER TABLE tp_defect ADD COLUMN IF NOT EXISTS occurrence_count INT NOT NULL DEFAULT 1;

DO $$ BEGIN
    ALTER TABLE tp_defect ADD CONSTRAINT ck_defect_source CHECK (source BETWEEN 1 AND 2);
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS uk_defect_open_sig ON tp_defect (case_id, fail_signature)
    WHERE source = 2 AND status <> 5 AND case_id IS NOT NULL;

COMMENT ON COLUMN tp_defect.source IS '数据源 1=人工建单 2=批次失败自动建单（V3/T232）';
COMMENT ON COLUMN tp_defect.fail_signature IS '失败签名 sha256(用例|失败步|错误类别|归一错误文本)，仅自动单（V3/T232）';
COMMENT ON COLUMN tp_defect.last_seen_at IS '同签名末次复现时间（去重窗判据，V3/T232）';
COMMENT ON COLUMN tp_defect.occurrence_count IS '同签名累计复现次数（首建=1，追评+1，V3/T232）';

CREATE TABLE IF NOT EXISTS tp_comment (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    host_type  VARCHAR(20)  NOT NULL,
    host_id    BIGINT       NOT NULL,
    source     SMALLINT     NOT NULL DEFAULT 1,
    author_id  BIGINT,
    content    VARCHAR(2000) NOT NULL,
    ref_key    VARCHAR(64),
    created_at TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT ck_comment_host CHECK (host_type IN ('defect'))
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_comment_host_ref ON tp_comment (host_type, host_id, ref_key)
    WHERE ref_key IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_comment_host ON tp_comment (host_type, host_id, id);

COMMENT ON TABLE tp_comment IS '多态评论表（V14 宿主面最小建：T232 自动追评 + T240 人工评论扩展复用）';
COMMENT ON COLUMN tp_comment.ref_key IS '系统评论幂等键（task:{taskId}=按任务恰一条，IT-V3-02 重投不翻倍）';

INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
VALUES
('defect.auto-create.enabled', '1', '系统', 'int', 0, '批次失败自动建缺陷开关（V3/T232；默认 1=开，仅 0 或 1 写侧校验；TC-V3-01/IT-V3-01）'),
('defect.auto-create.dedup-window', '24', '系统', 'int', 0, '自动建单去重窗口小时（V3/T232；已关闭缺陷在窗口内同签名复现=重开+追评，窗外=新立；域 1~720 写侧校验；TC-V3-01/IT-V3-01）')
ON CONFLICT (config_key) DO NOTHING;
