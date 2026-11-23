package com.sixbean.testpike.core.events.domain.service;

import com.sixbean.testpike.common.mq.AiKbIngestMessage;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.events.domain.component.RabbitEventConfig;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;


@SpringBootTest(classes = CoreApplication.class)
@TestPropertySource(properties = {
        "testpike.scheduling.enabled=false"})
class KbIngestPublisherTest {

    @Autowired
    private AiGenPublisher aiGenPublisher;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @Autowired
    private MessageConverter caseEventMessageConverter;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private Queue declareTempQueue(String routingKey) {
        Queue queue = new Queue("tp.test." + UUID.randomUUID(), false, true, true);
        amqpAdmin.declareQueue(queue);
        amqpAdmin.declareBinding(new Binding(queue.getName(), Binding.DestinationType.QUEUE,
                RabbitEventConfig.EXCHANGE, routingKey, null));
        return queue;
    }

    private AiKbIngestMessage awaitMessage(Queue queue) {
        Message msg = rabbitTemplate.receive(queue.getName(), 5000);
        return msg == null ? null : (AiKbIngestMessage) caseEventMessageConverter.fromMessage(msg);
    }


    @Test
    void publishKbIngest_知识库入库消息真实到达_字段完整() {
        Queue queue = declareTempQueue(AiGenPublisher.ROUTING_KB_INGEST);
        aiGenPublisher.publishKbIngest(new AiKbIngestMessage(42L, 7L, 9L, System.currentTimeMillis(),
                "DDL_DICT"));

        AiKbIngestMessage evt = awaitMessage(queue);
        assertNotNull(evt, "ai.kb.ingest 消息必须真实到达（T176a）");
        assertEquals(42L, evt.documentId());
        assertEquals(7L, evt.projectId(), "projectId 贯穿（H1）");
        assertEquals(9L, evt.operatorId());
        assertNotNull(evt.occurredAt(), "occurredAt 时间戳必填（消费端幂等键组成）");
        assertEquals("DDL_DICT", evt.category(), "category 随消息透传（T193 包②′）");
    }


    @Test
    void publishKbIngest_事务回滚_无消息发出() {
        Queue queue = declareTempQueue(AiGenPublisher.ROUTING_KB_INGEST);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        assertThrows(IllegalStateException.class, () -> tx.execute(status -> {
            aiGenPublisher.publishKbIngest(
                    new AiKbIngestMessage(43L, 7L, 9L, System.currentTimeMillis(), null));
            throw new IllegalStateException("模拟业务回滚");
        }));

        assertNull(awaitMessage(queue), "事务回滚后不得有入库消息（afterCommit 不触发）");
    }
}
