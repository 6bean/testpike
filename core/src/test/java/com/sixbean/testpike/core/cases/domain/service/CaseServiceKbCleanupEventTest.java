package com.sixbean.testpike.core.cases.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.api.dto.CaseStepInput;
import com.sixbean.testpike.core.cases.api.dto.CaseUpdateInput;
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


@SpringBootTest(classes = CoreApplication.class)
class CaseServiceKbCleanupEventTest {

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
        // run's tracked fixture projects (cases, steps, audit) — the blanket
        // TRUNCATE rewound shared sequences and wiped concurrent owners' rows.
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
    void delete_软删用例_真实发出DELETED事件() {
        long pid = createProject("kb1");
        long cid = approveCase(pid, createCase(pid));
        Queue queue = declareTempQueue(CaseEventType.DELETED.routingKey());

        caseService.delete(9L, cid);

        CaseEventMessage evt = awaitMessage(queue);
        assertNotNull(evt, "软删必须真实发出 case.deleted（T177a 挂点①）");
        assertEquals(CaseEventType.DELETED.name(), evt.eventType());
        assertEquals(cid, evt.caseId());
        assertEquals(pid, evt.projectId(), "projectId 贯穿（H1，清理链路台账定位键组成）");
        assertEquals(9L, evt.operatorId(), "操作人透传");
    }


    @Test
    void restore_还原已通过用例_直发REVIEW_CHANGED_PASS再入库() {
        long pid = createProject("kb2");
        long cid = approveCase(pid, createCase(pid));
        caseService.delete(9L, cid);
        Queue queue = declareTempQueue(CaseEventType.REVIEW_CHANGED.routingKey());

        caseService.restore(9L, cid, 2);

        CaseEventMessage evt = awaitMessage(queue);
        assertNotNull(evt, "还原已通过用例必须直发 case.reviewed action=PASS（T177a 挂点②，复用现消费者再入库）");
        assertEquals(CaseEventType.REVIEW_CHANGED.name(), evt.eventType());
        assertEquals(cid, evt.caseId());
        assertEquals("PASS", evt.action());
        assertEquals(2, evt.caseVersion(), "还原不 bump version，幂等键 kb-ingest:case:{id}:{version} 携带当前版本");
    }


    @Test
    void restore_还原待评审用例_无再入库事件() {
        long pid = createProject("kb3");
        long cid = createCase(pid); // status=1
        caseService.delete(9L, cid);
        Queue queue = declareTempQueue(CaseEventType.REVIEW_CHANGED.routingKey());

        caseService.restore(9L, cid, 1);

        assertNull(awaitMessage(queue), "还原非已通过用例不得发 case.reviewed（未过评审不入库）");
    }


    @Test
    void update_编辑已通过用例内容_回退待评审并发INVALIDATED() {
        long pid = createProject("kb4");
        long cid = approveCase(pid, createCase(pid));
        Queue queue = declareTempQueue(CaseEventType.INVALIDATED.routingKey());

        CaseInfo updated = caseService.update(9L, cid,
                new CaseUpdateInput("登录功能测试-改", null, null, null, null, null, null, null, null, null), 2);

        assertEquals(1, updated.status(), "已通过用例被编辑修改内容 → 状态回待评审(1)（14 §6.2）");
        CaseEventMessage evt = awaitMessage(queue);
        assertNotNull(evt, "编辑回退必须发 case.invalidated（离开已通过即清理向量，T177a 挂点④）");
        assertEquals(CaseEventType.INVALIDATED.name(), evt.eventType());
        assertEquals(cid, evt.caseId());
        assertEquals(pid, evt.projectId());
        assertEquals(3, evt.caseVersion(), "回退后新版本号（appliedVersion=expectedVersion+1）");
    }


    @Test
    void update_已通过用例仅改优先级_不回退无INVALIDATED() {
        long pid = createProject("kb5");
        long cid = approveCase(pid, createCase(pid));
        Queue queue = declareTempQueue(CaseEventType.INVALIDATED.routingKey());

        CaseInfo updated = caseService.update(9L, cid,
                new CaseUpdateInput(null, null, null, 2, null, null, null, null, null, null), 2);

        assertEquals(3, updated.status(), "仅改优先级不算内容变化，不回退");
        assertNull(awaitMessage(queue), "未离开已通过不得发 case.invalidated");
    }


    @Test
    void update_已通过用例提交相同标题_不回退无INVALIDATED() {
        long pid = createProject("kb6");
        long cid = approveCase(pid, createCase(pid));
        Queue queue = declareTempQueue(CaseEventType.INVALIDATED.routingKey());

        CaseInfo updated = caseService.update(9L, cid,
                new CaseUpdateInput("登录功能测试", null, null, null, null, null, null, null, null, null), 2);

        assertEquals(3, updated.status(), "标题内容未实际变化，不回退");
        assertNull(awaitMessage(queue), "内容相同提交不算变，不得发 case.invalidated");
    }


    @Test
    void archive_项目归档_真实发出PROJECT_INVALIDATED() {
        long pid = createProject("kb7");
        approveCase(pid, createCase(pid));
        Queue queue = declareTempQueue(CaseEventType.PROJECT_INVALIDATED.routingKey());

        projectService.archive(pid);

        CaseEventMessage evt = awaitMessage(queue);
        assertNotNull(evt, "项目归档必须发 kb.project-invalidated（T177a 挂点⑦，清理链路批量删）");
        assertEquals(CaseEventType.PROJECT_INVALIDATED.name(), evt.eventType());
        assertEquals(pid, evt.projectId());
        assertNull(evt.caseId(), "项目级事件无单对象 caseId");
    }
}
