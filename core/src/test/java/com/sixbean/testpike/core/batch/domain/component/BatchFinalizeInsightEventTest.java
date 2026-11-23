package com.sixbean.testpike.core.batch.domain.component;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.events.api.dto.CaseEventMessage;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
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

/**
 * Contract test for the batch-finalized domain event (AO-7 proactive insights, stage 1).
 * <p>Design pins (recon card ):
 * ① publish site = BatchFinalizeSupport.finalizeIfAllTerminal inside the {@code advanced > 0}
 * idempotent guard — only the transaction that really advances the batch emits the event;
 * ② routing key = exec.batch.finalized on the shared {@code testpike.events} topic exchange,
 * payload carries batchId + targetStatus (6 done / 7 partial-failed) appended at the tail of
 * {@link CaseEventMessage} ;
 * ③ publisher is afterCommit-wrapped — the finalize paths exercised here run without an outer
 * transaction, so the message lands immediately (same posture as the report snapshot tests).
 * <p>Real dev database + real RabbitMQ, nothing mocked (strict-testing-standards).
 */
@SpringBootTest(classes = CoreApplication.class)
class BatchFinalizeInsightEventTest {

    /** Routing key referenced as a string: RED phase must compile before the enum constant exists. */
    private static final String ROUTING_KEY_FINALIZED = "exec.batch.finalized";

    @Autowired
    private BatchFinalizeSupport batchFinalizeSupport;
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

    /** Per-run token: the sweep and every count stay scoped to this run's rows (R37). */
    private final String token = SharedDbFixtureSweep.newToken();
    private final List<Long> projectIds = new java.util.concurrent.CopyOnWriteArrayList<>();

    @BeforeEach
    @AfterEach
    void cleanTables() {
        SharedDbFixtureSweep.byProjectList(jdbcTemplate, List.of("tp_batch_report"), projectIds);
        // Side notifiers irrelevant to this contract are gated off (same posture as the snapshot tests).
        upsertConfig("defect.auto-create.enabled", "0");
        upsertConfig("notify.feishu.enabled", "0");
    }

    private void upsertConfig(String key, String value) {
        jdbcTemplate.update("""
                INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
                VALUES (?, ?, '系统', 'int', 0, 'insight event test fixture')
                ON CONFLICT (config_key) DO UPDATE SET config_value = EXCLUDED.config_value
                """, key, value);
    }

    private long createProject(String code) {
        Long id = projectService.create(1L, new ProjectCreateInput(token + "-洞察项目" + code,
                token + "-" + code, "zh-CN", "P1", null));
        jdbcTemplate.update("DELETE FROM tp_user_project WHERE project_id = ? AND user_id = 1", id);
        projectIds.add(id);
        return id;
    }

    private long createCase(long projectId, String title) {
        CaseInfo info = caseService.create(1L, new CaseCreateInput(projectId, title, "前置", "预期",
                1, 2, "web", 3, null, 1, null, List.of()));
        return info.id();
    }

    /** Creates a batch in the given status with tasks shaped [caseId, status, errorType, aiClass]. */
    private long batchWithTasks(long projectId, int batchStatus, Object[][] tasks) {
        Long batchId = jdbcTemplate.queryForObject(
                "INSERT INTO tp_exec_batch (project_id, batch_no, name, status, type, started_at) "
                        + "VALUES (?, ?, ?, ?, 1, now() - interval '200 seconds') RETURNING id",
                Long.class, projectId, "B-" + System.nanoTime(), "洞察事件批次", batchStatus);
        assert batchId != null;
        for (Object[] t : tasks) {
            jdbcTemplate.update(
                    "INSERT INTO tp_exec_task (project_id, case_id, batch_id, status, error_type, ai_failure_class) "
                            + "VALUES (?, ?, ?, ?, ?, ?)",
                    projectId, t[0], batchId, t[1], t[2], t[3]);
        }
        return batchId;
    }

    private Queue declareTempQueue() {
        Queue queue = new Queue("tp.test." + UUID.randomUUID(), false, true, true);
        amqpAdmin.declareQueue(queue);
        amqpAdmin.declareBinding(new Binding(queue.getName(), Binding.DestinationType.QUEUE,
                "testpike.events", ROUTING_KEY_FINALIZED, null));
        return queue;
    }

    private CaseEventMessage awaitMessage(Queue queue) {
        Message msg = rabbitTemplate.receive(queue.getName(), 5000);
        return msg == null ? null : (CaseEventMessage) caseEventMessageConverter.fromMessage(msg);
    }

