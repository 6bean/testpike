package com.sixbean.testpike.aiservice.gen.infrastructure.mq;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.common.idempotent.ConsumeOutcome;
import com.sixbean.testpike.common.idempotent.MqIdempotentConsumeSupport;
import com.sixbean.testpike.common.mq.AiGenMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;


@Component
public class AiGenConsumer {

    private static final Logger log = LoggerFactory.getLogger(AiGenConsumer.class);

    
    static final long CONSUME_LEASE_MILLIS = 15 * 60 * 1000L;

    private final AiGenIdempotentRunner idempotentRunner;
    private final MqIdempotentConsumeSupport consumeSupport;
    private final CoreGateway coreGateway;

    public AiGenConsumer(AiGenIdempotentRunner idempotentRunner,
                         MqIdempotentConsumeSupport consumeSupport,
                         CoreGateway coreGateway) {
        this.idempotentRunner = idempotentRunner;
        this.consumeSupport = consumeSupport;
        this.coreGateway = coreGateway;
    }

    @RabbitListener(queues = {AiGenQueueConfig.QUEUE_NAME, AiGenQueueConfig.WINDOW_QUEUE_NAME})
    public void onMessage(AiGenMessage message) {
        log.info("AI 生成任务消费 taskId={} projectId={} taskType={}",
                message.taskId(), message.projectId(), message.taskType());
        // Dedup key carries the generation sequence (11-20 restart fix): a
        // retried/restarted task publishes the SAME taskId, and without the
        // suffix the fresh message replayed the first run's completion stamp
        // → DUPLICATE skip → the restarted queue row sat in the queued state
        // forever.
        String dedupKey = "ai-gen:" + message.taskId() + ":" + message.taskType() + ":" + message.genSeq();
        String token = UUID.randomUUID().toString();
        ConsumeOutcome outcome = consumeSupport.consumeOnce(dedupKey, token,
                () -> idempotentRunner.run(message, token), CONSUME_LEASE_MILLIS);
        if (outcome == ConsumeOutcome.DUPLICATE) {
            log.info("AI 生成任务重复投递幂等跳过 taskId={} taskType={}",
                    message.taskId(), message.taskType());
            Map<String, Object> before = new LinkedHashMap<>();
            before.put("taskId", message.taskId());
            before.put("taskType", message.taskType());
            coreGateway.writeDuplicateDrop("ai-gen:" + message.taskId(),
                    message.projectId(), message.operatorId(), before);
        }
        if (outcome == ConsumeOutcome.ENTITY_GONE_SKIPPED) {
            // noise reduction: target entity already deleted → ack + skip
            log.info("AI 生成任务实体已删除降噪跳过 taskId={} taskType={}",
                    message.taskId(), message.taskType());
            Map<String, Object> before = new LinkedHashMap<>();
            before.put("taskId", message.taskId());
            before.put("taskType", message.taskType());
            coreGateway.writeEntityGoneSkip("ai-gen:" + message.taskId(),
                    message.projectId(), message.operatorId(), before);
        }
    }
}
