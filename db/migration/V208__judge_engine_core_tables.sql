
CREATE TABLE IF NOT EXISTS tp_judge_rubric (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name         VARCHAR(100) NOT NULL,
    domain       VARCHAR(50)  NOT NULL,
    version      INT          NOT NULL,
    dimensions   JSONB        NOT NULL,
    rules        JSONB        NOT NULL DEFAULT '[]'::jsonb,
    pass_score   INT          NOT NULL DEFAULT 60,
    w_rule       NUMERIC(4,3) NOT NULL DEFAULT 0.400,
    w_llm        NUMERIC(4,3) NOT NULL DEFAULT 0.600,
    status       SMALLINT     NOT NULL DEFAULT 1,
    created_by   BIGINT,
    created_at   TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at   TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT uk_judge_rubric_name_domain_version UNIQUE (name, domain, version),
    CONSTRAINT ck_judge_rubric_pass CHECK (pass_score >= 0 AND pass_score <= 100),
    CONSTRAINT ck_judge_rubric_w_rule CHECK (w_rule >= 0 AND w_rule <= 1),
    CONSTRAINT ck_judge_rubric_w_llm CHECK (w_llm >= 0 AND w_llm <= 1)
);
CREATE INDEX IF NOT EXISTS idx_judge_rubric_name_domain ON tp_judge_rubric (name, domain, status);

COMMENT ON TABLE tp_judge_rubric IS 'LLM 评测器评分标准（详设；修改即新版本/克隆即新版本，C1 同机制）';
COMMENT ON COLUMN tp_judge_rubric.dimensions IS '维度 JSON 数组（名称/定义/评分锚点 few-shot 示例/权重）';
COMMENT ON COLUMN tp_judge_rubric.rules IS '规则项列表（确定性检查器名+参数， 规则层短路，J2 注册表消费）';
COMMENT ON COLUMN tp_judge_rubric.pass_score IS '门禁阈值 0~100（D2 准出谓词消费，J7 接线）';
COMMENT ON COLUMN tp_judge_rubric.w_rule IS '合成层规则权重 0~1（score=Σ规则分×w_rule+ΣLLM分×w_llm）';
COMMENT ON COLUMN tp_judge_rubric.w_llm      IS '合成层 LLM 权重 0~1（评分卡视图=规则维全占 w_llm 0 的特例，6.3/6.7 DRY）';
COMMENT ON COLUMN tp_judge_rubric.status     IS '1=灰度(默认) 2=全量 3=历史（C1 同机制）';

CREATE TABLE IF NOT EXISTS tp_judge_sample (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    set_name       VARCHAR(100) NOT NULL,
    version        INT          NOT NULL,
    snapshot_digest CHAR(64)    NOT NULL,
    input          TEXT         NOT NULL,
    expected       TEXT,
reference_docs   JSONB        NOT NULL DEFAULT '[]'::jsonb,
    source         VARCHAR(30)  NOT NULL DEFAULT 'manual',
    sensitive      SMALLINT     NOT NULL DEFAULT 0,
    created_by     BIGINT,
    created_at     TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT ck_judge_sample_source CHECK (source IN ('manual','batch-export','training-raise','ai-generated'))
);
CREATE INDEX IF NOT EXISTS idx_judge_sample_set_version ON tp_judge_sample (set_name, version);

COMMENT ON TABLE tp_judge_sample IS '评测集样本（详设；样本增删即新版本=整集复制新 version 行，SHA256 快照锚定版本不可变）';
COMMENT ON COLUMN tp_judge_sample.snapshot_digest IS '本版本全样本规范串（input/expected/reference_docs 逐行排序拼接）的 SHA-256 小写 hex；同集同版本所有行同值；乱序增删同内容=同锚（判定确定性）';
COMMENT ON COLUMN tp_judge_sample.reference_docs IS '参考件 JSON 数组（详设 references 三件套之一；PG 保留字 REFERENCES 禁裸用故落列名 reference_docs；judge 接地校验对照， 禁孤儿分）';
COMMENT ON COLUMN tp_judge_sample.source     IS '四来源：manual 手工/batch-export 批次导出/training-raise 训练样本提升/ai-generated AI 造题（J6）';
COMMENT ON COLUMN tp_judge_sample.sensitive IS '脱敏位 1=敏感（脱敏未过审禁送外部厂商 judge，仅允许内部模型组， 红线）';

