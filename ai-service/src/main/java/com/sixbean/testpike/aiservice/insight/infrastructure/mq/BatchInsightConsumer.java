package com.sixbean.testpike.aiservice.insight.infrastructure.mq;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.insight.domain.service.BatchInsightService;
import com.sixbean.testpike.common.idempotent.ConsumeOutcome;
import com.sixbean.testpike.common.idempotent.MqIdempotentConsumeSupport;
import com.sixbean.testpike.common.mq.BatchFinalizedEventMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Batch-insight clustering consumer (AO-7 stage 2b; mirrors ClassifyConsumer —
 * dedicated queue, dedicated ack).
 * <p>Consumer gate: eventType = EXEC_BATCH_FINALIZED ∧ batchId non-null ∧ projectId
 * non-null — other event types share the testpike.events broadcast and are acked
 * with zero action. Idempotency is two-layer: in-flight lock + key
 * insight:batch:{batchId} (BatchInsightIdempotentRunner @Idempotent); DUPLICATE
 * deliveries leave a duplicate_drop audit row (never silent). Business exceptions
 * propagate → container retry → dead letter (BatchInsightQueueConfig DLX).
 * The ai.insight.enabled switch gate and its closed-state guidance live in the
 * service (this consumer does not read config).
 */
@Component
public class BatchInsightConsumer {

    private static final Logger log = LoggerFactory.getLogger(BatchInsightConsumer.class);

    /** Event type the gate accepts (core CaseEventType.EXEC_BATCH_FINALIZED name). */
    static final String EVENT_TYPE = "EXEC_BATCH_FINALIZED";

    /** Consume lock lease 15min (batch read-out + write-back worst case × retries, same calibration as classify). */
    static final long CONSUME_LEASE_MILLIS = 15 * 60 * 1000L;

    private final BatchInsightIdempotentRunner idempotentRunner;
    private final MqIdempotentConsumeSupport consumeSupport;
    private final CoreGateway coreGateway;

    public BatchInsightConsumer(BatchInsightIdempotentRunner idempotentRunner,
                                MqIdempotentConsumeSupport consumeSupport,
                                CoreGateway coreGateway) {
        this.idempotentRunner = idempotentRunner;
        this.consumeSupport = consumeSupport;
        this.coreGateway = coreGateway;
    }

    @RabbitListener(queues = BatchInsightQueueConfig.QUEUE_NAME)
    public void onMessage(BatchFinalizedEventMessage message) {
        if (!isInsightSubject(message)) {
            return;
        }
        log.info("batch insight consume batchId={} projectId={} targetStatus={}",
                message.batchId(), message.projectId(), message.targetStatus());
        String dedupKey = "insight:batch:" + message.batchId();
        String token = UUID.randomUUID().toString();
        ConsumeOutcome outcome = consumeSupport.consumeOnce(dedupKey, token,
                () -> idempotentRunner.run(message, token), CONSUME_LEASE_MILLIS);
        if (outcome == ConsumeOutcome.DUPLICATE) {
            log.info("batch insight redelivery deduplicated batchId={}", message.batchId());
            Map<String, Object> before = new LinkedHashMap<>();
            before.put("batchId", message.batchId());
            before.put("targetStatus", message.targetStatus());
            coreGateway.writeDuplicateDrop(dedupKey, message.projectId(),
                    message.operatorId(), before);
        }
        // noise reduction: batch/execution rows already deleted -> ack + skip audit
        if (outcome == ConsumeOutcome.ENTITY_GONE_SKIPPED) {
            log.info("batch insight entity-gone skipped batchId={}", message.batchId());
            Map<String, Object> before = new LinkedHashMap<>();
            before.put("batchId", message.batchId());
            before.put("targetStatus", message.targetStatus());
            coreGateway.writeEntityGoneSkip(dedupKey, message.projectId(),
                    message.operatorId(), before);
        }
    }

    /** Gate: a batch-finalized payload carries the batch id and the project anchor; other broadcast events pass through with zero action. */
    private static boolean isInsightSubject(BatchFinalizedEventMessage message) {
        return message != null
                && EVENT_TYPE.equals(message.eventType())
                && message.batchId() != null
                && message.projectId() != null;
    }
}
