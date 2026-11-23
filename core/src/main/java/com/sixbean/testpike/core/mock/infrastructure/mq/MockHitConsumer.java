package com.sixbean.testpike.core.mock.infrastructure.mq;

import com.sixbean.testpike.common.idempotent.ConsumeOutcome;
import com.sixbean.testpike.common.idempotent.MqIdempotentConsumeSupport;
import com.sixbean.testpike.common.mq.MockHitBatchMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Core consumer of the mock hit ledger batches , AG
 * design D-b): the mock-server ships masked rows over {@code testpike.events}
 * /{@code mock.hit.batch}; this listener is the only path into tp_mock_hit_log,
 * because the data plane never touches PostgreSQL.
 * <p>Double guard exactly like the ai-service consumers: in-flight lock +
 * batchId idempotent key (MockHitIdempotentRunner) — a requeue or DLQ replay
 * of the same batch lands exactly once. Business exceptions propagate so the
 * container retries and then dead-letters (unknown spaceKey = a visible dlq
 * backlog, never a silent drop).
 * <p>Ledger rows are masked upstream at record time; core re-reads them as
 * already-sanitized observations and does not re-apply the desensitizer.
 */
@Component
public class MockHitConsumer {

    private static final Logger log = LoggerFactory.getLogger(MockHitConsumer.class);

    /**
     * Consume lock lease: one batch is a handful of INSERTs under a single
     * transaction (worst case seconds), so 5 minutes covers it with crash
     * self-heal — far below the AI consumers' 15 min (no LLM here).
     */
    static final long CONSUME_LEASE_MILLIS = 5 * 60 * 1000L;

    private final MockHitIdempotentRunner idempotentRunner;
    private final MqIdempotentConsumeSupport consumeSupport;

    public MockHitConsumer(MockHitIdempotentRunner idempotentRunner,
                           MqIdempotentConsumeSupport consumeSupport) {
        this.idempotentRunner = idempotentRunner;
        this.consumeSupport = consumeSupport;
    }

    @RabbitListener(queues = MockHitQueueConfig.QUEUE_NAME)
    public void onMessage(MockHitBatchMessage message) {
        if (message == null || message.batchId() == null || message.batchId().isBlank()) {
            log.warn("Mock 命中流水批次缺 batchId——拒收进死信留痕，禁静默 ack");
            throw new IllegalArgumentException("hit batch without batchId cannot be deduplicated");
        }
        String dedupKey = "mock-hit:" + message.batchId();
        String token = UUID.randomUUID().toString();
        ConsumeOutcome outcome = consumeSupport.consumeOnce(dedupKey, token,
                () -> idempotentRunner.run(message, token), CONSUME_LEASE_MILLIS);
        if (outcome == ConsumeOutcome.DUPLICATE) {
            log.info("Mock 命中流水重复投递幂等跳过 batchId={}", message.batchId());
        }
    }
}
