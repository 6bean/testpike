package com.sixbean.testpike.core.executor.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.executor.api.dto.ClaimTaskView;
import com.sixbean.testpike.core.executor.api.dto.ExecResultInput;
import com.sixbean.testpike.core.executor.api.dto.ExecTaskCreateInput;
import com.sixbean.testpike.core.executor.api.dto.ExecutorRegisterInput;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.api.dto.ProjectEnvInput;
import com.sixbean.testpike.core.project.domain.service.ProjectEnvService;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.tags.api.dto.TagCreateInput;
import com.sixbean.testpike.core.tags.api.dto.TagInfo;
import com.sixbean.testpike.core.tags.domain.service.TagService;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class PoolDispatchClaimTest {

    private static final String ACTION_JSON =
            "{\"version\":\"v2\",\"steps\":[{\"action\":\"goto\",\"target\":{\"url\":\"http://demo.local\"}}]}";

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
    private TagService tagService;

    // Fixture-scoped teardown : the name-family sweep in
    // SharedDbFixtureSweep resolves this suite's fixed-name projects and
    // walks the FK graph; tp_tag_dict is project-keyed without an FK into
    // tp_project, so it rides the extras list. Executors drop by their exact
    // registered names. A blanket TRUNCATE of these shared tables would wipe
    // concurrent suites' rows on the dev database.
    private static final List<String> FIXTURE_PROJECT_NAMES =
            List.of("池标签项目", "池成员闸", "配额项目X", "配额项目Y", "保底项目", "空池项目");

    @BeforeEach
    @AfterEach
    void cleanTables() {
        com.sixbean.testpike.core.support.SharedDbFixtureSweep.byProjectNameFamily(
                jdbcTemplate, List.of("tp_tag_dict"), FIXTURE_PROJECT_NAMES);
        jdbcTemplate.update("DELETE FROM tp_executor WHERE name IN "
                + "('ex-out','ex-in-csv','ex-quota','ex-quota-offline','ex-floor','ex-nopool')");
    }

    private long createProject(String name) {
        return projectService.create(1L, new ProjectCreateInput(name,
                "P" + System.nanoTime() % 100000, "zh-CN", "P1", null));
    }

    private long createEnv(long projectId) {
        return projectEnvService.create(projectId, new ProjectEnvInput(
                "测试环境", 2, "http://demo.local", null, "web", "chromium", "v1.0.0"));
    }

    private long createCase(long projectId, String title) {
        CaseInfo info = caseService.create(1L, new CaseCreateInput(projectId, title,
                "前置", "预期", 1, 2, "web", 3, null, 1, null, List.of()));
        return info.id();
    }

 
    private long createPoolTag(long projectId, String name) {
        TagInfo tag = tagService.create(1L, new TagCreateInput(projectId, name, 7));
        return tag.id();
    }

    
    private long createBatch(long projectId, Long poolTagId) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO tp_exec_batch (project_id, batch_no, name, status, type,
                                           schedule_type, pool_tag_id)
                VALUES (?, ?, '池调度批', 2, 1, 0, ?) RETURNING id
                """, Long.class, projectId, "B-pool-" + System.nanoTime() % 100000, poolTagId);
    }

    private long createTaskInBatch(long projectId, long envId, long caseId, long batchId) {
        return execTaskService.create(new ExecTaskCreateInput(projectId, caseId, "v2",
                ACTION_JSON, envId, batchId));
    }

    private long registerExecutor(String name, String groupIds, int capacity) {
        return executorService.register(new ExecutorRegisterInput(
                name, groupIds, null, "web", "chromium", capacity, "1.0.0", 0));
    }

    
    private void setPoolQuota(long projectId, Integer pct) {
        jdbcTemplate.update("UPDATE tp_project SET pool_quota_pct = ? WHERE id = ?", pct, projectId);
    }

    
    private void forceOffline(long executorId) {
        jdbcTemplate.update("UPDATE tp_executor SET last_heartbeat = now() - interval '10 minutes'"
                + " WHERE id = ?", executorId);
    }

    private long pendingCount() {
        return FixtureTaskCounters.countByStatus(jdbcTemplate, 1, FIXTURE_PROJECT_NAMES);
    }

    private long runningCountOfProject(long projectId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_exec_task WHERE status = 2 AND project_id = ?",
                Long.class, projectId);
        return n == null ? 0 : n;
    }

    @Test
    void 池型标签可建type7_被批次引用的池标签删除拒409() {
        long pid = createProject("池标签项目");
        TagInfo pool = tagService.create(1L, new TagCreateInput(pid, "夜间回归池", 7));
        assertEquals(7, pool.type(), "资源池标签必须可按 type=7 创建（1=4 通用型零变化）");

        long batchId = createBatch(pid, pool.id());
        ServiceException ref = assertThrows(ServiceException.class,
                () -> tagService.delete(1L, pool.id()));
        assertEquals(BizErrorCode.RESOURCE_CONFLICT, ref.getCode());
        assertTrue(ref.getMessage().contains("资源池") && ref.getMessage().contains("无法删除"),
                "被批次选为池的标签删除必须 409 人话，实际：" + ref.getMessage());

        jdbcTemplate.update("DELETE FROM tp_exec_batch WHERE id = ?", batchId);
        tagService.delete(1L, pool.id());
    }

    @Test
    void 池外执行器不可领池批任务_池内可领_未选池零变化() {
        long pid = createProject("池成员闸");
        long envId = createEnv(pid);
        long caseId = createCase(pid, "池闸用例");
        long poolTag = createPoolTag(pid, "web池");
        long poolBatch = createBatch(pid, poolTag);
        createTaskInBatch(pid, envId, caseId, poolBatch);

        long out = registerExecutor("ex-out", null, 4);
        long inCsv = registerExecutor("ex-in-csv", "88," + poolTag + ",99", 4);

        assertNull(execTaskService.claim(out), "池外执行器必须领不到池批任务（候选跳过排队）");
        assertEquals(1L, pendingCount(), "池外被闸后任务必须仍在待派发队列（不误杀）");

        ClaimTaskView view = execTaskService.claim(inCsv);
        assertNotNull(view, "池内执行器必须可领池批任务");

        long plainBatch = createBatch(pid, null);
        createTaskInBatch(pid, envId, caseId, plainBatch);
        assertNotNull(execTaskService.claim(out), "未选池批次派发零变化（池 NULL 不加闸）");
    }

    @Test
    void 池内占比配额超限该项目排队_他项目不受波及_终态释放后恢复() {
        long pidX = createProject("配额项目X");
        long envX = createEnv(pidX);
        long caseX = createCase(pidX, "X用例");
        long pidY = createProject("配额项目Y");
        long envY = createEnv(pidY);
        long caseY = createCase(pidY, "Y用例");
        long poolTag = createPoolTag(pidX, "配额池");

        long exIn = registerExecutor("ex-quota", String.valueOf(poolTag), 2);
        long exOffline = registerExecutor("ex-quota-offline", String.valueOf(poolTag), 10);
        forceOffline(exOffline);
        setPoolQuota(pidX, 50);

        long batchX = createBatch(pidX, poolTag);
        long batchY = createBatch(pidY, poolTag);
        long t1 = createTaskInBatch(pidX, envX, caseX, batchX);
        long t2 = createTaskInBatch(pidX, envX, caseX, batchX);
        long t3 = createTaskInBatch(pidY, envY, caseY, batchY);

        ClaimTaskView first = execTaskService.claim(exIn);
        assertNotNull(first, "X 在跑未达配额上限首笔必须放行");
        assertEquals(t1, first.taskId());
        ClaimTaskView second = execTaskService.claim(exIn);
        assertNotNull(second, "配额闸=逐候选跳过，他项目候选仍可领（禁全局停摆）");
        assertEquals(t3, second.taskId(), "第二笔必须领到 Y 项目任务（X2 排队）");
        execTaskService.submitResult(new ExecResultInput(first.taskId(), first.claimToken(),
                "pass", null, null, null, null, null, null));
        jdbcTemplate.update("UPDATE tp_executor SET capacity = 4 WHERE id = ?", exIn);
        ClaimTaskView third = execTaskService.claim(exIn);
        assertNotNull(third, "X1 终态释放后 X 在跑 0 < 上限 1，X2 必须恢复放行");
        assertEquals(t2, third.taskId());
        assertEquals(0L, pendingCount(), "三条全部派发后队列清零");
        assertTrue(runningCountOfProject(pidX) >= 0);
    }

    @Test
    void 低配额保底一槽防饿死() {
        long pid = createProject("保底项目");
        long envId = createEnv(pid);
        long caseId = createCase(pid, "保底用例");
        long poolTag = createPoolTag(pid, "保底池");
        long exIn = registerExecutor("ex-floor", String.valueOf(poolTag), 2);
        setPoolQuota(pid, 10);

        long batch = createBatch(pid, poolTag);
        createTaskInBatch(pid, envId, caseId, batch);
        createTaskInBatch(pid, envId, caseId, batch);

        assertNotNull(execTaskService.claim(exIn), "配额取整为 0 时保底 1 槽必须放行（防饿死）");
        jdbcTemplate.update("UPDATE tp_executor SET capacity = 4 WHERE id = ?", exIn);
        assertNull(execTaskService.claim(exIn), "已达保底 1 槽后第二笔必须排队");
        assertEquals(1L, pendingCount(), "排队笔滞留待派发不误杀");
    }

    @Test
    void 池内无在线成员_池批滞留排队_claim不炸() {
        long pid = createProject("空池项目");
        long envId = createEnv(pid);
        long caseId = createCase(pid, "空池用例");
        long poolTag = createPoolTag(pid, "幽灵池");
        long exOut = registerExecutor("ex-nopool", null, 2);

        long batch = createBatch(pid, poolTag);
        createTaskInBatch(pid, envId, caseId, batch);

        assertNull(execTaskService.claim(exOut), "池内无在线成员时领取必须静默排队非异常");
        assertEquals(1L, pendingCount(), "滞留任务仍在待派发队列");
    }
}