    // ── ① done batch finalize emits the event with batchId + targetStatus=6 ──────────

    @Test
    void 收口已完成广播批次终态事件载荷完整() {
        Queue queue = declareTempQueue();
        long pid = createProject("INS1");
        long c1 = createCase(pid, "冒烟一");
        long batchId = batchWithTasks(pid, 4, new Object[][]{
                {c1, 3, null, null},
                {c1, 6, null, null},   // skipped task keeps the batch in the all-pass branch
        });
        batchFinalizeSupport.onTasksTimedOut(List.of(batchId));

        CaseEventMessage evt = awaitMessage(queue);
        assertNotNull(evt, "批次收口必须广播 exec.batch.finalized（聚类触发源）");
        assertEquals("EXEC_BATCH_FINALIZED", evt.eventType());
        assertEquals(batchId, evt.batchId(), "batchId 尾追键=聚类消费主体");
        assertEquals(6, evt.targetStatus(), "全过批 targetStatus=6 已完成");
        assertEquals(pid, evt.projectId(), "projectId 贯穿（H1 隔离）");
        assertNull(evt.caseId(), "批次级事件单对象位恒 null");
        assertNull(evt.execTaskId(), "批次级事件不携任务段");
    }

    // ── ② partial-failed batch emits targetStatus=7 ──────────────────────────────────

    @Test
    void 收口部分失败广播targetStatus七() {
        Queue queue = declareTempQueue();
        long pid = createProject("INS2");
        long c1 = createCase(pid, "下单");
        long batchId = batchWithTasks(pid, 4, new Object[][]{
                {c1, 3, null, null},
                {c1, 4, 2, null},   // failed task flips the batch to partial-failed
        });
        batchFinalizeSupport.onTasksTimedOut(List.of(batchId));

        CaseEventMessage evt = awaitMessage(queue);
        assertNotNull(evt, "部分失败收口同样广播（7 态才是聚类主消费面）");
        assertEquals(7, evt.targetStatus());
        assertEquals(batchId, evt.batchId());
    }

    // ── ③ cancelled batch: condition update advances 0 rows → no event at all ────────

    @Test
    void 取消批零推进不广播事件() {
        Queue queue = declareTempQueue();
        long pid = createProject("INS3");
        long c1 = createCase(pid, "取消");
        long batchId = batchWithTasks(pid, 8, new Object[][]{{c1, 7, null, null}});
        batchFinalizeSupport.onTasksTimedOut(List.of(batchId));

        assertNull(awaitQuietly(queue), "advanced=0 守卫位不发事件（同报告快照零写语义）");
    }

    private CaseEventMessage awaitQuietly(Queue queue) {
        Message msg = rabbitTemplate.receive(queue.getName(), 2000);
        return msg == null ? null : (CaseEventMessage) caseEventMessageConverter.fromMessage(msg);
    }

    // ── ④ re-entry without status reset: idempotent guard emits exactly one event ────

    @Test
    void 收口重入只广播一次事件() {
        Queue queue = declareTempQueue();
        long pid = createProject("INS4");
        long c1 = createCase(pid, "幂等");
        long batchId = batchWithTasks(pid, 4, new Object[][]{{c1, 3, null, null}});
        batchFinalizeSupport.onTasksTimedOut(List.of(batchId));
        assertNotNull(awaitMessage(queue), "首次收口必须出事件");

        // The auto-delete temp queue vanishes once its transient consumer drains the first
        // message, so re-declare a fresh binding before probing the re-entry call.
        Queue secondProbe = declareTempQueue();
        // Second call: batch already terminal, condition update hits 0 rows → no duplicate.
        batchFinalizeSupport.onTasksTimedOut(List.of(batchId));
        assertNull(awaitQuietly(secondProbe), "重入零推进=零事件（幂等守卫与报告快照同位）");
    }

    // ── ⑤ unfinished tasks: finalize not reached → no event ──────────────────────────

    @Test
    void 仍有未终态任务不广播() {
        Queue queue = declareTempQueue();
        long pid = createProject("INS5");
        long c1 = createCase(pid, "在跑");
        long batchId = batchWithTasks(pid, 4, new Object[][]{
                {c1, 3, null, null},
                {c1, 2, null, null},   // still running
        });
        batchFinalizeSupport.onTasksTimedOut(List.of(batchId));

        assertNull(awaitQuietly(queue), "未收口批次零广播（完成判定守卫）");
    }
}
