package com.sixbean.testpike.core.cases.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseStepInput;
import com.sixbean.testpike.common.exception.OptimisticLockingException;
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
class CaseExcellentEventTest {

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

    
    private long approveCase(long projectId, long caseId) {
        caseService.review(7L, caseId, ReviewAction.PASS, null, 1);
        return caseId;
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
    void markExcellent_真实发出REVIEW_CHANGED事件_actionEXCELLENT版本加一() {
        long pid = createProject("ex1");
        long cid = approveCase(pid, createCase(pid)); // version=2
        Queue queue = declareTempQueue(CaseEventType.REVIEW_CHANGED.routingKey());

        caseService.markExcellent(9L, cid, true, 2);

        CaseEventMessage evt = awaitMessage(queue);
        assertNotNull(evt, "标记优秀必须真实发出 case.reviewed action=EXCELLENT（T215 挂点）");
        assertEquals(CaseEventType.REVIEW_CHANGED.name(), evt.eventType());
        assertEquals(cid, evt.caseId());
        assertEquals(pid, evt.projectId(), "projectId 贯穿（H1）");
        assertEquals(9L, evt.operatorId(), "操作人透传");
        assertEquals("EXCELLENT", evt.action(), "分派值=EXCELLENT（消费链路分型依据）");
        assertEquals(3, evt.caseVersion(), "caseVersion=乐观锁 CAS 后新版本（幂等键去重依据）");
    }


    @Test
    void cancelExcellent_同样发出EXCELLENT事件() {
        long pid = createProject("ex2");
        long cid = approveCase(pid, createCase(pid));
        caseService.markExcellent(9L, cid, true, 2);  // version→3

        Queue queue = declareTempQueue(CaseEventType.REVIEW_CHANGED.routingKey());
        caseService.markExcellent(9L, cid, false, 3);

        CaseEventMessage evt = awaitMessage(queue);
        assertNotNull(evt, "取消标记同样发事件（消费链路直改 metadata=false，不静默）");
        assertEquals("EXCELLENT", evt.action());
        assertEquals(4, evt.caseVersion(), "取消也 bump version，事件携带 CAS 后新值");
    }


    @Test
    void 版本冲突_回滚不发事件() {
        long pid = createProject("ex3");
        long cid = approveCase(pid, createCase(pid)); // version=2
        Queue queue = declareTempQueue(CaseEventType.REVIEW_CHANGED.routingKey());

        assertThrows(OptimisticLockingException.class,
                () -> caseService.markExcellent(9L, cid, true, 99));

        assertNull(awaitMessage(queue), "乐观锁冲突事务回滚，禁止发出 EXCELLENT 事件（TC-MQ-05）");
    }
}
