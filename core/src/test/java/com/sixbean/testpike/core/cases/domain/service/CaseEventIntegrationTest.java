package com.sixbean.testpike.core.cases.domain.service;

import com.sixbean.testpike.common.exception.OptimisticLockingException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.api.dto.CaseStepInput;
import com.sixbean.testpike.core.cases.domain.component.ReviewAction;
import com.sixbean.testpike.core.events.api.dto.CaseEventMessage;
import com.sixbean.testpike.core.events.api.dto.CaseEventType;
import com.sixbean.testpike.core.events.domain.component.RabbitEventConfig;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.support.ProjectFixtureRig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;


@SpringBootTest(classes = CoreApplication.class)
class CaseEventIntegrationTest {

    @Autowired
    private CaseService caseService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @Autowired
    private MessageConverter caseEventMessageConverter;

    /** Fixture projects of this suite; the teardown sweeps only these rows. */
    private final java.util.List<Long> trackedProjects =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    @BeforeEach
    @AfterEach
    void cleanFixtureRows() {
        // Shared dev database (R37): purge only the rows reachable from this
        // run's tracked fixture projects (cases, steps, memberships, audit) —
        // never a whole-table wipe of tables the concurrent suites share.
        ProjectFixtureRig.sweep(jdbcTemplate, trackedProjects);
        trackedProjects.clear();
    }

    private long createProject(String code) {
        // Same-code self-heal drops a crashed run's leftover before creating,
        // so the unique key never collides without touching other suites' rows.
        return ProjectFixtureRig.createSelfHeal(jdbcTemplate, projectService, 1L,
                trackedProjects, "项目" + code, code);
    }

    private long createCase(long projectId) {
        CaseCreateInput input = new CaseCreateInput(projectId, "登录功能测试", "打开系统登录页",
                "登录成功进入首页", 1, 2, "web", 1, null, 1, null,
                List.of(new CaseStepInput(1, "输入账号密码", 0), new CaseStepInput(2, "页面出现登录成功提示", 1)));
        return caseService.create(1L, input).id();
    }

    private void addMember(long projectId, long userId) {
        CaseTestMembers.addMember(jdbcTemplate, projectId, userId);
    }

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


    @Test
    void review通过_真实发出REVIEW_CHANGED事件() {
        long pid = createProject("rtl");
        long cid = createCase(pid);
        Queue queue = declareTempQueue(CaseEventType.REVIEW_CHANGED.routingKey());

        caseService.review(7L, cid, ReviewAction.PASS, null, 1);

        CaseEventMessage evt = awaitMessage(queue);
        assertNotNull(evt, "评审通过必须真实发出 REVIEW_CHANGED（TC-MQ-01）");
        assertEquals(CaseEventType.REVIEW_CHANGED.name(), evt.eventType());
        assertEquals(cid, evt.caseId());
        assertEquals(pid, evt.projectId(), "projectId 贯穿（H1）");
        assertEquals(7L, evt.operatorId(), "操作人透传");
        assertEquals("PASS", evt.action());
    }


    @Test
    void review需修改_remark透传() {
        long pid = createProject("rtl");
        long cid = createCase(pid);
        Queue queue = declareTempQueue(CaseEventType.REVIEW_CHANGED.routingKey());

        caseService.review(7L, cid, ReviewAction.NEED_CHANGE, "步骤 2 预期不明确，请补充", 1,
                List.of("step_inaccurate"));

        CaseEventMessage evt = awaitMessage(queue);
        assertNotNull(evt, "评审需修改必须真实发出 REVIEW_CHANGED（TC-MQ-02）");
        assertEquals("NEED_CHANGE", evt.action());
        assertEquals("步骤 2 预期不明确，请补充", evt.remark());
    }


    @Test
    void 版本冲突_评审被拦截_无事件发出() {
        long pid = createProject("rtl");
        long cid = createCase(pid);
        Queue queue = declareTempQueue(CaseEventType.REVIEW_CHANGED.routingKey());

        assertThrows(OptimisticLockingException.class,
                () -> caseService.review(7L, cid, ReviewAction.PASS, null, 99),
                "版本冲突必须抛 409 语义异常");

        assertNull(awaitMessage(queue), "版本冲突回滚不得发事件（TC-MQ-05）");
    }


    @Test
    void 单条分配_真实发出ASSIGNED事件() {
        long pid = createProject("rtl");
        long cid = createCase(pid);
        addMember(pid, 5L);
        Queue queue = declareTempQueue(CaseEventType.ASSIGNED.routingKey());

        caseService.assign(7L, cid, 5L);

        CaseEventMessage evt = awaitMessage(queue);
        assertNotNull(evt, "单条分配必须真实发出 ASSIGNED（TC-MQ-03）");
        assertEquals(CaseEventType.ASSIGNED.name(), evt.eventType());
        assertEquals(cid, evt.caseId());
        assertEquals(5L, evt.assigneeId());
        assertEquals(pid, evt.projectId());
    }


    @Test
    void 批量分配_真实发出BATCH_ASSIGNED摘要事件() {
        long pid = createProject("rtl");
        long c1 = createCase(pid);
        long c2 = createCase(pid);
        long c3 = createCase(pid);
        addMember(pid, 5L);
        Queue queue = declareTempQueue(CaseEventType.BATCH_ASSIGNED.routingKey());

        caseService.assignBatch(7L, pid, List.of(c1, c2, c3), 5L);

        CaseEventMessage evt = awaitMessage(queue);
        assertNotNull(evt, "批量分配必须真实发出 BATCH_ASSIGNED（TC-MQ-04）");
        assertEquals(CaseEventType.BATCH_ASSIGNED.name(), evt.eventType());
        assertEquals(3, evt.batchSize());
        assertEquals(List.of(c1, c2, c3), evt.caseIds(), "批量明细完整保留");
        assertEquals(5L, evt.assigneeId());
        assertEquals(pid, evt.projectId());
        assertNull(evt.caseId(), "批量事件无单对象 caseId");
    }


    @Test
    void 批量分配整体回滚_无事件发出() {
        long pid = createProject("rtl");
        long c1 = createCase(pid);
        long c2 = createCase(pid);
        addMember(pid, 5L);
        caseService.review(7L, c1, ReviewAction.PASS, null, 1);
        Queue queue = declareTempQueue(CaseEventType.BATCH_ASSIGNED.routingKey());

        assertThrows(com.sixbean.testpike.common.exception.ServiceException.class,
                () -> caseService.assignBatch(7L, pid, List.of(c1, c2), 5L),
                "含已通过用例的批量分配必须整体回滚");

        CaseInfo c2After = caseService.get(c2);
        assertNull(c2After.assigneeId(), "回滚后未分配（数据一致性）");
        assertNull(awaitMessage(queue), "整体回滚不得发 BATCH_ASSIGNED 事件（TC-MQ-05 语义）");
    }
}
