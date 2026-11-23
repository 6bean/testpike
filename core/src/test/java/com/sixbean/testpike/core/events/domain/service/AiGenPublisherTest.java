package com.sixbean.testpike.core.events.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.ai.api.dto.AiTaskCreateInput;
import com.sixbean.testpike.core.ai.api.dto.AiTaskInfo;
import com.sixbean.testpike.core.ai.domain.service.AiTaskService;
import com.sixbean.testpike.common.mq.AiGenMessage;
import com.sixbean.testpike.core.events.domain.component.RabbitEventConfig;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.support.ProjectFixtureRig;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class AiGenPublisherTest {

    @Autowired
    private AiTaskService aiTaskService;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @Autowired
    private MessageConverter caseEventMessageConverter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
 * R37 fixture-scoped teardown : the old blanket TRUNCATE
 * wiped shipped ai tasks, notifications and the whole audit trail of the
 * shared dev database. Every row this suite seeds hangs off its own
 * fixture project - tp_ai_task through the extra project-keyed table,
 * tp_audit_log/tp_notification through the default sweep - so the
 * tracked-project sweep covers the whole footprint with no global wipe.
 */
    private final List<Long> trackedProjects = new ArrayList<>();

    @BeforeEach
    @AfterEach
    void cleanFixtureRows() {
        SharedDbFixtureSweep.byProjectList(jdbcTemplate, List.of("tp_ai_task"), trackedProjects);
        trackedProjects.clear();
    }

    /** Fixed-code fixture project, self-healed through the shared rig. */
    private long fixtureProject() {
        return ProjectFixtureRig.createSelfHealByCode(jdbcTemplate, projectService, 1L,
                trackedProjects, "AIGPUB");
    }

    @Test
    void createTask_发布MQ消息_临时队列可消费且载荷完整() throws Exception {
        long projectId = fixtureProject();
        String queueName = "test.ai-gen." + UUID.randomUUID().toString().substring(0, 8);
        Queue queue = new Queue(queueName, false, true, true);
        amqpAdmin.declareQueue(queue);
        amqpAdmin.declareBinding(new Binding(queueName, Binding.DestinationType.QUEUE,
                RabbitEventConfig.EXCHANGE, AiGenPublisher.ROUTING_CASE_GENERATE, null));

        try {
            long taskId = aiTaskService.create(new AiTaskCreateInput(projectId, 5L, 1, "用户登录功能", null));
            rabbitTemplate.convertAndSend(RabbitEventConfig.EXCHANGE,
                    AiGenPublisher.ROUTING_CASE_GENERATE,
                    new AiGenMessage(taskId, projectId, 5L, 1, "用户登录功能", null, null));

            var received = rabbitTemplate.receive(queueName, 5000);
            assertNotNull(received, "MQ 消息未到达临时队列");
            AiGenMessage msg = (AiGenMessage) caseEventMessageConverter.fromMessage(received);
            assertEquals(taskId, msg.taskId());
            assertEquals(projectId, msg.projectId());
            assertEquals(5L, msg.operatorId());
            assertEquals("用户登录功能", msg.requirementText());
            assertTrue(msg.requirementId() == null);
        } finally {
            amqpAdmin.deleteQueue(queueName);
        }
    }

    @Test
    void createTask_任务落库pending_状态可回写done() {
        long projectId = fixtureProject();
        long taskId = aiTaskService.create(new AiTaskCreateInput(projectId, 5L, 1, "登录", null));

        AiTaskInfo task = aiTaskService.get(taskId);
        assertEquals(1, task.status());     // pending

        aiTaskService.markRunning(taskId);
        aiTaskService.markDone(taskId, 777L);

        AiTaskInfo done = aiTaskService.get(taskId);
        assertEquals(3, done.status());     // done
        assertEquals(777L, done.caseId());
    }
}
