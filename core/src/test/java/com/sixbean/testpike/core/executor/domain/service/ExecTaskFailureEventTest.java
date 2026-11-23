package com.sixbean.testpike.core.executor.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.events.api.dto.CaseEventMessage;
import com.sixbean.testpike.core.executor.api.dto.ClaimTaskView;
import com.sixbean.testpike.core.executor.api.dto.ExecResultInput;
import com.sixbean.testpike.core.executor.api.dto.ExecTaskCreateInput;
import com.sixbean.testpike.core.executor.api.dto.ExecTaskInfo;
import com.sixbean.testpike.core.executor.api.dto.ExecutorRegisterInput;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.api.dto.ProjectEnvInput;
import com.sixbean.testpike.core.project.domain.service.ProjectEnvService;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
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
class ExecTaskFailureEventTest {

    
    private static final String ROUTING_KEY_FAILED = "exec.task.failed";

    private static final String ACTION_JSON =
            "{\"version\":\"v2\",\"steps\":[{\"action\":\"goto\",\"target\":{\"url\":\"http://demo.local/login\"}}]}";

    @Autowired
    private ExecTaskService execTaskService;

    @Autowired
    private ExecutorService executorService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private CaseService caseService;

    @Autowired
    private ProjectEnvService projectEnvService;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @Autowired
    private MessageConverter caseEventMessageConverter;

    // Fixture-scoped teardown : the name-family sweep resolves
    // this suite's fixed-name project and walks the FK graph (case steps,
    // cases, envs, membership; tp_exec_task is in the helper's default
    // project-keyed set). The executor rows drop by their registered name
    // family. A blanket TRUNCATE would wipe concurrent suites' rows on the
    // dev database.
    private static final java.util.List<String> FIXTURE_PROJECT_NAMES =
            java.util.List.of("归因项目");

    @BeforeEach
    @AfterEach
    void cleanTables() {
        com.sixbean.testpike.core.support.SharedDbFixtureSweep.byProjectNameFamily(
                jdbcTemplate, java.util.List.of(), FIXTURE_PROJECT_NAMES);
        jdbcTemplate.update("DELETE FROM tp_executor WHERE name LIKE 'ex-fail-event-%'");
    }


    private Queue declareTempQueue(String routingKey) {
        Queue queue = new Queue("tp.test." + UUID.randomUUID(), false, true, true);
        amqpAdmin.declareQueue(queue);
        amqpAdmin.declareBinding(new Binding(queue.getName(), Binding.DestinationType.QUEUE,
                "testpike.events", routingKey, null));
        return queue;
    }

    private long registerExecutor(String name, int capacity) {
        return executorService.register(new ExecutorRegisterInput(
                name, null, null, "web", "chromium", capacity, "1.0.0", 0));
    }

    
    private ExecTaskInfo runningTask(long executorId) {
        long pid = projectService.create(1L, new ProjectCreateInput("归因项目",
                "CL" + System.nanoTime() % 100000, "zh-CN", "P1", null));
        // Fixture-scoped teardown keeps other suites' env rows alive, so the
        // env must be captured by id — the former global "first env row"
        // query would bind a foreign project's env under the sweep.
        long envId = projectEnvService.create(pid, new ProjectEnvInput(
                "测试环境", 2, "http://demo.local", null, "web", "chromium", "v1"));
        CaseInfo info = caseService.create(1L, new CaseCreateInput(pid, "归因链路用例",
                "前置", "预期", 1, 2, "web", 3, null, 1, null, List.of()));
        long taskId = execTaskService.create(new ExecTaskCreateInput(pid, info.id(), "v2",
                ACTION_JSON, envId));
        ClaimTaskView view = execTaskService.claim(executorId);
        assertNotNull(view, "执行器必须领取到任务");
        assertEquals(taskId, view.taskId());
        return execTaskService.get(taskId);
    }


    @Test
    void submitResult_fail广播失败事件载荷完整() {
        Queue queue = declareTempQueue(ROUTING_KEY_FAILED);
        long exId = registerExecutor("ex-fail-event-1", 2);
        ExecTaskInfo task = runningTask(exId);

        execTaskService.submitResult(new ExecResultInput(task.id(), task.claimToken(), "fail",
                2, "元素未找到：locator=#submit-btn 在页面 login 不存在", null,
                List.of("artifacts/shot-step1.png", "artifacts/shot-step2.png"), 1234, "boom log"));

        Message msg = rabbitTemplate.receive(queue.getName(), 5000);
        assertNotNull(msg, "fail 回传必须广播 exec.task.failed（TC-C3-01 归因触发源）");
        CaseEventMessage evt = (CaseEventMessage) caseEventMessageConverter.fromMessage(msg);
        assertEquals("EXEC_TASK_FAILED", evt.eventType(), "事件类型枚举名（消费链路路由判定）");
        assertEquals(task.id(), evt.execTaskId(), "taskId 载荷（消费链路幂等键主段）");
        assertEquals(task.projectId(), evt.projectId(), "projectId 贯穿（H1）");
        assertEquals(task.caseId(), evt.caseId(), "caseId 载荷");
        assertEquals(2, evt.execErrorType(), "执行器 error_type 原值透传（正交不换算）");
        assertEquals("元素未找到：locator=#submit-btn 在页面 login 不存在", evt.execErrorDetail());
        assertEquals("artifacts/shot-step2.png", evt.execScreenshotKey(),
                "末步截图 objectKey（vision 增强消费源，取数组末位）");
    }


    @Test
    void submitResult_pass不广播失败事件() {
        Queue queue = declareTempQueue(ROUTING_KEY_FAILED);
        long exId = registerExecutor("ex-fail-event-2", 2);
        ExecTaskInfo task = runningTask(exId);

        execTaskService.submitResult(new ExecResultInput(task.id(), task.claimToken(), "pass",
                null, null, null, null, 999, "ok"));

        assertNull(rabbitTemplate.receive(queue.getName(), 2000),
                "pass 回传零广播（IT-C3-01 触发面=失败链路）");
    }


    @Test
    void submitResult_manual_skip不广播失败事件() {
        Queue queue = declareTempQueue(ROUTING_KEY_FAILED);
        long exId = registerExecutor("ex-fail-event-3", 2);
        ExecTaskInfo task = runningTask(exId);

        execTaskService.submitResult(new ExecResultInput(task.id(), task.claimToken(), "manual_skip",
                null, "人工跳过", null, null, 1, "skip"));

        assertNull(rabbitTemplate.receive(queue.getName(), 2000),
                "manual_skip 零广播（跳过≠失败，不归因）");
    }


    @Test
    void submitResult_fail无截图事件仍可广播() {
        Queue queue = declareTempQueue(ROUTING_KEY_FAILED);
        long exId = registerExecutor("ex-fail-event-4", 2);
        ExecTaskInfo task = runningTask(exId);

        execTaskService.submitResult(new ExecResultInput(task.id(), task.claimToken(), "fail",
                3, "连接被拒", null, List.of(), 50, "conn refused"));

        Message msg = rabbitTemplate.receive(queue.getName(), 5000);
        assertNotNull(msg, "无截图不影响失败事件广播");
        CaseEventMessage evt = (CaseEventMessage) caseEventMessageConverter.fromMessage(msg);
        assertNull(evt.execScreenshotKey(), "空截图数组=objectKey null（消费链路 vision 链路自动降级）");
        assertEquals(3, evt.execErrorType());
    }
}
