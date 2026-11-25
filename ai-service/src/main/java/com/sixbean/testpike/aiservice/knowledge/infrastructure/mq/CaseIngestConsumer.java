package com.sixbean.testpike.aiservice.knowledge.infrastructure.mq;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.knowledge.domain.component.ProjectKbGate;
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
public class CaseIngestConsumer {

    private static final Logger log = LoggerFactory.getLogger(CaseIngestConsumer.class);

    
    static final String ACTION_PASS = "PASS";

    
    static final String ACTION_EXCELLENT = "EXCELLENT";

    
    static final String REQUIREMENT_EVENT_REVIEW_PASSED = "REVIEW_PASSED";

    static final String REQUIREMENT_EVENT_CHANGED = "CHANGED";

    
    static final long CONSUME_LEASE_MILLIS = 15 * 60 * 1000L;

    private final CaseIngestIdempotentRunner idempotentRunner;
    private final MqIdempotentConsumeSupport consumeSupport;
    private final CoreGateway coreGateway;
    private final ProjectKbGate kbGate;

    public CaseIngestConsumer(CaseIngestIdempotentRunner idempotentRunner,
                              MqIdempotentConsumeSupport consumeSupport,
                              CoreGateway coreGateway,
                              ProjectKbGate kbGate) {
        this.idempotentRunner = idempotentRunner;
        this.consumeSupport = consumeSupport;
        this.coreGateway = coreGateway;
        this.kbGate = kbGate;
    }

    @RabbitListener(queues = CaseIngestQueueConfig.QUEUE_NAME)
    public void onMessage(CaseIngestEventMessage message) {
        if (message != null && message.requirementId() != null) {
            onRequirementEvent(message);
            return;
        }
        onCaseEvent(message);
    }

    /**
     * Requirement dispatch on the shared queue (thin branch, no re-ingest of
     * case logic). REVIEW_PASSED → idempotent ingest under
     * kb-ingest:req:{id}:{version} (gate off = audit-trail skip, same posture
     * as the case PASS branch). CHANGED → retire runs regardless of the ingest
     * gate and occupies no key: stale criteria must leave the vector space
     * even for disabled projects, and the CHANGED→REVIEW_PASSED fallback pair
     * at one {id}:{version} must execute both actions in publish order.
     */
    private void onRequirementEvent(CaseIngestEventMessage message) {
        if (message.projectId() == null) {
            log.info("Requirement ingest gate skipped: projectId missing requirementId={} eventType={} (incomplete event)",
                    message.requirementId(), message.eventType());
            return;
        }
        if (REQUIREMENT_EVENT_CHANGED.equals(message.eventType())) {
            log.info("Requirement offline consumed requirementId={} projectId={} version={}",
                    message.requirementId(), message.projectId(), message.requirementVersion());
            idempotentRunner.runRetire(message.requirementId());
            return;
        }
        if (!REQUIREMENT_EVENT_REVIEW_PASSED.equals(message.eventType())) {
            log.info("Requirement event type outside the ingest branches, skipping eventType={} requirementId={}",
                    message.eventType(), message.requirementId());
            return;
        }
        if (!kbGate.isAutoIngestEnabled(message.projectId())) {
            coreGateway.writeKbGateSkip("kb-ingest:req:" + message.requirementId(),
                    message.projectId(), message.operatorId(), requirementAuditFields(message));
            return;
        }
        log.info("Requirement ingest consumed requirementId={} projectId={} version={}",
                message.requirementId(), message.projectId(), message.requirementVersion());
        String dedupKey = "kb-ingest:req:" + message.requirementId() + ":" + message.requirementVersion();
        String token = UUID.randomUUID().toString();
        ConsumeOutcome outcome = consumeSupport.consumeOnce(dedupKey, token,
                () -> idempotentRunner.runRequirement(message, token), CONSUME_LEASE_MILLIS);
        if (outcome == ConsumeOutcome.DUPLICATE) {
            log.info("Requirement ingest duplicate delivery, idempotent skip requirementId={} version={}",
                    message.requirementId(), message.requirementVersion());
            coreGateway.writeDuplicateDrop("kb-ingest:req:" + message.requirementId(),
                    message.projectId(), message.operatorId(), requirementAuditFields(message));
        }
        // noise reduction: requirement row already deleted -> ack + skip audit
        if (outcome == ConsumeOutcome.ENTITY_GONE_SKIPPED) {
            log.info("Requirement ingest skipped, entity already deleted, reducing noise requirementId={}", message.requirementId());
            coreGateway.writeEntityGoneSkip("kb-ingest:req:" + message.requirementId(),
                    message.projectId(), message.operatorId(), requirementAuditFields(message));
        }
    }

    private static Map<String, Object> requirementAuditFields(CaseIngestEventMessage message) {
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("requirementId", message.requirementId());
        before.put("requirementVersion", message.requirementVersion());
        return before;
    }

    private void onCaseEvent(CaseIngestEventMessage message) {
        if (!isIngestable(message)) {
            log.info("G1 case ingest gate skipped eventType={} caseId={} action={}",
                    message.eventType(), message.caseId(), message.action());
            return;
        }
        boolean excellent = ACTION_EXCELLENT.equals(message.action());
        if (!excellent && !kbGate.isAutoIngestEnabled(message.projectId())) {
            Map<String, Object> before = new LinkedHashMap<>();
            before.put("caseId", message.caseId());
            before.put("caseVersion", message.caseVersion());
            coreGateway.writeKbGateSkip("kb-ingest:case:" + message.caseId(),
                    message.projectId(), message.operatorId(), before);
            return;
        }
        log.info("G1 case {} consumed caseId={} projectId={} version={}",
                excellent ? "excellent-case linkage" : "ingest", message.caseId(), message.projectId(), message.caseVersion());
        String dedupKey = "kb-ingest:case:" + message.caseId() + ":" + message.caseVersion()
                + ":" + message.action();
        String token = UUID.randomUUID().toString();
        ConsumeOutcome outcome = consumeSupport.consumeOnce(dedupKey, token,
                () -> excellent
                        ? idempotentRunner.runExcellent(message, token)
                        : idempotentRunner.run(message, token),
                CONSUME_LEASE_MILLIS);
        if (outcome == ConsumeOutcome.DUPLICATE) {
            log.info("G1 case {} duplicate delivery, idempotent skip caseId={}",
                    excellent ? "excellent-case linkage" : "ingest", message.caseId());
            Map<String, Object> before = new LinkedHashMap<>();
            before.put("caseId", message.caseId());
            before.put("caseVersion", message.caseVersion());
            coreGateway.writeDuplicateDrop("kb-ingest:case:" + message.caseId(),
                    message.projectId(), message.operatorId(), before);
        }
        // noise reduction: case row already deleted -> ack + skip audit
        if (outcome == ConsumeOutcome.ENTITY_GONE_SKIPPED) {
            log.info("G1 case {} skipped, entity already deleted, reducing noise caseId={}",
                    excellent ? "excellent-case linkage" : "ingest", message.caseId());
            Map<String, Object> before = new LinkedHashMap<>();
            before.put("caseId", message.caseId());
            before.put("caseVersion", message.caseVersion());
            coreGateway.writeEntityGoneSkip("kb-ingest:case:" + message.caseId(),
                    message.projectId(), message.operatorId(), before);
        }
    }

    
    private static boolean isIngestable(CaseIngestEventMessage message) {
        return message != null && message.caseId() != null && message.projectId() != null
                && (ACTION_PASS.equals(message.action()) || ACTION_EXCELLENT.equals(message.action()));
    }
}
