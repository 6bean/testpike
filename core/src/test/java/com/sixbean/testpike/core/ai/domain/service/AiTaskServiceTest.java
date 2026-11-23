package com.sixbean.testpike.core.ai.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.ai.api.dto.AiTaskCreateInput;
import com.sixbean.testpike.core.ai.api.dto.AiTaskInfo;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class AiTaskServiceTest {

    @Autowired
    private AiTaskService aiTaskService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // Fixture-scoped teardown : id watermarks captured before each
    // test; @AfterEach deletes only rows this run created above the floors. The
    // blanket TRUNCATE also rewound shared sequences and wiped concurrent suites'
    // audit/notification rows on the dev database.
    private long taskFloor;
    private long notificationFloor;
    private long auditFloor;

    @BeforeEach
    void markWatermarks() {
        taskFloor = SharedDbFixtureSweep.maxId(jdbcTemplate, "tp_ai_task");
        notificationFloor = SharedDbFixtureSweep.maxId(jdbcTemplate, "tp_notification");
        auditFloor = SharedDbFixtureSweep.maxId(jdbcTemplate, "tp_audit_log");
    }

    @AfterEach
    void sweepRunRows() {
        SharedDbFixtureSweep.deleteSince(jdbcTemplate, "tp_ai_task", taskFloor);
        SharedDbFixtureSweep.deleteSince(jdbcTemplate, "tp_notification", notificationFloor);
        SharedDbFixtureSweep.deleteSince(jdbcTemplate, "tp_audit_log", auditFloor);
    }

    @Test
    void createTask_初始状态pending_字段完整() {
        long id = aiTaskService.create(new AiTaskCreateInput(10L, 5L, 1,
                "用户登录功能：输入账号密码点击登录，成功进入首页", null));

        AiTaskInfo task = aiTaskService.get(id);
        assertNotNull(task);
        assertEquals(10L, task.projectId());
        assertEquals(5L, task.operatorId());
        assertEquals(1, task.status());          // 1=pending
        assertEquals("用户登录功能：输入账号密码点击登录，成功进入首页", task.requirementText());
        assertEquals(0, task.retryCnt());
        assertNull(task.caseId());
    }

    @Test
    void markRunning_状态流转running() {
        long id = aiTaskService.create(new AiTaskCreateInput(10L, 5L, 1, "登录", null));

        aiTaskService.markRunning(id);

        assertEquals(2, aiTaskService.get(id).status());   // 2=running
    }

    @Test
    void markDone_回填caseId_状态done() {
        long id = aiTaskService.create(new AiTaskCreateInput(10L, 5L, 1, "登录", null));

        aiTaskService.markRunning(id);
        aiTaskService.markDone(id, 888L);

        AiTaskInfo task = aiTaskService.get(id);
        assertEquals(3, task.status());                    // 3=done
        assertEquals(888L, task.caseId());
    }

    @Test
    void markFailed_记录错误分类与人话原因() {
        long id = aiTaskService.create(new AiTaskCreateInput(10L, 5L, 1, "登录", null));

        aiTaskService.markRunning(id);
        aiTaskService.markFailed(id, "MODEL_TIMEOUT", "模型调用超时，请稍后重试");

        AiTaskInfo task = aiTaskService.get(id);
        assertEquals(4, task.status());                    // 4=failed
        assertEquals("MODEL_TIMEOUT", task.errorCode());
        assertEquals("模型调用超时，请稍后重试", task.errorMsg());
    }

    @Test
    void retry_失败任务回pending_重试计数加一() {
        long id = aiTaskService.create(new AiTaskCreateInput(10L, 5L, 1, "登录", null));
        aiTaskService.markRunning(id);
        aiTaskService.markFailed(id, "FORMAT_ERROR", "格式校验失败");

        aiTaskService.retry(id);

        AiTaskInfo task = aiTaskService.get(id);
        assertEquals(1, task.status());
        assertEquals(1, task.retryCnt());
        assertNull(task.errorCode());
        assertNull(task.errorMsg());
    }

    @Test
    void createTask_携带需求点ID_用于验收覆盖输入() {
        long id = aiTaskService.create(new AiTaskCreateInput(10L, 5L, 1, "登录", 33L));

        assertEquals(33L, aiTaskService.get(id).requirementId());
    }

    @Test
    void get_任务不存在_返回null() {
        assertNull(aiTaskService.get(999999L));
    }

    @Test
    void done任务重试_幂等不重复计数() {
        long id = aiTaskService.create(new AiTaskCreateInput(10L, 5L, 1, "登录", null));
        aiTaskService.markRunning(id);
        aiTaskService.markDone(id, 888L);

        aiTaskService.retry(id);

        AiTaskInfo task = aiTaskService.get(id);
        assertEquals(3, task.status());
        assertEquals(0, task.retryCnt());
        assertTrue(task.caseId() != null);
    }

    @Test
    void retry_已停止任务可重启_回pending重新入队() {
        // User report 2026-11-20: a stopped queue item had no door back.
        // Restart re-enqueues the cancelled row exactly like a failed retry.
        long id = aiTaskService.create(new AiTaskCreateInput(10L, 5L, 1, "登录", null));
        aiTaskService.markRunning(id);
        aiTaskService.cancelTask(id);
        assertEquals(5, aiTaskService.get(id).status());

        aiTaskService.retry(id);

        AiTaskInfo task = aiTaskService.get(id);
        assertEquals(1, task.status());
        assertEquals(1, task.retryCnt());
    }

    @Test
    void markModel_任务记录项目模型标识_K9路由落库() {
        long id = aiTaskService.create(new AiTaskCreateInput(10L, 5L, 1, "登录", null));
        aiTaskService.markRunning(id);

        aiTaskService.markModel(id, "deepseek-chat");

        AiTaskInfo task = aiTaskService.get(id);
        assertEquals("deepseek-chat", task.modelName(), "AI 生成任务带项目模型标识（TC-K9-02）");
        String stored = jdbcTemplate.queryForObject(
                "SELECT model_name FROM tp_ai_task WHERE id = ?", String.class, id);
        assertEquals("deepseek-chat", stored, "tp_ai_task.model_name 落库");
    }

    @Test
    void 钉住模板版本_列写入且重读可见() {
        long id = aiTaskService.create(new AiTaskCreateInput(10L, 5L, 1, "登录", null));

        aiTaskService.pinTemplate(id, 777L);

        AiTaskInfo task = aiTaskService.get(id);
        assertEquals(777L, task.templateVersionId(),
                "任务钉住列回填（TC-D4-01 执行中改模板不回拽的消费前提）");
        Long stored = jdbcTemplate.queryForObject(
                "SELECT template_version_id FROM tp_ai_task WHERE id = ?", Long.class, id);
        assertEquals(777L, stored, "tp_ai_task.template_version_id 落库");
    }

    @Test
    void 钉住覆盖写_改钉新模板行_存量列零值默认() {
        long id = aiTaskService.create(new AiTaskCreateInput(10L, 5L, 1, "登录", null));
        assertNull(aiTaskService.get(id).templateVersionId(), "新建任务未钉住=NULL（回退默认语义）");

        aiTaskService.pinTemplate(id, 111L);
        aiTaskService.pinTemplate(id, 222L);

        assertEquals(222L, jdbcTemplate.queryForObject(
                "SELECT template_version_id FROM tp_ai_task WHERE id = ?", Long.class, id),
                "重钉=覆盖写（每任务至多钉一行）");
    }

    @Test
    void 钉住任务不存在_404() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> aiTaskService.pinTemplate(999999L, 1L));
        assertEquals(BizErrorCode.NOT_FOUND, ex.getCode());
    }

    @Test
    void markModel_任务不存在_404() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> aiTaskService.markModel(999999L, "gpt-4o"));
        assertEquals(BizErrorCode.NOT_FOUND, ex.getCode());
    }
}
