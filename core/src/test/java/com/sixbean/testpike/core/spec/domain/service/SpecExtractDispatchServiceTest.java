package com.sixbean.testpike.core.spec.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.mq.AiSpecExtractMessage;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.config.api.dto.ConfigInput;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import com.sixbean.testpike.core.document.api.dto.DocumentInfo;
import com.sixbean.testpike.core.document.domain.service.DocumentService;
import com.sixbean.testpike.core.events.domain.component.RabbitEventConfig;
import com.sixbean.testpike.core.events.domain.service.AiGenPublisher;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.spec.api.dto.SpecExtractEstimate;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
@TestPropertySource(properties = {"testpike.scheduling.enabled=false"})
class SpecExtractDispatchServiceTest {

    @Autowired
    private SpecExtractDispatchService dispatchService;
    @Autowired
    private DocumentService documentService;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private ConfigService configService;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private AmqpAdmin amqpAdmin;
    @Autowired
    private MessageConverter caseEventMessageConverter;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String code;

    @BeforeEach
    @AfterEach
    void cleanTables() {
        jdbcTemplate.execute("DELETE FROM tp_sys_config WHERE config_key='"
                + AiSpecExtractMessage.KEY_MAX_CHUNKS + "'");
        jdbcTemplate.execute("DELETE FROM tp_sys_config WHERE config_key='"
                + AiSpecExtractMessage.KEY_GROUPED_INTAKE_ENABLED + "'");
        // Fixture-scoped teardown (R37): documents/chunks/requirements hang
        // off the tracked fixture projects — blanket TRUNCATE wiped other
        // chains' in-flight rows on the shared dev database (r13 postmortem).
        SharedDbFixtureSweep.byProjectList(jdbcTemplate, fixtureProjectIds);
        fixtureProjectIds.clear();
    }

    private final List<Long> fixtureProjectIds = new ArrayList<>();

    private long createProject() {
        code = UUID.randomUUID().toString().substring(0, 8);
        long id = projectService.create(1L,
                new ProjectCreateInput("项目" + code, code, "zh-CN", "P1", null));
        fixtureProjectIds.add(id);
        return id;
    }

    
    private long uploadDoc(long projectId) {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String md = "# 接口设计（" + tag + "）\n\n## 下单接口\n\nPOST /orders 创建订单。\n\n"
                + "## 查询接口\n\nGET /orders 查询订单列表。\n";
        DocumentInfo info = documentService.upload(1L, projectId,
                "SPEC-" + tag + ".md", md.getBytes(StandardCharsets.UTF_8), null);
        assertEquals(1, info.status(), "前置文档必须解析成功");
        return info.id();
    }

