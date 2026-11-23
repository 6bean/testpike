package com.sixbean.testpike.aiservice.explore.infrastructure.mq;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.common.idempotent.ConsumeOutcome;
import com.sixbean.testpike.common.idempotent.MqIdempotentConsumeSupport;
import com.sixbean.testpike.common.mq.ExploreRunMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;


@Component
public class ExploreConsumer {

    private static final Logger log = LoggerFactory.getLogger(ExploreConsumer.class);

    
    static final long CONSUME_LEASE_MILLIS = 30 * 60 * 1000L;

    private final ExploreIdempotentRunner idempotentRunner;
    private final MqIdempotentConsumeSupport consumeSupport;
    private final CoreGateway coreGateway;

    public ExploreConsumer(ExploreIdempotentRunner idempotentRunner,
                           MqIdempotentConsumeSupport consumeSupport,
                           CoreGateway coreGateway) {
        this.idempotentRunner = idempotentRunner;
        this.consumeSupport = consumeSupport;
        this.coreGateway = coreGateway;
    }

    @RabbitListener(queues = ExploreQueueConfig.QUEUE_NAME)
    public void onMessage(ExploreRunMessage message) {
        log.info("B9 探索任务消费 sessionId={} projectId={}",
                message.sessionId(), message.projectId());
        String dedupKey = "ai-explore:" + message.sessionId();
        String token = UUID.randomUUID().toString();
        ConsumeOutcome outcome = consumeSupport.consumeOnce(dedupKey, token,
                () -> idempotentRunner.run(message, token), CONSUME_LEASE_MILLIS);
        if (outcome == ConsumeOutcome.DUPLICATE) {
            log.info("B9 探索任务重复投递幂等跳过 sessionId={}", message.sessionId());
            Map<String, Object> before = new LinkedHashMap<>();
            before.put("sessionId", message.sessionId());
            before.put("projectId", message.projectId());
            coreGateway.writeDuplicateDrop(dedupKey, message.projectId(),
                    message.operatorId(), before);
        }
        // noise reduction: explore session already deleted -> ack + skip audit
        if (outcome == ConsumeOutcome.ENTITY_GONE_SKIPPED) {
            log.info("B9 探索任务实体已删除降噪跳过 sessionId={}", message.sessionId());
            Map<String, Object> before = new LinkedHashMap<>();
            before.put("sessionId", message.sessionId());
            before.put("projectId", message.projectId());
            coreGateway.writeEntityGoneSkip(dedupKey, message.projectId(),
                    message.operatorId(), before);
        }
    }
}
