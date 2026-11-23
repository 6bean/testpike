package com.sixbean.testpike.aiservice.knowledge.infrastructure.mq;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.common.idempotent.ConsumeOutcome;
import com.sixbean.testpike.common.idempotent.MqIdempotentConsumeSupport;
import com.sixbean.testpike.common.mq.AiKbIngestMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;


@Component
public class KbIngestConsumer {

    private static final Logger log = LoggerFactory.getLogger(KbIngestConsumer.class);

    
    static final long CONSUME_LEASE_MILLIS = 15 * 60 * 1000L;

    private final KbIngestIdempotentRunner idempotentRunner;
    private final MqIdempotentConsumeSupport consumeSupport;
    private final CoreGateway coreGateway;

    public KbIngestConsumer(KbIngestIdempotentRunner idempotentRunner,
                            MqIdempotentConsumeSupport consumeSupport,
                            CoreGateway coreGateway) {
        this.idempotentRunner = idempotentRunner;
        this.consumeSupport = consumeSupport;
        this.coreGateway = coreGateway;
    }

    @RabbitListener(queues = KbIngestQueueConfig.QUEUE_NAME)
    public void onMessage(AiKbIngestMessage message) {
        log.info("G1 文档入库消费 documentId={} projectId={}",
                message.documentId(), message.projectId());
        String dedupKey = "kb-ingest:doc:" + message.documentId() + ":" + message.operatorId()
                + ":" + message.occurredAt();
        String token = UUID.randomUUID().toString();
        ConsumeOutcome outcome = consumeSupport.consumeOnce(dedupKey, token,
                () -> idempotentRunner.run(message, token), CONSUME_LEASE_MILLIS);
        if (outcome == ConsumeOutcome.DUPLICATE) {
            log.info("G1 文档入库重复投递幂等跳过 documentId={}", message.documentId());
            Map<String, Object> before = new LinkedHashMap<>();
            before.put("documentId", message.documentId());
            before.put("occurredAt", message.occurredAt());
            coreGateway.writeDuplicateDrop("kb-ingest:doc:" + message.documentId(),
                    message.projectId(), message.operatorId(), before);
        }
        // noise reduction: document row already deleted -> ack + skip audit
        if (outcome == ConsumeOutcome.ENTITY_GONE_SKIPPED) {
            log.info("G1 文档入库实体已删除降噪跳过 documentId={}", message.documentId());
            Map<String, Object> before = new LinkedHashMap<>();
            before.put("documentId", message.documentId());
            before.put("occurredAt", message.occurredAt());
            coreGateway.writeEntityGoneSkip("kb-ingest:doc:" + message.documentId(),
                    message.projectId(), message.operatorId(), before);
        }
    }
}
