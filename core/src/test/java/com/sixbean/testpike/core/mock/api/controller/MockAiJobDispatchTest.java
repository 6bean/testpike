package com.sixbean.testpike.core.mock.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.mq.MockGenMessage;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.audit.domain.component.OperatorContext;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import com.sixbean.testpike.core.events.domain.component.RabbitEventConfig;
import com.sixbean.testpike.core.events.domain.service.AiGenPublisher;
import com.sixbean.testpike.core.mock.api.dto.MockAiJobCreateInput;
import com.sixbean.testpike.core.mock.api.dto.MockAiJobInfo;
import com.sixbean.testpike.core.mock.api.dto.MockSpaceInput;
import com.sixbean.testpike.core.mock.domain.service.MockSpaceService;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AI mock-drafting MQ dispatch : the core internal
 * controller's create door is the publish point — the service only gates,
 * pins and writes the ledger; the ai.mock.gen message fires after the job
 * row is committed, never inside the transaction (ExploreInternalController
 * precedent).
 * <p>Contract under test: an accepted creation lands 'queued' AND puts one
 * identity-only MockGenMessage on ROUTING_MOCK_GEN carrying
 * jobId/spaceId/projectId/jobType/operatorId/publishToken (the consumer
 * pulls prompt + budget pin back over the internal door, so a fat message is
 * forbidden); a closed-gate creation refuses FORBIDDEN and puts NOTHING on
 * the queue — a refused job must never reach the model (red line: silent
 * dispatch of gated work).
 * <p>Real RabbitMQ + real dev database, zero mocks (ExploreMqDispatchTest
 * posture; temp queue stands in for the ai-service consumer).
 */
@SpringBootTest(classes = CoreApplication.class)
class MockAiJobDispatchTest {

    private static final String MASTER_KEY = "ai.mock.enabled";
    private static final String NL_STUB_KEY = "ai.mock.nl-stub";
    private static final String EDGE_KEY = "ai.mock.edge-scenarios";
    private static final String SPEC_SYNC_KEY = "ai.mock.spec-sync";

    @Autowired
    private MockAiJobInternalController controller;

    @Autowired
    private MockSpaceService spaceService;

    @Autowired
    private ConfigCacheHolder configCacheHolder;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @Autowired
    private MessageConverter caseEventMessageConverter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanJobs() {
        jdbcTemplate.execute("DELETE FROM tp_mock_ai_job");
    }

    @AfterEach
    void restore() {
        RequestContextHolder.resetRequestAttributes();
        for (String k : new String[]{MASTER_KEY, NL_STUB_KEY, EDGE_KEY, SPEC_SYNC_KEY}) {
            configCacheHolder.remove(k); // seeded value '0' = closed
        }
    }

    private long newProject() {
        String code = "t365d" + UUID.randomUUID().toString().replace("-", "").substring(0, 9);
        return jdbcTemplate.queryForObject(
                "INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1) RETURNING id",
                Long.class, code, code);
    }

    private long newSpace(long projectId) {
        String key = "t365d-" + UUID.randomUUID().toString().substring(0, 10);
        return spaceService.create(projectId,
                new MockSpaceInput("Space " + key, key, null, null,
                        "prefix", "none", null, null, 100, null));
    }

    private String bindTempQueue(String tag) {
        String queueName = "test.ai-mock-gen." + tag + "." + UUID.randomUUID().toString().substring(0, 6);
        amqpAdmin.declareQueue(new Queue(queueName, false, true, true));
        amqpAdmin.declareBinding(new Binding(queueName, Binding.DestinationType.QUEUE,
                RabbitEventConfig.EXCHANGE, AiGenPublisher.ROUTING_MOCK_GEN, null));
        return queueName;
    }

    /** Accepted creation → queued row + one complete MockGenMessage on ai.mock.gen. */
    @Test
    void create_publishes_mockgen_with_identity_payload() throws Exception {
        String queueName = bindTempQueue("ok");
        try {
            long projectId = newProject();
            long spaceId = newSpace(projectId);
            configCacheHolder.put(MASTER_KEY, "1");
            configCacheHolder.put(NL_STUB_KEY, "1");

            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader(OperatorContext.OPERATOR_ID_HEADER, "7");
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

            Response<MockAiJobInfo> resp = controller.create(
                    new MockAiJobCreateInput(spaceId, "nl-stub", "订单接口超时返回 5001"));
            MockAiJobInfo job = resp.getData();
            assertEquals("queued", job.status(), "accepted job lands queued");

            var received = rabbitTemplate.receive(queueName, 5000);
            assertNotNull(received, "ai.mock.gen message missing after accepted create");
            MockGenMessage msg = (MockGenMessage) caseEventMessageConverter.fromMessage(received);
            assertEquals(job.id(), msg.jobId(), "payload jobId = ledger row (single source of truth)");
            assertEquals(spaceId, msg.spaceId());
            assertEquals(projectId, msg.projectId(), "projectId resolved from the space, never guessed");
            assertEquals("nl-stub", msg.jobType());
            assertEquals(7L, msg.operatorId(), "operator travels via X-Operator-Id");
            assertNotNull(msg.publishToken());
            assertTrue(msg.publishToken().length() >= 8, "publish token = per-trigger dedup key");
        } finally {
            amqpAdmin.deleteQueue(queueName);
        }
    }

    /** Closed master gate → FORBIDDEN refusal, zero rows, and NO message on the queue. */
    @Test
    void gate_closed_create_refused_and_nothing_published() throws Exception {
        String queueName = bindTempQueue("gate");
        try {
            long projectId = newProject();
            long spaceId = newSpace(projectId);
            // seeded state: master closed (restore() removes overrides; nothing opened here)

            ServiceException ex = assertThrows(ServiceException.class, () -> controller.create(
                    new MockAiJobCreateInput(spaceId, "nl-stub", "被闸拦下的草稿请求")));
            assertEquals(BizErrorCode.FORBIDDEN, ex.getCode().intValue(),
                    "closed gate must refuse FORBIDDEN naming the key");
            assertTrue(ex.getMessage().contains(MASTER_KEY), "plain words name the exact key");

            Integer rows = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM tp_mock_ai_job WHERE space_id = ?", Integer.class, spaceId);
            assertEquals(0, rows.intValue(), "refused creation leaves zero job rows");
            assertNull(rabbitTemplate.receive(queueName, 1500),
                    "a gated job must never reach the queue — no silent dispatch");
        } finally {
            amqpAdmin.deleteQueue(queueName);
        }
    }
}
