
CREATE TABLE IF NOT EXISTS tp_cluster_component (
    id               BIGSERIAL    PRIMARY KEY,
    node_id          VARCHAR(120) NOT NULL,
    component_family VARCHAR(80)  NOT NULL,
    current_version  VARCHAR(80),
    target_version   VARCHAR(80),
    requirement      VARCHAR(120),
    support_state    VARCHAR(10)  NOT NULL DEFAULT 'UNKNOWN',
    state_reason     VARCHAR(300),
    source           VARCHAR(20)  NOT NULL DEFAULT 'heartbeat',
    reported_at      TIMESTAMP,
    created_at       TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at       TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT uq_cluster_component_node_family UNIQUE (node_id, component_family),
    CONSTRAINT ck_cluster_component_state CHECK (support_state IN ('GREEN', 'YELLOW', 'RED', 'UNKNOWN'))
);

COMMENT ON TABLE tp_cluster_component IS '集群部件×版本统一台账（版本检测数据面；节点×部件单行，心跳扩报+定时扫描回写，禁两本账）';
COMMENT ON COLUMN tp_cluster_component.node_id IS '节点标识（执行器 id/装机指纹/主机名，体系内节点统一寻址串）';
COMMENT ON COLUMN tp_cluster_component.component_family IS '部件族（数据化枚举可扩展：core/admin/api/ai-service/frontend/db-schema/executor-agent/browser-kernel/engine-runtime.*）';
COMMENT ON COLUMN tp_cluster_component.current_version IS '现值版本（上报；NULL=未上报=诚实缺省）';
COMMENT ON COLUMN tp_cluster_component.target_version IS '目标值（金版本/上游最新；NULL=无基准，判定回退 UNKNOWN 禁猜）';
COMMENT ON COLUMN tp_cluster_component.requirement IS '支持区间表达式（如 >=22.12 或 =1.6.1 语义；NULL=区间未知）';
COMMENT ON COLUMN tp_cluster_component.support_state IS '支持态三色+灰：GREEN=区间内最新/YELLOW=可升级/RED=不在支持矩阵失守/UNKNOWN=离线或无基准（灰）';
COMMENT ON COLUMN tp_cluster_component.state_reason IS '判定可读理由（含「外网探测不可达」「未知部件族」等诚实标注，禁 JSON dump）';
COMMENT ON COLUMN tp_cluster_component.source IS '现值来源：heartbeat(心跳扩报)/scan(定时扫描)/manual(手工登记)';
COMMENT ON COLUMN tp_cluster_component.reported_at IS '现值最近上报时间（NULL=从未成功上报）';