    private int docStatus(long docId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM tp_document WHERE id=?", Integer.class, docId);
    }

    
    @Test
    void test_dispatch_publishes_spec_extract_and_keeps_status() {
        long projectId = createProject();
        long docId = uploadDoc(projectId);

        String queueName = "test.spec-extract." + UUID.randomUUID().toString().substring(0, 8);
        Queue queue = new Queue(queueName, false, true, true);
        amqpAdmin.declareQueue(queue);
        amqpAdmin.declareBinding(new Binding(queueName, Binding.DestinationType.QUEUE,
                RabbitEventConfig.EXCHANGE, AiGenPublisher.ROUTING_SPEC_EXTRACT, null));
        try {
            SpecExtractEstimate est = dispatchService.dispatch(1L, docId, "deepseek-v4-flash");
            assertTrue(est.chunks() >= 2, "多章节文档估算块数应≥2，实得 " + est.chunks());
            assertTrue(est.estimatedTokens() > 0, "预估 token 必须为正（成本确认卡数据源）");
            assertEquals(AiSpecExtractMessage.DEFAULT_MAX_CHUNKS, est.maxChunks(),
                    "K8 键缺失时上限回退默认 50（读侧默认值兜底=权威口径）");

            var received = rabbitTemplate.receive(queueName, 5000);
            assertNotNull(received, "通道 B 派发必须经 ai.spec.extract 路由键真实入队");
            AiSpecExtractMessage msg = (AiSpecExtractMessage)
                    caseEventMessageConverter.fromMessage(received);
            assertEquals(docId, msg.documentId());
            assertEquals(projectId, msg.projectId());
            assertEquals(1L, msg.operatorId());
            assertEquals("deepseek-v4-flash", msg.modelHint());
            assertNotNull(msg.publishToken(), "每次派发新 token=重触发即新任务语义");
            assertEquals(est.chunks(), documentService.chunks(docId).size(),
                    "估算块数=分块表现算，与消费端同源");
            assertEquals(1, docStatus(docId), "通道 B 无任务态：派发不得改写文档 status");
        } finally {
            amqpAdmin.deleteQueue(queueName);
        }
    }

    
    @Test
    void test_dispatch_repeat_publishes_new_token() {
        long projectId = createProject();
        long docId = uploadDoc(projectId);
        String queueName = "test.spec-extract2." + UUID.randomUUID().toString().substring(0, 8);
        Queue queue = new Queue(queueName, false, true, false);
        amqpAdmin.declareQueue(queue);
        amqpAdmin.declareBinding(new Binding(queueName, Binding.DestinationType.QUEUE,
                RabbitEventConfig.EXCHANGE, AiGenPublisher.ROUTING_SPEC_EXTRACT, null));
        try {
            dispatchService.dispatch(1L, docId, null);
            dispatchService.dispatch(1L, docId, null);
            var first = rabbitTemplate.receive(queueName, 5000);
            assertNotNull(first, "第一次派发必须入队（队列先声明=不丢消息）");
            var second = rabbitTemplate.receive(queueName, 5000);
            assertNotNull(second, "第二次派发同样入队（F2 式状态互斥在通道 B 不适用）");
            AiSpecExtractMessage m1 = (AiSpecExtractMessage)
                    caseEventMessageConverter.fromMessage(first);
            AiSpecExtractMessage m2 = (AiSpecExtractMessage)
                    caseEventMessageConverter.fromMessage(second);
            assertNotNull(m1.publishToken());
            assertNotNull(m2.publishToken(), "重触发必须携带新 token（消费端幂等放行依据）");
            assertTrue(!m1.publishToken().equals(m2.publishToken()),
                    "两次派发的 token 必须不同（同 token=被消费端去重误吞）");
        } finally {
            amqpAdmin.deleteQueue(queueName);
        }
    }

    
    @Test
    void test_dispatch_rejects_failed_parse_document() {
        long projectId = createProject();
        long docId = uploadDoc(projectId);
        jdbcTemplate.update("UPDATE tp_document SET status=2 WHERE id=?", docId);
        ServiceException e = assertThrows(ServiceException.class,
                () -> dispatchService.dispatch(1L, docId, null));
        assertEquals(BizErrorCode.STATE_MACHINE_REJECT, e.getCode());
        assertTrue(e.getMessage().contains("解析"), "失败原因必须人话可读：" + e.getMessage());
    }

    
    @Test
    void test_dispatch_rejects_over_chunk_limit_with_key_name() {
        long projectId = createProject();
        long docId = uploadDoc(projectId);
        var gate = configService.create(1L, new ConfigInput(
                AiSpecExtractMessage.KEY_GROUPED_INTAKE_ENABLED, "0", "系统", "int", 0, "分组摄入门"));
        var created = configService.create(1L, new ConfigInput(
                AiSpecExtractMessage.KEY_MAX_CHUNKS, "1", "系统", "int", 0, "抽取块上限"));
        try {
            ServiceException e = assertThrows(ServiceException.class,
                    () -> dispatchService.dispatch(1L, docId, null));
            assertEquals(BizErrorCode.VALIDATION_ERROR, e.getCode());
            assertTrue(e.getMessage().contains(AiSpecExtractMessage.KEY_MAX_CHUNKS),
                    "超限人话必须含 K8 键名供用户自助调整：" + e.getMessage());
            assertTrue(e.getMessage().contains("1"), "人话含当前配置值：" + e.getMessage());
        } finally {
            configService.delete(1L, created.id());
            configService.delete(1L, gate.id());
        }
    }

