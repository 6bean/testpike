
CREATE TABLE IF NOT EXISTS tp_upgrade_plan (
    id                  BIGSERIAL    PRIMARY KEY,
    scope               VARCHAR(20)  NOT NULL,
    target_engine_codes JSONB        NOT NULL DEFAULT '[]',
    strategy            JSONB        NOT NULL DEFAULT '{}'::jsonb,
    manifest_version    VARCHAR(60),
    status              VARCHAR(20)  NOT NULL DEFAULT 'PREVIEW',
    brake_reason        VARCHAR(500),
    operator            VARCHAR(50),
    created_at          TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at          TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT ck_upgrade_plan_scope CHECK (scope IN ('engine', 'node')),
    CONSTRAINT ck_upgrade_plan_status CHECK
        (status IN ('PREVIEW', 'RUNNING', 'PAUSED', 'COMPLETED', 'ABORTED', 'BRAKED')),
    CONSTRAINT ck_upgrade_plan_engine_codes CHECK (jsonb_typeof(target_engine_codes) = 'array'),
    CONSTRAINT ck_upgrade_plan_strategy CHECK (jsonb_typeof(strategy) = 'object')
);

CREATE INDEX IF NOT EXISTS idx_upgrade_plan_status ON tp_upgrade_plan (status, created_at);

COMMENT ON TABLE tp_upgrade_plan IS '升级计划（U1-U3 编排权威行：灰度策略快照+状态机 CAS+刹车归因；下发只经命令队列）';
COMMENT ON COLUMN tp_upgrade_plan.scope IS '升级范围：engine=单/多引擎升级（U1/U3 引擎维度）；node=节点 agent 升级（U2，金版本窗口引用）';
COMMENT ON COLUMN tp_upgrade_plan.target_engine_codes IS '引擎 code 快照 jsonb 数组（创建时锁定，描述符目录变更不影响在途计划）';
COMMENT ON COLUMN tp_upgrade_plan.strategy IS '灰度策略快照 {canaryNodeIds,batchSize,cool,baselineRef}（cool=创建时解析 K8 键落库，键驱动禁硬编码）';
COMMENT ON COLUMN tp_upgrade_plan.manifest_version IS '升级权威引用（tp_release_manifest.version； 禁第二权威）';
COMMENT ON COLUMN tp_upgrade_plan.status IS '状态机 PREVIEW→RUNNING→PAUSED→COMPLETED|ABORTED|BRAKED（推进全 CAS，坑8 批次边界生效不掐在跑）';
COMMENT ON COLUMN tp_upgrade_plan.brake_reason IS '自动刹车写明原因（U3 金丝雀不达标=通过率/耗时对比证据句，通知+证据链消费）';

CREATE TABLE IF NOT EXISTS tp_upgrade_plan_step (
    id              BIGSERIAL    PRIMARY KEY,
    plan_id         BIGINT       NOT NULL REFERENCES tp_upgrade_plan (id) ON DELETE CASCADE,
    node_id         VARCHAR(120) NOT NULL,
    kind            VARCHAR(10)  NOT NULL,
    engine_code     VARCHAR(64),
    phase           VARCHAR(10)  NOT NULL DEFAULT 'batch',
    current_version VARCHAR(80),
    target_version  VARCHAR(80),
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    command_id      BIGINT,
    evidence        JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_at      TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT ck_upgrade_plan_step_kind CHECK (kind IN ('engine', 'node')),
    CONSTRAINT ck_upgrade_plan_step_phase CHECK (phase IN ('canary', 'batch', 'rollback')),
    CONSTRAINT ck_upgrade_plan_step_status CHECK
        (status IN ('PENDING', 'RUNNING', 'SUCCESS', 'FAILED', 'SKIPPED')),
    CONSTRAINT ck_upgrade_plan_step_evidence CHECK (jsonb_typeof(evidence) = 'object')
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_upgrade_plan_step_node
    ON tp_upgrade_plan_step (plan_id, node_id, COALESCE(engine_code, ''));
CREATE INDEX IF NOT EXISTS idx_upgrade_plan_step_plan ON tp_upgrade_plan_step (plan_id, phase, status);

COMMENT ON TABLE tp_upgrade_plan_step IS '升级计划步快照（预览现算产物；一机一引擎一步唯一；command_id=命令队列引用唯一下发面，坑1）';
COMMENT ON COLUMN tp_upgrade_plan_step.node_id IS '节点标识（tp_cluster_component.node_id 同谱寻址串）';
COMMENT ON COLUMN tp_upgrade_plan_step.kind IS 'engine=引擎运行时步（engine_code 非空）；node=节点 agent 步（engine_code 空）';
COMMENT ON COLUMN tp_upgrade_plan_step.phase IS 'canary=灰度槽先升机；batch=分批推进；rollback=自动刹车反向步（回滚目标=台账现值/账本钉版，坑5）';
COMMENT ON COLUMN tp_upgrade_plan_step.target_version IS '目标版本=金版本引用（台账 target_version/描述符钉版， 禁重写禁 payload 造第二基准）';
COMMENT ON COLUMN tp_upgrade_plan_step.command_id IS 'tp_cluster_command 引用（03 零命令运维队列；NULL=尚未下发）';
COMMENT ON COLUMN tp_upgrade_plan_step.evidence IS '步证据链 jsonb（金丝雀通过率/耗时对比、失败归因；U3「证据链可下载」落点）';

ALTER TABLE tp_release_manifest
    ADD COLUMN IF NOT EXISTS retained_versions JSONB NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE tp_release_manifest
    DROP CONSTRAINT IF EXISTS ck_release_manifest_retained;
ALTER TABLE tp_release_manifest
    ADD CONSTRAINT ck_release_manifest_retained CHECK (jsonb_typeof(retained_versions) = 'array');

COMMENT ON COLUMN tp_release_manifest.retained_versions IS 'U7 保留上 N 版清单 jsonb 数组（新在前；上限消费 cluster.retention-versions 键；真机文件 GC=installkit 侧消费）';
