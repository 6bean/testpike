package com.sixbean.testpike.aiservice.sample.infrastructure.mq;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.sample.domain.service.TrainingSampleService;
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
public class CaseSampleConsumer {

    private static final Logger log = LoggerFactory.getLogger(CaseSampleConsumer.class);

    
    static final String ACTION_REJECT = "REJECT";
    static final String ACTION_PASS = "PASS";

    
    static final long CONSUME_LEASE_MILLIS = 15 * 60 * 1000L;

    private final CaseSampleIdempotentRunner idempotentRunner;
    private final MqIdempotentConsumeSupport consumeSupport;
    private final CoreGateway coreGateway;

    public CaseSampleConsumer(CaseSampleIdempotentRunner idempotentRunner,
                              MqIdempotentConsumeSupport consumeSupport,
                              CoreGateway coreGateway) {
        this.idempotentRunner = idempotentRunner;
        this.consumeSupport = consumeSupport;
        this.coreGateway = coreGateway;
    }

    @RabbitListener(queues = CaseSampleQueueConfig.QUEUE_NAME)
    public void onMessage(CaseIngestEventMessage message) {
        if (!isCapturable(message)) {
            return;
        }
        log.info("D2 样本沉淀消费 caseId={} projectId={} version={} action={}",
                message.caseId(), message.projectId(), message.caseVersion(), message.action());
        String dedupKey = "sample-ingest:case:" + message.caseId() + ":" + message.caseVersion()
                + ":" + message.action();
        String token = UUID.randomUUID().toString();
        ConsumeOutcome outcome = consumeSupport.consumeOnce(dedupKey, token,
                () -> idempotentRunner.run(message, token), CONSUME_LEASE_MILLIS);
        if (outcome == ConsumeOutcome.DUPLICATE) {
            log.info("D2 样本沉淀重复投递幂等跳过 caseId={}", message.caseId());
            Map<String, Object> before = new LinkedHashMap<>();
            before.put("caseId", message.caseId());
            before.put("caseVersion", message.caseVersion());
            before.put("action", message.action());
            coreGateway.writeDuplicateDrop("sample-ingest:case:" + message.caseId(),
                    message.projectId(), message.operatorId(), before);
        }
        // noise reduction: target case already deleted -> ack + skip audit
        if (outcome == ConsumeOutcome.ENTITY_GONE_SKIPPED) {
            log.info("D2 样本沉淀实体已删除降噪跳过 caseId={}", message.caseId());
            Map<String, Object> before = new LinkedHashMap<>();
            before.put("caseId", message.caseId());
            before.put("caseVersion", message.caseVersion());
            before.put("action", message.action());
            coreGateway.writeEntityGoneSkip("sample-ingest:case:" + message.caseId(),
                    message.projectId(), message.operatorId(), before);
        }
    }

    
    private static boolean isCapturable(CaseIngestEventMessage message) {
        return message != null && message.caseId() != null && message.projectId() != null
                && (ACTION_REJECT.equals(message.action()) || ACTION_PASS.equals(message.action()));
    }
}
