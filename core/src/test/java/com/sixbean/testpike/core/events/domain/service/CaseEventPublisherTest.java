package com.sixbean.testpike.core.events.domain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.events.api.dto.CaseEventMessage;
import com.sixbean.testpike.core.events.api.dto.CaseEventType;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class CaseEventPublisherTest {

    @Autowired
    private CaseEventPublisher caseEventPublisher;

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

    private CaseEventMessage awaitMessage(Queue queue) {
        Message msg = rabbitTemplate.receive(queue.getName(), 5000);
        return msg == null ? null : (CaseEventMessage) caseEventMessageConverter.fromMessage(msg);
    }

    private CaseEventMessage reviewEvent(Long caseId, Long projectId, Long operatorId,
                                         String action, String remark) {
        return new CaseEventMessage(CaseEventType.REVIEW_CHANGED.name(), caseId, projectId,
                operatorId, action, remark, null, null, null, System.currentTimeMillis(), 3);
    }


    @Test
    void publish_评审通过_REVIEW_CHANGED事件真实到达_字段完整() {
        Queue queue = declareTempQueue(CaseEventType.REVIEW_CHANGED.routingKey());
        caseEventPublisher.publish(reviewEvent(42L, 7L, 9L, "PASS", null));

        CaseEventMessage evt = awaitMessage(queue);
        assertNotNull(evt, "REVIEW_CHANGED 事件必须真实到达（TC-MQ-01）");
        assertEquals(CaseEventType.REVIEW_CHANGED.name(), evt.eventType());
        assertEquals(42L, evt.caseId());
        assertEquals(7L, evt.projectId(), "projectId 贯穿（H1）");
        assertEquals(9L, evt.operatorId());
        assertEquals("PASS", evt.action());
        assertNotNull(evt.occurredAt(), "occurredAt 时间戳必填");
        assertEquals(3, evt.caseVersion(), "T176a 契约扩展：评审事件携带变更后版本号（消费端幂等键）");
    }


    @Test
    void publish_评审需修改_remark透传() {
        Queue queue = declareTempQueue(CaseEventType.REVIEW_CHANGED.routingKey());
        caseEventPublisher.publish(reviewEvent(43L, 7L, 9L, "NEED_CHANGE", "步骤 2 预期不明确，请补充"));

        CaseEventMessage evt = awaitMessage(queue);
        assertNotNull(evt, "REVIEW_CHANGED 事件必须真实到达（TC-MQ-02）");
        assertEquals("NEED_CHANGE", evt.action());
        assertEquals("步骤 2 预期不明确，请补充", evt.remark(), "评审意见透传（07 事件表载荷）");
    }


    @Test
    void publish_单条分配_ASSIGNED事件字段完整() {
        Queue queue = declareTempQueue(CaseEventType.ASSIGNED.routingKey());
        caseEventPublisher.publish(new CaseEventMessage(CaseEventType.ASSIGNED.name(), 50L, 7L,
            9L, "ASSIGN", null, 5L, null, null, System.currentTimeMillis(), null));

        CaseEventMessage evt = awaitMessage(queue);
        assertNotNull(evt, "ASSIGNED 事件必须真实到达（TC-MQ-03）");
        assertEquals(50L, evt.caseId());
        assertEquals(5L, evt.assigneeId());
        assertEquals(9L, evt.operatorId());
    }


    @Test
    void publish_批量分配_BATCH_ASSIGNED摘要事件() {
        Queue queue = declareTempQueue(CaseEventType.BATCH_ASSIGNED.routingKey());
        List<Long> ids = List.of(51L, 52L, 53L, 54L, 55L);
        caseEventPublisher.publish(new CaseEventMessage(CaseEventType.BATCH_ASSIGNED.name(), null,
                7L, 9L, "BATCH_ASSIGN", null, null, ids, ids.size(), System.currentTimeMillis(), null));

        CaseEventMessage evt = awaitMessage(queue);
        assertNotNull(evt, "BATCH_ASSIGNED 事件必须真实到达（TC-MQ-04）");
        assertEquals(5, evt.batchSize());
        assertEquals(ids, evt.caseIds(), "批量明细完整保留");
        assertEquals(7L, evt.projectId());
        assertNull(evt.caseId(), "批量事件无单对象 caseId");
    }


    @Test
    void publish_事务回滚_无事件发出() {
        Queue queue = declareTempQueue(CaseEventType.REVIEW_CHANGED.routingKey());
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        assertThrows(IllegalStateException.class, () -> tx.execute(status -> {
            caseEventPublisher.publish(reviewEvent(60L, 7L, 9L, "PASS", null));
            throw new IllegalStateException("模拟业务回滚");
        }));

        assertNull(awaitMessage(queue), "事务回滚后不得有事件（afterCommit 不触发，TC-MQ-05）");
    }


    @Test
    void publish_消息体纯JSON_跨语言可解析() throws Exception {
        Queue queue = declareTempQueue(CaseEventType.REVIEW_CHANGED.routingKey());
        caseEventPublisher.publish(reviewEvent(70L, 7L, 9L, "PASS", "备注"));

        Message msg = rabbitTemplate.receive(queue.getName(), 5000);
        assertNotNull(msg, "原始消息必须可拉取");
        JsonNode node = new ObjectMapper().readTree(msg.getBody());
        assertEquals("REVIEW_CHANGED", node.get("eventType").asText());
        assertEquals(70L, node.get("caseId").asLong());
        assertEquals(7L, node.get("projectId").asLong());
        assertEquals("备注", node.get("remark").asText());
        assertTrue(node.has("occurredAt"), "occurredAt 字段存在（时间戳）");
        assertTrue(node.get("occurredAt").canConvertToLong(), "occurredAt 为数字时间戳");
    }
}
