-- V395: AJ batch the dead-letter ledger table tp_mq_dead_letter.
-- Rationale: Rabbit DLQs currently have zero egress (RED anchor of ). From on,
-- DLQ listeners consume each dead message and write one ledger row before acking, so the
-- backlog metric becomes "rows with status=pending in this table", never Rabbit queue depth
-- reports must state this explicitly to avoid reconciliation drift).
-- Columns follow the database naming standard (snake_case, tp_ prefix, no reserved words);
-- the classifier
-- enum (deadletter/domain/component/DeadLetterFailureClass) is the single source for
-- failure_class values — replay/gray-out logic keys off it, so the column stays plain VARCHAR
-- without a second enum definition in code.
-- Idempotency root: uk on (message_fingerprint, queue_name) — a redelivered or re-dead-lettered
-- message lands once; the ledger listener uses INSERT ... ON CONFLICT DO NOTHING (same proven
-- pattern as tp_schedule_fire). Fingerprint = broker message id when present, else a digest of
-- queue+payload (derived by the listener, never here).
-- status lifecycle: pending (booked) -> replayed / voided (operator void
-- with void_reason + operator recorded); replay_round counts replays of this message across rounds.
-- Flyway is not wired in this project: apply manually to the target database (DBA path in
-- production), then regenerate db/schema-init.sql with tools/ops/gen_schema_init.sh.

CREATE TABLE IF NOT EXISTS tp_mq_dead_letter (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    message_fingerprint VARCHAR(64)  NOT NULL,
    queue_name        VARCHAR(128) NOT NULL,
    orig_exchange     VARCHAR(128),
    orig_routing_key  VARCHAR(256),
    payload_json      TEXT         NOT NULL,
    failure_class     VARCHAR(32)  NOT NULL,
    failure_reason    TEXT,
    x_death_json      TEXT,
    replay_round      INT          NOT NULL DEFAULT 0,
    status            VARCHAR(16)  NOT NULL DEFAULT 'pending',
    void_reason       VARCHAR(512),
    operator          VARCHAR(64),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_mq_dead_letter_msg ON tp_mq_dead_letter (message_fingerprint, queue_name);
CREATE INDEX IF NOT EXISTS idx_mq_dead_letter_status ON tp_mq_dead_letter (status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_mq_dead_letter_class ON tp_mq_dead_letter (failure_class, created_at DESC);

COMMENT ON TABLE tp_mq_dead_letter IS '死信账本：DLQ 消息消费落账后 ack 的一等记录；积压统计=status=pending 的行数（非 Rabbit 队列深度）';
COMMENT ON COLUMN tp_mq_dead_letter.message_fingerprint IS '消息指纹（broker message id 或 queue+payload 摘要）；与 queue_name 联合唯一=重复落账幂等根';
COMMENT ON COLUMN tp_mq_dead_letter.failure_class IS '分型（DeadLetterFailureClass 六型单源，UNKNOWN 兜底禁猜）';
COMMENT ON COLUMN tp_mq_dead_letter.failure_reason IS '人话失败原因（异常链摘要，脱敏后文本）';
COMMENT ON COLUMN tp_mq_dead_letter.x_death_json IS 'broker x-death 头原样 JSON（审计与分型取证）';
COMMENT ON COLUMN tp_mq_dead_letter.replay_round IS '本消息已补投轮次（0=未补投）';
COMMENT ON COLUMN tp_mq_dead_letter.status IS 'pending=待处理 / replayed=已补投 / voided=人工作废';
COMMENT ON COLUMN tp_mq_dead_letter.void_reason IS '作废原因（status=voided 时必填）';
COMMENT ON COLUMN tp_mq_dead_letter.operator IS '操作人（补投/作废的动作发起者）';