CREATE TABLE IF NOT EXISTS tp_judge_run (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    rubric_name     VARCHAR(100) NOT NULL,
    rubric_domain   VARCHAR(50)  NOT NULL,
    rubric_version  INT          NOT NULL,
    rubric_snapshot JSONB        NOT NULL,
    evalset_name    VARCHAR(100) NOT NULL,
    evalset_version INT          NOT NULL,
    evalset_digest  CHAR(64)     NOT NULL,
    target_type     VARCHAR(20)  NOT NULL,
    target_ref      VARCHAR(200) NOT NULL,
    llm_config_id   BIGINT,
    status          SMALLINT     NOT NULL DEFAULT 0,
    created_by      BIGINT,
    created_at      TIMESTAMP    NOT NULL DEFAULT now(),
    finished_at     TIMESTAMP,
    CONSTRAINT ck_judge_run_target CHECK (target_type IN ('pipeline','external')),
    CONSTRAINT ck_judge_run_status CHECK (status IN (0,1,2,3))
);
CREATE INDEX IF NOT EXISTS idx_judge_run_evalset ON tp_judge_run (evalset_name, evalset_version);

COMMENT ON TABLE tp_judge_run IS '评测运行（详设；Rubric/EvalSet 双版本快照钉住，事后改标准不改历史归因）';
COMMENT ON COLUMN tp_judge_run.rubric_snapshot IS '创建时 Rubric 行全量 JSON 快照（维度/规则/阈值/权重冻结）';
COMMENT ON COLUMN tp_judge_run.target_type IS '被评目标两类（EvalTargetAdapter 策略）：pipeline=testPike 自家生成配置 / external=被测 AI 系统 HTTP 适配';
COMMENT ON COLUMN tp_judge_run.status          IS '0=待跑 1=跑批中 2=完成 3=失败';

CREATE TABLE IF NOT EXISTS tp_judge_record (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    run_id              BIGINT NOT NULL REFERENCES tp_judge_run (id) ON DELETE CASCADE,
    sample_id           BIGINT NOT NULL REFERENCES tp_judge_sample (id),
    rule_score          INT,
    rule_hits           JSONB   NOT NULL DEFAULT '[]'::jsonb,
    llm_scores          JSONB,
    final_score         INT,
    reasons             JSONB,
    latency_ms          BIGINT,
    tokens              INT,
    manual_final_score  INT,
    created_at          TIMESTAMP NOT NULL DEFAULT now(),
    updated_at          TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT uk_judge_record_run_sample UNIQUE (run_id, sample_id),
    CONSTRAINT ck_judge_record_rule CHECK (rule_score IS NULL OR (rule_score >= 0 AND rule_score <= 100)),
    CONSTRAINT ck_judge_record_final CHECK (final_score IS NULL OR (final_score >= 0 AND final_score <= 100)),
    CONSTRAINT ck_judge_record_manual CHECK (manual_final_score IS NULL OR (manual_final_score >= 0 AND manual_final_score <= 100))
);
CREATE INDEX IF NOT EXISTS idx_judge_record_run ON tp_judge_record (run_id, final_score);

COMMENT ON TABLE tp_judge_record IS '单样本评分记录（详设/6.4；规则分+LLM 各维分+合成+理由与 offset 证据引用+人工终判位）';
COMMENT ON COLUMN tp_judge_record.rule_hits  IS '命中规则明细（规则名+判定，可审计到规则级；硬规则短路=直接 0 分/上限分+reason=规则名）';
COMMENT ON COLUMN tp_judge_record.llm_scores IS 'LLM 各维分（无引用锚的分值=无效「判分无据」进人审， 禁孤儿分）';
COMMENT ON COLUMN tp_judge_record.reasons    IS '每维理由与证据引用（offset 锚校验，jsonschema networknt 现件）';

CREATE TABLE IF NOT EXISTS tp_judge_review (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    run_id     BIGINT NOT NULL REFERENCES tp_judge_run (id) ON DELETE CASCADE,
    record_id  BIGINT NOT NULL REFERENCES tp_judge_record (id) ON DELETE CASCADE,
    reviewer   BIGINT NOT NULL,
    dimension  VARCHAR(50),
    org_score  INT,
    new_score  INT NOT NULL,
    reason     TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_judge_review_run ON tp_judge_review (run_id);

COMMENT ON TABLE tp_judge_review IS '人工改判流水（详设；人机一致率 κ/Spearman 数据源，J4 校准包消费；改判样本按维度聚合→锚点更新候选建议）';