    /**
 * fix piece: while the grouped-intake door is on (factory
 * default — a missing row keeps it on), an over-count chunk set is
 * published instead of rejected: the ai-service consumer door seals the
 * chunks into token-bounded groups and owns the final say, so the
 * dispatch pre-check must not shadow it.
 */
    @Test
    void test_dispatch_over_limit_passes_when_grouped_intake_row_absent() {
        long projectId = createProject();
        long docId = uploadDoc(projectId);
        var created = configService.create(1L, new ConfigInput(
                AiSpecExtractMessage.KEY_MAX_CHUNKS, "1", "系统", "int", 0, "抽取块上限"));
        String queueName = "test.spec-grouped." + UUID.randomUUID().toString().substring(0, 8);
        Queue queue = new Queue(queueName, false, true, true);
        amqpAdmin.declareQueue(queue);
        amqpAdmin.declareBinding(new Binding(queueName, Binding.DestinationType.QUEUE,
                RabbitEventConfig.EXCHANGE, AiGenPublisher.ROUTING_SPEC_EXTRACT, null));
        try {
            SpecExtractEstimate est = dispatchService.dispatch(1L, docId, null);
            assertTrue(est.chunks() > 1,
                    "门开态超限文档应放行派发（块数应>1），实得 " + est.chunks());
            assertNotNull(rabbitTemplate.receive(queueName, 5000),
                    "门开态超限派发必须经 ai.spec.extract 真实入队，交消费端分组门终裁");
        } finally {
            amqpAdmin.deleteQueue(queueName);
            configService.delete(1L, created.id());
        }
    }

    /** Door-off accepts the literal "false" too (same tri-state face as the consumer door). */
    @Test
    void test_dispatch_over_limit_rejects_when_grouped_intake_false() {
        long projectId = createProject();
        long docId = uploadDoc(projectId);
        var gate = configService.create(1L, new ConfigInput(
                AiSpecExtractMessage.KEY_GROUPED_INTAKE_ENABLED, "false", "系统", "string", 0, "分组摄入门"));
        var created = configService.create(1L, new ConfigInput(
                AiSpecExtractMessage.KEY_MAX_CHUNKS, "1", "系统", "int", 0, "抽取块上限"));
        try {
            ServiceException e = assertThrows(ServiceException.class,
                    () -> dispatchService.dispatch(1L, docId, null));
            assertEquals(BizErrorCode.VALIDATION_ERROR, e.getCode());
            assertTrue(e.getMessage().contains("拆分为多篇"),
                    "关态必须维持旧字面人话拒：" + e.getMessage());
        } finally {
            configService.delete(1L, created.id());
            configService.delete(1L, gate.id());
        }
    }

    
    @Test
    void test_dispatch_rejects_missing_document() {
        ServiceException e = assertThrows(ServiceException.class,
                () -> dispatchService.dispatch(1L, 999_999_999L, null));
        assertEquals(BizErrorCode.NOT_FOUND, e.getCode());
    }

    
    @Test
    void test_dispatch_estimate_shape_for_cost_card() {
        long projectId = createProject();
        long docId = uploadDoc(projectId);
        SpecExtractEstimate est = dispatchService.dispatch(1L, docId, null);
        List<?> chunks = documentService.chunks(docId);
        assertTrue(est.estimatedTokens() >= chunks.size() * 800L,
                "预估 token 至少覆盖逐块固定开销（成本确认卡可解释性）");
    }
}
