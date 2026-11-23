package com.sixbean.testpike.aiservice.knowledge.infrastructure.mq;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.common.idempotent.ConsumeOutcome;
import com.sixbean.testpike.common.idempotent.MqIdempotentConsumeSupport;
import com.sixbean.testpike.common.mq.CaseIngestEventMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;


@Component
public class CaseCleanupConsumer {

    private static final Logger log = LoggerFactory.getLogger(CaseCleanupConsumer.class);

    
    static final String EVENT_DELETED = "DELETED";

    
    static final String EVENT_INVALIDATED = "INVALIDATED";

    
    static final String EVENT_PROJECT_INVALIDATED = "PROJECT_INVALIDATED";

    
    static final long CONSUME_LEASE_MILLIS = 15 * 60 * 1000L;

    private final CaseCleanupIdempotentRunner idempotentRunner;
    private final MqIdempotentConsumeSupport consumeSupport;
    private final CoreGateway coreGateway;

    public CaseCleanupConsumer(CaseCleanupIdempotentRunner idempotentRunner,
                               MqIdempotentConsumeSupport consumeSupport,
                               CoreGateway coreGateway) {
        this.idempotentRunner = idempotentRunner;
        this.consumeSupport = consumeSupport;
        this.coreGateway = coreGateway;
    }

    @RabbitListener(queues = CaseCleanupQueueConfig.QUEUE_NAME)
    public void onMessage(CaseIngestEventMessage message) {
        if (message == null) {
            return;
        }
        if (EVENT_PROJECT_INVALIDATED.equals(message.eventType())) {
            cleanupProject(message);
        } else if (isCaseCleanup(message)) {
            cleanupCase(message);
        } else {
            log.info("G1 清理闸跳过 eventType={} caseId={}（非清理族/残缺事件）",
                    message.eventType(), message.caseId());
        }
    }

    private void cleanupCase(CaseIngestEventMessage message) {
        log.info("G1 用例清理消费 caseId={} projectId={} eventType={}",
                message.caseId(), message.projectId(), message.eventType());
        String dedupKey = "kb-cleanup:case:" + message.caseId() + ":" + message.occurredAt();
        String token = UUID.randomUUID().toString();
        ConsumeOutcome outcome = consumeSupport.consumeOnce(dedupKey, token,
                () -> idempotentRunner.runCase(message, token), CONSUME_LEASE_MILLIS);
        if (outcome == ConsumeOutcome.DUPLICATE) {
            auditDuplicate(message, dedupKey, "kb-cleanup:case:" + message.caseId());
        }
        // noise reduction: case row already deleted -> ack + skip audit
        if (outcome == ConsumeOutcome.ENTITY_GONE_SKIPPED) {
            auditEntityGone(message, dedupKey, "kb-cleanup:case:" + message.caseId());
        }
    }

    private void cleanupProject(CaseIngestEventMessage message) {
        if (message.projectId() == null) {
            log.info("G1 清理闸跳过：项目级事件 projectId=null caseId={}", message.caseId());
            return;
        }
        log.info("G1 项目级清理消费 projectId={}", message.projectId());
        String dedupKey = "kb-cleanup:proj:" + message.projectId() + ":" + message.occurredAt();
        String token = UUID.randomUUID().toString();
        ConsumeOutcome outcome = consumeSupport.consumeOnce(dedupKey, token,
                () -> idempotentRunner.runProject(message, token), CONSUME_LEASE_MILLIS);
        if (outcome == ConsumeOutcome.DUPLICATE) {
            auditDuplicate(message, dedupKey, "kb-cleanup:proj:" + message.projectId());
        }
        // noise reduction: project rows already deleted -> ack + skip audit
        if (outcome == ConsumeOutcome.ENTITY_GONE_SKIPPED) {
            auditEntityGone(message, dedupKey, "kb-cleanup:proj:" + message.projectId());
        }
    }

    
    private void auditDuplicate(CaseIngestEventMessage message, String dedupKey, String auditObjectId) {
        log.info("G1 清理重复投递幂等跳过 dedupKey={}", dedupKey);
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("caseId", message.caseId());
        before.put("projectId", message.projectId());
        before.put("occurredAt", message.occurredAt());
        coreGateway.writeDuplicateDrop(auditObjectId,
                message.projectId() == null ? 0L : message.projectId(),
                message.operatorId(), before);
    }

    /**
 * the cleanup runner found its target rows already deleted — ack with a
 * traceable skip audit, same posture as auditDuplicate (write failure only
 * warns, never blocks the ack).
 */
    private void auditEntityGone(CaseIngestEventMessage message, String dedupKey, String auditObjectId) {
        log.info("G1 清理实体已删除降噪跳过 dedupKey={}", dedupKey);
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("caseId", message.caseId());
        before.put("projectId", message.projectId());
        before.put("occurredAt", message.occurredAt());
        coreGateway.writeEntityGoneSkip(auditObjectId,
                message.projectId() == null ? 0L : message.projectId(),
                message.operatorId(), before);
    }

    
    private static boolean isCaseCleanup(CaseIngestEventMessage message) {
        return (EVENT_DELETED.equals(message.eventType())
                || EVENT_INVALIDATED.equals(message.eventType()))
                && message.caseId() != null;
    }
}
