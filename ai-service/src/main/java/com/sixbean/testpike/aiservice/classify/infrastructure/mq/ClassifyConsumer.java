package com.sixbean.testpike.aiservice.classify.infrastructure.mq;

import com.sixbean.testpike.aiservice.classify.domain.service.FailureClassifyService;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.common.idempotent.ConsumeOutcome;
import com.sixbean.testpike.common.idempotent.MqIdempotentConsumeSupport;
import com.sixbean.testpike.common.mq.ExecFailedEventMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;


@Component
public class ClassifyConsumer {

    private static final Logger log = LoggerFactory.getLogger(ClassifyConsumer.class);

    
    static final long CONSUME_LEASE_MILLIS = 15 * 60 * 1000L;

    private final ClassifyIdempotentRunner idempotentRunner;
    private final MqIdempotentConsumeSupport consumeSupport;
    private final CoreGateway coreGateway;

    public ClassifyConsumer(ClassifyIdempotentRunner idempotentRunner,
                            MqIdempotentConsumeSupport consumeSupport,
                            CoreGateway coreGateway) {
        this.idempotentRunner = idempotentRunner;
        this.consumeSupport = consumeSupport;
        this.coreGateway = coreGateway;
    }

    @RabbitListener(queues = ClassifyQueueConfig.QUEUE_NAME)
    public void onMessage(ExecFailedEventMessage message) {
        if (!isClassifiable(message)) {
            return;
        }
        log.info("C3 失败归因消费 taskId={} caseId={} projectId={} errorType={}",
                message.execTaskId(), message.caseId(), message.projectId(),
                message.execErrorType());
        String dedupKey = "exec-classify:task:" + message.execTaskId();
        String token = UUID.randomUUID().toString();
        ConsumeOutcome outcome = consumeSupport.consumeOnce(dedupKey, token,
                () -> idempotentRunner.run(message, token), CONSUME_LEASE_MILLIS);
        if (outcome == ConsumeOutcome.DUPLICATE) {
            log.info("C3 失败归因重复投递幂等跳过 taskId={}", message.execTaskId());
            Map<String, Object> before = new LinkedHashMap<>();
            before.put("execTaskId", message.execTaskId());
            before.put("caseId", message.caseId());
            coreGateway.writeDuplicateDrop(dedupKey, message.projectId(),
                    message.operatorId(), before);
        }
        // noise reduction: target entity already deleted -> ack + skip audit
        if (outcome == ConsumeOutcome.ENTITY_GONE_SKIPPED) {
            log.info("C3 失败归因实体已删除降噪跳过 taskId={}", message.execTaskId());
            Map<String, Object> before = new LinkedHashMap<>();
            before.put("execTaskId", message.execTaskId());
            before.put("caseId", message.caseId());
            coreGateway.writeEntityGoneSkip(dedupKey, message.projectId(),
                    message.operatorId(), before);
        }
    }

    
    private static boolean isClassifiable(ExecFailedEventMessage message) {
        return message != null && message.execTaskId() != null && message.projectId() != null;
    }
}
