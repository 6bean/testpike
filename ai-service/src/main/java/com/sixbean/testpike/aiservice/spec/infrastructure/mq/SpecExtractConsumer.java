package com.sixbean.testpike.aiservice.spec.infrastructure.mq;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.common.idempotent.ConsumeOutcome;
import com.sixbean.testpike.common.idempotent.MqIdempotentConsumeSupport;
import com.sixbean.testpike.common.mq.AiSpecExtractMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;


@Component
public class SpecExtractConsumer {

    private static final Logger log = LoggerFactory.getLogger(SpecExtractConsumer.class);

    
    static final long CONSUME_LEASE_MILLIS = 15 * 60 * 1000L;

    private final SpecExtractIdempotentRunner idempotentRunner;
    private final MqIdempotentConsumeSupport consumeSupport;
    private final CoreGateway coreGateway;

    public SpecExtractConsumer(SpecExtractIdempotentRunner idempotentRunner,
                               MqIdempotentConsumeSupport consumeSupport,
                               CoreGateway coreGateway) {
        this.idempotentRunner = idempotentRunner;
        this.consumeSupport = consumeSupport;
        this.coreGateway = coreGateway;
    }

    @RabbitListener(queues = SpecExtractQueueConfig.QUEUE_NAME)
    public void onMessage(AiSpecExtractMessage message) {
        log.info("通道 B 抽取任务消费 documentId={} projectId={} modelHint={}",
                message.documentId(), message.projectId(), message.modelHint());
        String dedupKey = "ai-spec-extract:" + message.documentId() + ":" + message.publishToken();
        String token = UUID.randomUUID().toString();
        ConsumeOutcome outcome = consumeSupport.consumeOnce(dedupKey, token,
                () -> idempotentRunner.run(message, token), CONSUME_LEASE_MILLIS);
        if (outcome == ConsumeOutcome.DUPLICATE) {
            log.info("通道 B 抽取任务重复投递幂等跳过 documentId={}", message.documentId());
            Map<String, Object> before = new LinkedHashMap<>();
            before.put("documentId", message.documentId());
            before.put("publishToken", message.publishToken());
            coreGateway.writeDuplicateDrop("ai-spec-extract:" + message.documentId(),
                    message.projectId(), message.operatorId(), before);
        }
        // noise reduction: document row already deleted -> ack + skip audit
        if (outcome == ConsumeOutcome.ENTITY_GONE_SKIPPED) {
            log.info("通道 B 抽取实体已删除降噪跳过 documentId={}", message.documentId());
            Map<String, Object> before = new LinkedHashMap<>();
            before.put("documentId", message.documentId());
            before.put("publishToken", message.publishToken());
            coreGateway.writeEntityGoneSkip("ai-spec-extract:" + message.documentId(),
                    message.projectId(), message.operatorId(), before);
        }
    }
}
