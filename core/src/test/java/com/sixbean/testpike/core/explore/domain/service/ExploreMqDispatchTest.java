package com.sixbean.testpike.core.explore.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.mq.ExploreRunMessage;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.audit.domain.component.OperatorContext;
import com.sixbean.testpike.core.cost.api.dto.BudgetInput;
import com.sixbean.testpike.core.cost.domain.service.CostService;
import com.sixbean.testpike.core.events.domain.component.RabbitEventConfig;
import com.sixbean.testpike.core.events.domain.service.AiGenPublisher;
import com.sixbean.testpike.core.explore.api.controller.ExploreInternalController;
import com.sixbean.testpike.core.explore.api.dto.ExploreCreateInput;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
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
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class ExploreMqDispatchTest {

    @Autowired
    private ExploreInternalController exploreController;

    @Autowired
    private com.sixbean.testpike.core.explore.domain.service.ExploreService exploreService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private CostService costService;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @Autowired
    private MessageConverter caseEventMessageConverter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        // Fixture-scoped teardown (R37): sweep this suite's own project
        // subtrees only — blanket deletes on the shared dev database destroy
        // concurrent chains' in-flight rows (r13 postmortem).
        SharedDbFixtureSweep.byProject(jdbcTemplate,
                SharedDbFixtureSweep.projectIdByCode(jdbcTemplate, "EXP-MQ1"),
                SharedDbFixtureSweep.projectIdByCode(jdbcTemplate, "EXP-MQ2"));
    }

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    private long newProject(String code) {
        return projectService.create(1L,
                new ProjectCreateInput("探索派发" + code, code, "zh-CN", "P1", null));
    }

    
    @Test
    void create_派发探索MQ_临时队列消费载荷完整() throws Exception {
        String queueName = "test.ai-explore." + UUID.randomUUID().toString().substring(0, 8);
        amqpAdmin.declareQueue(new Queue(queueName, false, true, true));
        amqpAdmin.declareBinding(new Binding(queueName, Binding.DestinationType.QUEUE,
                RabbitEventConfig.EXCHANGE, AiGenPublisher.ROUTING_EXPLORE_RUN, null));
        try {
            long projectId = newProject("EXP-MQ1");
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader(OperatorContext.OPERATOR_ID_HEADER, "7");
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

            var response = exploreController.create(projectId,
                    new ExploreCreateInput("摸清下单流程", "http://localhost:5173"));
            long sessionId = response.getData().getId();

            var received = rabbitTemplate.receive(queueName, 5000);
            assertNotNull(received, "ai.explore.run 消息未到达临时队列");
            ExploreRunMessage msg = (ExploreRunMessage) caseEventMessageConverter.fromMessage(received);
            assertEquals(sessionId, msg.sessionId(), "载荷 sessionId=新建会话 ID");
            assertEquals(projectId, msg.projectId());
            assertEquals(7L, msg.operatorId(), "操作人经 X-Operator-Id 透传入载荷");
        } finally {
            amqpAdmin.deleteQueue(queueName);
        }
    }

    
    @Test
    void 预算耗尽_创建探索会话拒40002_不落库() {
        long projectId = newProject("EXP-MQ2");
        costService.putBudget(1L, projectId, new BudgetInput(100L, 80));
        jdbcTemplate.update("""
                INSERT INTO tp_ai_call_log (project_id, operator_id, provider, model, function,
                                            prompt_tokens, completion_tokens, cost, latency_ms)
                VALUES (?, 1, 'openai', 'gpt-a', 'explore', 100, 200, 2.0, 1000)
                """, projectId);

        ServiceException ex = assertThrows(ServiceException.class, () -> exploreService.create(1L,
                projectId, new ExploreCreateInput("预算内不该跑", "http://localhost:5173")));
        assertEquals(BizErrorCode.COST_BUDGET_EXCEEDED, ex.getCode().intValue(),
                "E3 挂点缺失=熔断项目放行探索生成（红线 D2 无旁路违例）");
        assertTrue(ex.getMessage().contains("预算已用尽"), "40002 人话同源: " + ex.getMessage());

        Long sessions = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_explore_session WHERE project_id = ?", Long.class, projectId);
        assertEquals(0L, sessions, "熔断拒启后会话不得落库");
    }
}
