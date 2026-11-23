package com.sixbean.testpike.core.executor.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.config.api.dto.ConfigInput;
import com.sixbean.testpike.core.config.domain.component.ConfigCipher;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.executor.api.dto.CaseExecHistoryView;
import com.sixbean.testpike.core.executor.api.dto.ClaimTaskView;
import com.sixbean.testpike.core.executor.api.dto.ExecResultInput;
import com.sixbean.testpike.core.executor.api.dto.ExecTaskCreateInput;
import com.sixbean.testpike.core.executor.api.dto.ExecTaskInfo;
import com.sixbean.testpike.core.executor.api.dto.ExecutorRegisterInput;
import com.sixbean.testpike.core.executor.api.dto.ExecutorTaskPageView;
import com.sixbean.testpike.core.executor.api.dto.RunningTaskView;
import com.sixbean.testpike.core.executor.api.dto.ScreenshotView;
import com.sixbean.testpike.core.project.api.dto.EnvParamsInput;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.api.dto.ProjectEnvInput;
import com.sixbean.testpike.core.project.domain.service.ProjectEnvService;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.exception.ValidationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
@TestPropertySource(properties = "testpike.prod-blacklist=*.example.com")
class ExecTaskServiceTest {

    private static final String ACTION_JSON = "{\"version\":\"v2\",\"steps\":[{\"action\":\"goto\",\"target\":{\"url\":\"{{ENV:URL}}/login\"}}]}";

 
    private static final String APP_ENVELOPE_JSON = "{\"kind\":\"app-auto-run\",\"engine\":{\"code\":\"MOBILEWRIGHT\",\"name\":\"Mobilewright\",\"status\":1},\"steps\":[{\"stepNo\":1,\"action\":\"app.launch\"}]}";

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
    private ConfigCipher configCipher;

    @Autowired
    private ConfigService configService;

    // Fixture-scoped teardown (truncate-ratchet discipline): the name-family sweep resolves
    // this suite's fixed-name projects and walks the FK graph child-first
    // (case steps, cases, envs, membership; tp_exec_task/tp_exec_batch/audit
    // rows are in the helper's default project-keyed set). Executor rows drop
    // by the suite-wide ex-svc- name family. A blanket TRUNCATE would wipe
    // rows owned by concurrent suites and L3 probes on the shared dev database.
    private static final java.util.List<String> FIXTURE_PROJECT_NAMES =
            java.util.List.of("项目J2");

    @BeforeEach
    @AfterEach
    void cleanTables() {
        com.sixbean.testpike.core.support.SharedDbFixtureSweep.byProjectNameFamily(
                jdbcTemplate, java.util.List.of(), FIXTURE_PROJECT_NAMES);
        jdbcTemplate.update("DELETE FROM tp_executor WHERE name LIKE 'ex-svc-%'");
    }

    private long registerExecutor(String name, int capacity) {
        return executorService.register(new ExecutorRegisterInput(
                name, null, null, "web", "chromium", capacity, "1.0.0", 0));
    }

    
    private long createProject() {
        return projectService.create(1L, new ProjectCreateInput("项目J2", "J2" + System.nanoTime() % 100000,
                "zh-CN", "P1", null));
    }

    private long createApprovedCase(long projectId) {
        CaseInfo info = caseService.create(1L, new CaseCreateInput(projectId, "回填链路用例",
                "前置", "预期", 1, 2, "web", 3, null, 1, null, List.of()));
        return info.id();
    }

 
    private long createEnv(long projectId, int envType, String name, String baseUrl, String versionSnapshot) {
        return projectEnvService.create(projectId, new ProjectEnvInput(
                name, envType, baseUrl, null, "web", "chromium", versionSnapshot));
    }

 
    private long createTestEnv(long projectId) {
        return createEnv(projectId, 2, "测试环境", "http://demo.local", "v2.1.0");
    }

    
    private long createTask() {
        long pid = createProject();
        long envId = createTestEnv(pid);
        long caseId = createApprovedCase(pid);
        return execTaskService.create(new ExecTaskCreateInput(pid, caseId, "v2", ACTION_JSON, envId));
    }

    private int caseStatus(long caseId) {
        Integer status = jdbcTemplate.queryForObject(
                "SELECT status FROM tp_test_case WHERE id = ?", Integer.class, caseId);
        return status == null ? -1 : status;
    }

    private ExecTaskInfo runToClaimed(long taskId, long exId) {
        ClaimTaskView view = execTaskService.claim(exId);
        assertNotNull(view, "执行器必须领取到任务");
        assertEquals(taskId, view.taskId());
        return execTaskService.get(taskId);
    }

    @Test
    void create_初始待派发_字段完整() {
        long pid = createProject();
        long envId = createTestEnv(pid);
        long caseId = createApprovedCase(pid);
        long id = execTaskService.create(new ExecTaskCreateInput(pid, caseId, "v2", ACTION_JSON, envId));

        ExecTaskInfo task = execTaskService.get(id);
        assertNotNull(task);
        assertEquals(pid, task.projectId());
        assertEquals(caseId, task.caseId());
        assertEquals(1, task.status());
        assertNull(task.claimToken());
        assertEquals("v2", task.schemaVersion());
        assertEquals(ACTION_JSON, task.actionJson());
        assertEquals("http://demo.local", task.envBaseUrl());
        assertEquals("v2.1.0", task.envVersionSnapshot(), "J6：执行记录带所选环境版本快照");
    }

    @Test
    void claim_领取成功_状态执行中且令牌落库() {
        long pid = createProject();
        long envId = createTestEnv(pid);
        long caseId = createApprovedCase(pid);
        long taskId = execTaskService.create(new ExecTaskCreateInput(pid, caseId, "v2", ACTION_JSON, envId));
        long exId = registerExecutor("ex-svc-claim", 2);

        ClaimTaskView view = execTaskService.claim(exId);

        assertNotNull(view);
        assertEquals(taskId, view.taskId());
        assertEquals(caseId, view.caseId());
        assertEquals(ACTION_JSON, view.actionJson());
        assertEquals("http://demo.local", view.envBaseUrl(), "claim 下发环境 baseUrl");
        ExecTaskInfo task = execTaskService.get(taskId);
        assertEquals(2, task.status());
        assertNotNull(task.claimToken());
        assertEquals(exId, task.executorId());
        assertEquals(1, executorService.get(exId).runningCnt());
    }

    @Test
    void claim_双执行器同一任务_仅一个成功() {
        createTask();
        long exA = registerExecutor("ex-svc-a", 2);
        long exB = registerExecutor("ex-svc-b", 2);

        ClaimTaskView first = execTaskService.claim(exA);
        ClaimTaskView second = execTaskService.claim(exB);

        assertNotNull(first, "第一台执行器必须领取成功");
        assertNull(second, "任务已被领取，第二台必须空响应（TC-EXEC-02 原子性）");
        ExecTaskInfo task = execTaskService.get(first.taskId());
        assertEquals(2, task.status());
        assertEquals(exA, task.executorId());
    }

    @Test
    void claim_容量满_不派发() {
        long taskId = createTask();
        long exId = registerExecutor("ex-svc-full", 1);
        executorService.heartbeat(exId, 1);

        assertNull(execTaskService.claim(exId));
        assertEquals(1, execTaskService.get(taskId).status());
    }

    @Test
    void claim_未注册执行器_拒绝() {
        createTask();

        assertThrows(ServiceException.class, () -> execTaskService.claim(99999L));
    }

    @Test
    void submitResult_pass_状态成功且步骤时间戳落库() {
        long taskId = createTask();
        long exId = registerExecutor("ex-svc-pass", 2);
        ClaimTaskView view = execTaskService.claim(exId);

        execTaskService.submitResult(new ExecResultInput(
                taskId, view.claimToken(), "pass", null, null,
                "[{\"stepNo\":1,\"action\":\"goto\",\"status\":\"ok\"}]",
                "[\"exec/1/step1.png\"]", 3500, null));

        ExecTaskInfo task = execTaskService.get(taskId);
        assertEquals(3, task.status());
        assertEquals(1, parseJsonArray(task.stepsTimeline()).size());
        assertEquals("goto", parseJsonArray(task.stepsTimeline()).get(0).get("action").asText());
        assertEquals(3500, task.durationMs());
        assertNotNull(task.finishedAt());
        assertEquals(0, executorService.get(exId).runningCnt());
    }

    private static com.fasterxml.jackson.databind.JsonNode parseJsonArray(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        } catch (Exception e) {
            throw new AssertionError("非法 JSON: " + json, e);
        }
    }

    @Test
    void submitResult_fail_落错误分类与详情() {
        long taskId = createTask();
        long exId = registerExecutor("ex-svc-fail", 2);
        ClaimTaskView view = execTaskService.claim(exId);

        execTaskService.submitResult(new ExecResultInput(
                taskId, view.claimToken(), "fail", 2, "定位失败: #username 元素不存在",
                null, null, 1200, null));

        ExecTaskInfo task = execTaskService.get(taskId);
        assertEquals(4, task.status());
        assertEquals(2, task.errorType());
        assertEquals("定位失败: #username 元素不存在", task.errorDetail());
    }

    @Test
    void submitResult_claimToken不匹配_拒绝() {
        long taskId = createTask();
        long exId = registerExecutor("ex-svc-token", 2);
        execTaskService.claim(exId);

        assertThrows(ServiceException.class, () -> execTaskService.submitResult(
                new ExecResultInput(taskId, "wrong-token", "pass", null, null, null, null, 100, null)));
        assertEquals(2, execTaskService.get(taskId).status());
    }

    @Test
    void listScreenshots_解析截图JSON_返回预签名查看URL() {
        long taskId = createTask();
        long exId = registerExecutor("ex-svc-shot", 2);
        ClaimTaskView view = execTaskService.claim(exId);

        execTaskService.submitResult(new ExecResultInput(
                taskId, view.claimToken(), "fail", 2, "断言失败: 未找到目标文本",
                "[{\"stepNo\":1,\"action\":\"goto\",\"status\":\"ok\"}]",
                "[\"exec/" + taskId + "/screenshot/1_step1.png\"]", 1500, null));

        List<ScreenshotView> shots = execTaskService.listScreenshots(taskId);
        assertEquals(1, shots.size(), "截图列表应解析出 1 条");
        assertEquals(taskId, shots.get(0).taskId());
        assertEquals("exec/" + taskId + "/screenshot/1_step1.png", shots.get(0).objectKey());
        assertTrue(shots.get(0).url().contains("X-Amz-Signature"),
                "URL 必须为预签名 GET（可 <img> 直接展示）: " + shots.get(0).url());
    }

    @Test
    void listScreenshots_无截图产物_返回空列表() {
        long taskId = createTask();
        assertEquals(0, execTaskService.listScreenshots(taskId).size());
    }


    
    private static final String ACTION_JSON_SQL_EXEC =
            "{\"version\":\"v2\",\"steps\":[{\"action\":\"sql.exec\",\"conn\":\"{{ENV:DB_SIT}}\","
                    + "\"sql\":\"UPDATE t_order SET status = 'PENDING' WHERE id = :oid\","
                    + "\"params\":{\"oid\":9999}}]}";

    private long createSqlExecTask() {
        long pid = createProject();
        long envId = createTestEnv(pid);
        long caseId = createApprovedCase(pid);
        return execTaskService.create(new ExecTaskCreateInput(pid, caseId, "v2", ACTION_JSON_SQL_EXEC, envId));
    }

    @Test
    void submitResult_sqlExec执行_落db_exec审计留痕且零数据值() {
        long taskId = createSqlExecTask();
        long exId = registerExecutor("ex-svc-dbexec", 2);
        ClaimTaskView view = execTaskService.claim(exId);

        execTaskService.submitResult(new ExecResultInput(
                taskId, view.claimToken(), "pass", null, null,
                "[{\"stepNo\":1,\"action\":\"sql.exec\",\"status\":\"ok\","
                        + "\"detail\":\"sql.exec UPDATE 影响 1 行（连接「DB_SIT」）\"}]",
                null, 900, null));

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT project_id, before_json::text AS before_json FROM tp_audit_log"
                        + " WHERE action = 'db_exec' AND object_type = 'exec_task' AND object_id = ?",
                String.valueOf(taskId));
        assertEquals(1, rows.size(), "sql.exec 执行回传必须落且仅落 1 条 db_exec 审计行");
        String before = (String) rows.get(0).get("before_json");
        assertNotNull(before, "before=执行摘要（契约 L69）");
        assertTrue(before.contains("UPDATE t_order SET status"), "摘要须含语句骨架: " + before);
        assertTrue(before.contains(":oid"), "具名参数名保留（非实值）: " + before);
        assertFalse(before.contains("PENDING"), "字面量数据值禁进摘要（TC-B11-06）: " + before);
        assertFalse(before.contains("9999"), "绑定参数实值禁进摘要（TC-B11-06）: " + before);
        assertNotNull(rows.get(0).get("project_id"), "projectId 透传（项目隔离查询面）");
    }

    @Test
    void submitResult_纯UI任务_零db_exec审计行() {
        long taskId = createTask();
        long exId = registerExecutor("ex-svc-ui-only", 2);
        ClaimTaskView view = execTaskService.claim(exId);

        execTaskService.submitResult(new ExecResultInput(
                taskId, view.claimToken(), "pass", null, null,
                "[{\"stepNo\":1,\"action\":\"goto\",\"status\":\"ok\"}]",
                null, 500, null));

        Integer cnt = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_audit_log WHERE action = 'db_exec' AND object_id = ?",
                Integer.class, String.valueOf(taskId));
        assertEquals(0, cnt, "纯 UI 任务零审计行（触发条件=时间线含 sql.exec 步）");
    }

    @Test
    void submitResult_重复回传_幂等不报错() {
        long taskId = createTask();
        long exId = registerExecutor("ex-svc-dup", 2);
        ClaimTaskView view = execTaskService.claim(exId);

        execTaskService.submitResult(new ExecResultInput(
                taskId, view.claimToken(), "pass", null, null, null, null, 100, null));
        execTaskService.submitResult(new ExecResultInput(
                taskId, view.claimToken(), "pass", null, null, null, null, 100, null));

        assertEquals(3, execTaskService.get(taskId).status());
    }

    @Test
    void get_任务不存在_抛人话异常() {
        ServiceException ex = assertThrows(ServiceException.class, () -> execTaskService.get(999999L));
        assertTrue(ex.getMessage().contains("执行任务不存在"), "错误信息应人话: " + ex.getMessage());
    }

    @Test
    void listScreenshots_任务不存在_抛人话异常() {
        ServiceException ex = assertThrows(ServiceException.class, () -> execTaskService.listScreenshots(999999L));
        assertTrue(ex.getMessage().contains("执行任务不存在"), "错误信息应人话: " + ex.getMessage());
    }

    @Test
    void listByProject_分页返回() {
        long pidA = createProject();
        long envA = createTestEnv(pidA);
        for (int i = 0; i < 3; i++) {
            long caseA = createApprovedCase(pidA);
            execTaskService.create(new ExecTaskCreateInput(pidA, caseA, "v2", ACTION_JSON, envA));
        }
        long pidB = createProject();
        long envB = createTestEnv(pidB);
        long caseB = createApprovedCase(pidB);
        execTaskService.create(new ExecTaskCreateInput(pidB, caseB, "v2", ACTION_JSON, envB));

        List<ExecTaskInfo> page = execTaskService.listByProject(pidA, 1, 2);
        assertEquals(2, page.size());
        assertEquals(3, execTaskService.countByProject(pidA));
        assertTrue(page.get(0).id() > page.get(1).id());
    }


    @Test
    void listByProject_size1000_返回1000条_超限钳制() {
        long pid = createProject();
        long caseId = createApprovedCase(pid);
        jdbcTemplateUpdateTasks(pid, caseId, 1005);

        List<ExecTaskInfo> page = execTaskService.listByProject(pid, 1, 1000);
        assertEquals(1000, page.size(), "size=1000 必须返回 1000 条（旧上限 100 会截断）");
        assertEquals(1005, execTaskService.countByProject(pid));

        List<ExecTaskInfo> over = execTaskService.listByProject(pid, 1, 2000);
        assertEquals(1000, over.size(), "超限钳制 1000（防超大请求）");
    }

    private void jdbcTemplateUpdateTasks(long projectId, long caseId, int n) {
        jdbcTemplate.update("""
                INSERT INTO tp_exec_task (project_id, case_id, status, created_at)
                SELECT ?, ?, 1, now() FROM generate_series(1, ?)
                """, projectId, caseId, n);
    }

    // ── Batch-promotion source picker: server-side narrowing by batch + status (AW2 read door) ──

    @Test
    void listByProject_batchAndStatusNarrowing_keepsOnlyMatchingRows() {
        long pid = createProject();
        long caseId = createApprovedCase(pid);
        long batchA = insertBatchRow(pid);
        long batchB = insertBatchRow(pid);
        insertBatchedTask(pid, caseId, batchA, 4);   // failed — inside the promotion window
        insertBatchedTask(pid, caseId, batchA, 5);   // timeout — inside the promotion window
        insertBatchedTask(pid, caseId, batchA, 3);   // success — outside the status window
        insertBatchedTask(pid, caseId, batchB, 4);   // other batch — outside the batch window
        insertBatchedTask(pid, caseId, null, 4);     // trial run — outside the batch window

        List<ExecTaskInfo> rows = execTaskService.listByProject(pid, 1, 10, null, null, batchA, "4,5");
        assertEquals(2, rows.size(), "only batch A failed/timeout rows may come back");
        assertTrue(rows.stream().allMatch(r -> r.status() == 4 || r.status() == 5));
        assertEquals(2, execTaskService.countByProject(pid, batchA, "4,5"));
        assertEquals(1, execTaskService.countByProject(pid, batchB, "4,5"));
        assertEquals(3, execTaskService.countByProject(pid, batchA, null));
        assertEquals(5, execTaskService.countByProject(pid, null, null));
        assertEquals(5, execTaskService.listByProject(pid, 1, 10, null, null, null, null).size());
    }

    @Test
    void listByProject_statusFilterOffWhitelist_isRefusedFailClosed() {
        long pid = createProject();
        assertThrows(ValidationException.class,
                () -> execTaskService.listByProject(pid, 1, 10, null, null, null, "7"));
        assertThrows(ValidationException.class,
                () -> execTaskService.listByProject(pid, 1, 10, null, null, null, "4,abc"));
        assertThrows(ValidationException.class,
                () -> execTaskService.countByProject(pid, null, "0"));
    }

    private long insertBatchRow(long projectId) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO tp_exec_batch (project_id, name, type, env_version_snapshot, status)
                VALUES (?, 'eval-source-batch', 1, '1.0.0-snap', 6) RETURNING id
                """, Long.class, projectId);
    }

    private void insertBatchedTask(long projectId, long caseId, Long batchId, int status) {
        jdbcTemplate.update(
                "INSERT INTO tp_exec_task (project_id, case_id, status, batch_id, created_at)"
                        + " VALUES (?, ?, ?, ?, now())",
                projectId, caseId, status, batchId);
    }

 // ---------- server-side sort over the project task window ----------

 /**
 * Seed four rows whose id / duration_ms / created_at / status orders disagree
 * on purpose (id 1..4 → duration 300/100/200/400, created_at newest→oldest,
 * status 3/4/3/1): every whitelisted sort key then proves a different ORDER BY
 * path, and the duplicated status 3 pins the id tie-breaker for paging.
 */
    private void seedSortFixture(long projectId, long caseId) {
        jdbcTemplate.update("""
                INSERT INTO tp_exec_task (project_id, case_id, status, schema_version, duration_ms, created_at)
                VALUES (?, ?, 3, 'v2', 300, now()),
                       (?, ?, 4, 'v2', 100, now() - interval '2 minutes'),
                       (?, ?, 3, 'v2', 200, now() - interval '3 minutes'),
                       (?, ?, 1, 'v2', 400, now() - interval '4 minutes')
                """, projectId, caseId, projectId, caseId, projectId, caseId, projectId, caseId);
    }

    private List<Integer> durationsOf(List<ExecTaskInfo> list) {
        return list.stream().map(ExecTaskInfo::durationMs).toList();
    }

    private long seedSortedTaskWindow() {
        long pid = createProject();
        long caseId = createApprovedCase(pid);
        seedSortFixture(pid, caseId);
        return pid;
    }

 /** Null sort keys keep the historical id DESC window; the 3-arg legacy call
 * delegates to the same default (zero behaviour change for old callers). */
    @Test
    void listByProject_sortAbsent_keepsHistoricalIdDesc() {
        long pid = seedSortedTaskWindow();
        List<Integer> expected = List.of(400, 200, 100, 300);
        assertEquals(expected, durationsOf(execTaskService.listByProject(pid, 1, 10, null, null)));
        assertEquals(expected, durationsOf(execTaskService.listByProject(pid, 1, 10, "", "")));
        assertEquals(expected, durationsOf(execTaskService.listByProject(pid, 1, 10)));
    }

 /** duration_ms asc lists the whole scoped window; desc is its mirror. */
    @Test
    void listByProject_sortDuration_bothDirections() {
        long pid = seedSortedTaskWindow();
        assertEquals(List.of(100, 200, 300, 400),
                durationsOf(execTaskService.listByProject(pid, 1, 10, "duration_ms", "asc")));
        assertEquals(List.of(400, 300, 200, 100),
                durationsOf(execTaskService.listByProject(pid, 1, 10, "duration_ms", "desc")));
    }

 /** created_at desc differs from the id order here — proves the query really
 * orders by the requested column instead of falling back to id. */
    @Test
    void listByProject_sortCreatedAtDesc_isNotIdOrder() {
        long pid = seedSortedTaskWindow();
        assertEquals(List.of(300, 100, 200, 400),
                durationsOf(execTaskService.listByProject(pid, 1, 10, "created_at", "desc")));
    }

 /** status asc with the id DESC tie-breaker: the two status-3 rows stay in id
 * desc order (id 3 before id 1), so paging windows under equal values are deterministic. */
    @Test
    void listByProject_sortStatus_tiePinnedByIdDesc() {
        long pid = seedSortedTaskWindow();
        List<ExecTaskInfo> sorted = execTaskService.listByProject(pid, 1, 10, "status", "asc");
        assertEquals(List.of(1, 3, 3, 4), sorted.stream().map(ExecTaskInfo::status).toList());
        assertEquals(List.of(400, 200, 300, 100), durationsOf(sorted));
        assertTrue(sorted.get(1).id() > sorted.get(2).id(), "equal-status rows must order by id desc");
    }

 /** Sort continues across pages: size-2 windows of a duration asc ordering. */
    @Test
    void listByProject_sortWithPaging_continuesOrder() {
        long pid = seedSortedTaskWindow();
        assertEquals(List.of(100, 200), durationsOf(execTaskService.listByProject(pid, 1, 2, "duration_ms", "asc")));
        assertEquals(List.of(300, 400), durationsOf(execTaskService.listByProject(pid, 2, 2, "duration_ms", "asc")));
    }

 /** Injection attempts and off-list keys are refused fail-closed with the
 * whitelist named in the message; the table stays intact afterwards. */
    @Test
    void listByProject_sortOffWhitelist_rejectedFailClosed() {
        long pid = seedSortedTaskWindow();
        for (String bad : List.of("1; DROP TABLE tp_exec_task", "project_id")) {
            ServiceException ex = assertThrows(ServiceException.class,
                    () -> execTaskService.listByProject(pid, 1, 10, bad, "asc"));
            assertTrue(ex.getMessage().contains("不在白名单"), "人话报错须含白名单提示: " + ex.getMessage());
            assertTrue(ex.getMessage().contains("duration_ms"), "报错须点名可选键: " + ex.getMessage());
        }
        assertEquals(4, execTaskService.countByProject(pid), "拒后表数据必须完好");
    }


    @Test
    void create_未选环境_400人话() {
        long pid = createProject();
        long caseId = createApprovedCase(pid);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> execTaskService.create(new ExecTaskCreateInput(pid, caseId, "v2", ACTION_JSON, null)));
        assertTrue(ex.getMessage().contains("请选择执行环境"), "错误信息应人话: " + ex.getMessage());
    }

    @Test
    void create_环境不存在或不属于项目_400人话() {
        long pid = createProject();
        long envId = createTestEnv(pid);
        long caseId = createApprovedCase(pid);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> execTaskService.create(new ExecTaskCreateInput(pid, caseId, "v2", ACTION_JSON, 999999L)));
        assertTrue(ex.getMessage().contains("环境不存在或不属于该项目"),
                "错误信息应人话且不泄露: " + ex.getMessage());

        long pidB = createProject();
        long caseB = createApprovedCase(pidB);
        ServiceException ex2 = assertThrows(ServiceException.class,
                () -> execTaskService.create(new ExecTaskCreateInput(pidB, caseB, "v2", ACTION_JSON, envId)));
        assertTrue(ex2.getMessage().contains("环境不存在或不属于该项目"),
                "跨项目环境必须 400 人话: " + ex2.getMessage());
    }

    @Test
    void create_生产环境_默认禁止执行() {
        long pid = createProject();
        long prodEnv = createEnv(pid, 4, "生产环境", "https://prod.example.com", "v1.0.0");
        long caseId = createApprovedCase(pid);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> execTaskService.create(new ExecTaskCreateInput(pid, caseId, "v2", ACTION_JSON, prodEnv)));
        assertTrue(ex.getMessage().contains("生产环境默认禁止执行"),
                "生产防护拒绝应人话: " + ex.getMessage());
    }

    @Test
    void create_非生产环境命中黑名单_拒绝() {
        long pid = createProject();
        long envId = createEnv(pid, 2, "测试环境", "https://admin.example.com", "v2.0.0");
        long caseId = createApprovedCase(pid);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> execTaskService.create(new ExecTaskCreateInput(pid, caseId, "v2", ACTION_JSON, envId)));
        assertTrue(ex.getMessage().contains("命中生产域名黑名单"),
                "黑名单拦截应人话: " + ex.getMessage());
    }

    @Test
    void create_黑名单未命中_放行() {
        long pid = createProject();
        long envId = createEnv(pid, 1, "开发环境", "http://dev.internal.local", "v0.9.0");
        long caseId = createApprovedCase(pid);

        long taskId = execTaskService.create(new ExecTaskCreateInput(pid, caseId, "v2", ACTION_JSON, envId));

        ExecTaskInfo task = execTaskService.get(taskId);
        assertEquals("http://dev.internal.local", task.envBaseUrl());
        assertEquals("v0.9.0", task.envVersionSnapshot());
    }

    @Test
    void create_环境版本快照落库_claim下发baseUrl() {
        long pid = createProject();
        long envId = createEnv(pid, 3, "预发环境", "http://staging.local", "v2.2.0");
        long caseId = createApprovedCase(pid);
        long taskId = execTaskService.create(new ExecTaskCreateInput(pid, caseId, "v2", ACTION_JSON, envId));

        ExecTaskInfo task = execTaskService.get(taskId);
        assertEquals("http://staging.local", task.envBaseUrl(), "baseUrl 从环境表解析");
        assertEquals("v2.2.0", task.envVersionSnapshot(), "执行记录带所选环境版本");

        long exId = registerExecutor("ex-svc-env", 2);
        ClaimTaskView view = execTaskService.claim(exId);
        assertNotNull(view);
        assertEquals("http://staging.local", view.envBaseUrl());
    }

    @Test
    void claim_环境无参数_envVars空map() {
        long pid = createProject();
        long envId = createTestEnv(pid);
        long caseId = createApprovedCase(pid);
        execTaskService.create(new ExecTaskCreateInput(pid, caseId, "v2", ACTION_JSON, envId));
        long exId = registerExecutor("ex-svc-vars-empty", 2);

        ClaimTaskView view = execTaskService.claim(exId);

        assertNotNull(view);
        assertNotNull(view.envVars(), "无参数=空 map 而非 null（执行器侧契约稳定）");
        assertTrue(view.envVars().isEmpty(), "无参数 envVars 为空 map");
    }

    @Test
    void claim_环境已删除_envVars空map不阻断下发() {
        long pid = createProject();
        long envId = createTestEnv(pid);
        long caseId = createApprovedCase(pid);
        long taskId = execTaskService.create(new ExecTaskCreateInput(pid, caseId, "v2", ACTION_JSON, envId));
        projectEnvService.delete(pid, envId);
        long exId = registerExecutor("ex-svc-vars-gone", 2);

        ClaimTaskView view = execTaskService.claim(exId);

        assertNotNull(view, "环境已删不阻断领取（任务自带 baseUrl 快照）");
        assertEquals(taskId, view.taskId());
        assertTrue(view.envVars().isEmpty());
    }

    @Test
    void claim_历史明文参数键_原样下发不解密() {
        long pid = createProject();
        long envId = createTestEnv(pid);
        jdbcTemplate.update("UPDATE tp_project_env SET config_json = '{\"params\":{\"LEGACY\":\"plain-value\"}}'::jsonb WHERE id = ?", envId);
        long caseId = createApprovedCase(pid);
        execTaskService.create(new ExecTaskCreateInput(pid, caseId, "v2", ACTION_JSON, envId));
        long exId = registerExecutor("ex-svc-vars-legacy", 2);

        ClaimTaskView view = execTaskService.claim(exId);

        assertNotNull(view);
        assertEquals(java.util.Map.of("LEGACY", "plain-value"), view.envVars(), "历史明文键值原样下发");
    }

    @Test
    void claim_ENC密文参数_解密明文下发_库内零明文() {
        Assumptions.assumeTrue(configCipher.isEnabled(),
                "密文解密钉需 CONFIG_AES_KEY（.env 注入）");
        long pid = createProject();
        long envId = createTestEnv(pid);
        projectEnvService.saveParams(pid, envId, new EnvParamsInput(
                Map.of("ACCOUNT", "qa_user", "PASSWORD", "p@ss!w0rd")));
        long caseId = createApprovedCase(pid);
        execTaskService.create(new ExecTaskCreateInput(pid, caseId, "v2", ACTION_JSON, envId));
        long exId = registerExecutor("ex-svc-vars-enc", 2);

        ClaimTaskView view = execTaskService.claim(exId);

        assertNotNull(view);
        assertEquals(java.util.Map.of("ACCOUNT", "qa_user", "PASSWORD", "p@ss!w0rd"),
                view.envVars(), "claim 下发=解密明文 map");
        String stored = jdbcTemplate.queryForObject(
                "SELECT config_json::text FROM tp_project_env WHERE id = ?", String.class, envId);
        assertFalse(stored.contains("qa_user"), "DB 侧保持密文零明文（下发时解密不落库）");
    }

    @Test
    void create_环境版本为空_历史回退baseUrl() {
        long pid = createProject();
        long envId = createEnv(pid, 2, "测试环境", "http://demo.local", null);
        long caseId = createApprovedCase(pid);
        long taskId = execTaskService.create(new ExecTaskCreateInput(pid, caseId, "v2", ACTION_JSON, envId));

        PageResult<CaseExecHistoryView> page = execTaskService.listHistoryByCase(caseId, pid, 1, 10);
        assertEquals(1, page.total());
        assertEquals("http://demo.local", page.list().get(0).envVersion(), "版本空回退 base_url");
    }


    @Test
    void create_已通过用例_进入执行中() {
        long pid = createProject();
        long envId = createTestEnv(pid);
        long caseId = createApprovedCase(pid);
        assertEquals(3, caseStatus(caseId), "前置：用例已通过");

        execTaskService.create(new ExecTaskCreateInput(pid, caseId, "v2", ACTION_JSON, envId));

        assertEquals(6, caseStatus(caseId), "创建执行任务后已通过用例应进入执行中(6)");
    }

    @Test
    void result回填_fail_执行中变执行失败() {
        long pid = createProject();
        long envId = createTestEnv(pid);
        long caseId = createApprovedCase(pid);
        long taskId = execTaskService.create(new ExecTaskCreateInput(pid, caseId, "v2", ACTION_JSON, envId));
        long exId = registerExecutor("ex-svc-j2-fail", 2);
        ExecTaskInfo task = runToClaimed(taskId, exId);

        execTaskService.submitResult(new ExecResultInput(task.id(), task.claimToken(), "fail", 1,
                "产品缺陷：优惠券未生效", null, null, 1200, null));

        assertEquals(7, caseStatus(caseId), "执行失败应回填用例状态为执行失败(7)");
    }

    @Test
    void result回填_pass_执行失败恢复已通过() {
        long pid = createProject();
        long envId = createTestEnv(pid);
        long caseId = createApprovedCase(pid);
        jdbcTemplate.update("UPDATE tp_test_case SET status = 7 WHERE id = ?", caseId);
        long taskId = execTaskService.create(new ExecTaskCreateInput(pid, caseId, "v2", ACTION_JSON, envId));
        long exId = registerExecutor("ex-svc-j2-pass", 2);
        ExecTaskInfo task = runToClaimed(taskId, exId);

        execTaskService.submitResult(new ExecResultInput(task.id(), task.claimToken(), "pass",
                null, null, null, null, 3500, null));

        assertEquals(3, caseStatus(caseId), "重跑执行通过应恢复用例为已通过(3)");
    }

    @Test
    void result回填_fail_已通过状态变回归失败() {
        long pid = createProject();
        long envId = createTestEnv(pid);
        long caseId = createApprovedCase(pid);
        long taskId = execTaskService.create(new ExecTaskCreateInput(pid, caseId, "v2", ACTION_JSON, envId));
        jdbcTemplate.update("UPDATE tp_test_case SET status = 3 WHERE id = ?", caseId);
        long exId = registerExecutor("ex-svc-j2-reg", 2);
        ExecTaskInfo task = runToClaimed(taskId, exId);

        execTaskService.submitResult(new ExecResultInput(task.id(), task.claimToken(), "fail", 3,
                "环境问题", null, null, 800, null));

        assertEquals(8, caseStatus(caseId), "已通过用例执行失败应回填为回归失败(8)");
    }


    @Test
    void listHistoryByCase_批次与试运行聚合_字段完整() {
        long pid = createProject();
        long envId = createTestEnv(pid);
        long caseId = createApprovedCase(pid);
        Long batchId = jdbcTemplate.queryForObject("""
                INSERT INTO tp_exec_batch (project_id, name, type, env_version_snapshot, status)
                VALUES (?, '周末回归批次', 1, '2.1.0-snap', 6) RETURNING id
                """, Long.class, pid);
        long batchTaskId = execTaskService.create(new ExecTaskCreateInput(pid, caseId, "v2", ACTION_JSON, envId));
        jdbcTemplate.update("UPDATE tp_exec_task SET batch_id = ? WHERE id = ?", batchId, batchTaskId);
        long exA = registerExecutor("ex-svc-j5-a", 2);
        ExecTaskInfo batchTask = runToClaimed(batchTaskId, exA);
        execTaskService.submitResult(new ExecResultInput(batchTask.id(), batchTask.claimToken(), "fail", 2,
                "定位失败: #login 元素不存在",
                "[{\"stepNo\":1,\"action\":\"goto\",\"status\":\"fail\"}]",
                "[\"exec/" + batchTaskId + "/screenshot/1_step1.png\"]", 1500, null));

        long trialTaskId = execTaskService.create(new ExecTaskCreateInput(pid, caseId, "v2", ACTION_JSON, envId));
        long exB = registerExecutor("ex-svc-j5-b", 2);
        ExecTaskInfo trialTask = runToClaimed(trialTaskId, exB);
        execTaskService.submitResult(new ExecResultInput(trialTask.id(), trialTask.claimToken(), "pass",
                null, null, null, null, 3500, null));

        PageResult<CaseExecHistoryView> page = execTaskService.listHistoryByCase(caseId, pid, 1, 10);

        assertEquals(2, page.total(), "批次 + 试运行 2 条应聚合");
        assertEquals(2, page.list().size());
        CaseExecHistoryView first = page.list().get(0);
        CaseExecHistoryView second = page.list().get(1);
        assertNull(first.batchId());
        assertNull(first.batchName());
        assertNull(first.batchType());
        assertEquals("v2.1.0", first.envVersion(), "试运行环境版本 = env_version_snapshot（J6 落库）");
        assertEquals(3, first.status(), "试运行结果=成功");
        assertNotNull(first.startedAt());
        assertNotNull(first.finishedAt());
        assertEquals(batchId, second.batchId());
        assertEquals("周末回归批次", second.batchName());
        assertEquals(1, second.batchType());
        assertEquals("2.1.0-snap", second.envVersion(), "批次环境版本 = env_version_snapshot 快照");
        assertEquals(4, second.status(), "批次结果=失败");
        assertEquals(2, second.errorType());
        assertEquals("定位失败: #login 元素不存在", second.errorDetail(), "失败原因人话完整");
        assertEquals(1500, second.durationMs());
        assertEquals(1, second.screenshots().size(), "截图缩略应解析出 1 条");
        assertEquals("exec/" + batchTaskId + "/screenshot/1_step1.png", second.screenshots().get(0).objectKey());
        assertTrue(second.screenshots().get(0).url().contains("X-Amz-Signature"),
                "截图 URL 必须为预签名 GET: " + second.screenshots().get(0).url());
    }

    @Test
    void listHistoryByCase_分页倒序() {
        long pid = createProject();
        long envId = createTestEnv(pid);
        long caseId = createApprovedCase(pid);
        for (int i = 0; i < 3; i++) {
            execTaskService.create(new ExecTaskCreateInput(pid, caseId, "v2", ACTION_JSON, envId));
        }

        PageResult<CaseExecHistoryView> page = execTaskService.listHistoryByCase(caseId, pid, 1, 2);

        assertEquals(3, page.total());
        assertEquals(2, page.list().size());
        assertTrue(page.list().get(0).id() > page.list().get(1).id(), "按 id 倒序（最新在前）");
    }

    @Test
    void listHistoryByCase_跨项目隔离_404隐藏() {
        long pid = createProject();
        long envId = createTestEnv(pid);
        long caseId = createApprovedCase(pid);
        execTaskService.create(new ExecTaskCreateInput(pid, caseId, "v2", ACTION_JSON, envId));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> execTaskService.listHistoryByCase(caseId, 999999L, 1, 10));
        assertTrue(ex.getMessage().contains("不存在"), "错误信息应人话且不泄露: " + ex.getMessage());

        ServiceException ex2 = assertThrows(ServiceException.class,
                () -> execTaskService.listHistoryByCase(999999L, pid, 1, 10));
        assertTrue(ex2.getMessage().contains("不存在"), "错误信息应人话且不泄露: " + ex2.getMessage());
    }

    @Test
    void listHistoryByCase_app信封行投影引擎编码_web行诚实null() {
        long pid = createProject();
        long envId = createTestEnv(pid);
        long caseId = createApprovedCase(pid);
        long webTaskId = execTaskService.create(
                new ExecTaskCreateInput(pid, caseId, "v2", ACTION_JSON, envId));
        executorService.register(new ExecutorRegisterInput(
                "hist-app-ex-" + System.nanoTime(), null, null, "web,app", "chromium", 2,
                "1.0.0", 0, Map.of("engines", List.of("MOBILEWRIGHT"),
                        "platforms", List.of("ANDROID"), "deviceKinds", List.of("EMULATOR"))));
        long appTaskId = execTaskService.createAppAutoRun(pid, caseId, null, APP_ENVELOPE_JSON);

        PageResult<CaseExecHistoryView> page = execTaskService.listHistoryByCase(caseId, pid, 1, 10);

        assertEquals(2, page.total(), "APP 任务 + web 任务 2 行聚合");
        CaseExecHistoryView appRow = page.list().stream()
                .filter(v -> v.id() == appTaskId).findFirst().orElseThrow();
        assertEquals("MOBILEWRIGHT", appRow.engineCode(), "APP 信封 engine.code 必须投影到历史行");
        CaseExecHistoryView webRow = page.list().stream()
                .filter(v -> v.id() == webTaskId).findFirst().orElseThrow();
        assertNull(webRow.engineCode(), "web 信封无引擎维度=null（对照视图据此分流 App 行）");
    }

    @Test
    void listHistoryByCase_无执行记录_返回空列表() {
        long pid = createProject();
        long caseId = createApprovedCase(pid);

        PageResult<CaseExecHistoryView> page = execTaskService.listHistoryByCase(caseId, pid, 1, 10);

        assertEquals(0, page.total());
        assertTrue(page.list().isEmpty());
    }


    
    private long[] createStatusSpread(long exId) {
        long[] ids = new long[6];
        for (int i = 0; i < 6; i++) {
            ids[i] = createTask();
        }
        ExecTaskInfo[] claimed = new ExecTaskInfo[5];
        for (int i = 0; i < 5; i++) {
            ClaimTaskView view = execTaskService.claim(exId);
            assertNotNull(view, "第 " + (i + 1) + " 次领取应有任务");
            claimed[i] = execTaskService.get(view.taskId());
        }
        execTaskService.submitResult(new ExecResultInput(claimed[1].id(), claimed[1].claimToken(), "pass",
                null, null, null, null, 100, "console: 登录成功"));
        execTaskService.submitResult(new ExecResultInput(claimed[2].id(), claimed[2].claimToken(), "manual_skip",
                null, null, null, null, 50, null));
        execTaskService.submitResult(new ExecResultInput(claimed[3].id(), claimed[3].claimToken(), "fail", 2,
                "定位失败: #submit 不存在", null, null, 200, "console: element not found"));
        jdbcTemplate.update("UPDATE tp_exec_task SET status = 5 WHERE id = ?", claimed[4].id());
        return ids;
    }

    @Test
    void listByExecutor_分组计数与状态过滤() {
        long exId = registerExecutor("ex-svc-k2-count", 6);
        createStatusSpread(exId);

        ExecutorTaskPageView all = execTaskService.listByExecutor(exId, "all", 1, 10);
        assertEquals(1, all.pending(), "待执行=1（1待派发）");
        assertEquals(1, all.running(), "执行中=1（2执行中）");
        assertEquals(2, all.done(), "已完成=2（3成功 + 6跳过）");
        assertEquals(2, all.failed(), "失败=2（4失败 + 5超时）");
        assertEquals(5, all.total(), "执行器维度任务=5（待派发 t6 未归属执行器）");

        ExecutorTaskPageView pending = execTaskService.listByExecutor(exId, "pending", 1, 10);
        assertEquals(1, pending.list().size());
        assertEquals(1, pending.list().get(0).status());

        ExecutorTaskPageView done = execTaskService.listByExecutor(exId, "done", 1, 10);
        assertEquals(2, done.list().size());
        assertTrue(done.list().stream().allMatch(t -> t.status() == 3 || t.status() == 6));

        ExecutorTaskPageView failed = execTaskService.listByExecutor(exId, "failed", 1, 10);
        assertEquals(2, failed.list().size());
        assertTrue(failed.list().stream().allMatch(t -> t.status() == 4 || t.status() == 5));
    }

    @Test
    void listByExecutor_无任务_空计数() {
        long exId = registerExecutor("ex-svc-k2-empty", 2);
        ExecutorTaskPageView page = execTaskService.listByExecutor(exId, "all", 1, 10);
        assertEquals(0, page.pending());
        assertEquals(0, page.running());
        assertEquals(0, page.done());
        assertEquals(0, page.failed());
        assertEquals(0, page.total());
        assertTrue(page.list().isEmpty());
    }

    // ---------- server-side sort over the executor task window ----------

    /**
     * Seed four executor-owned rows whose id / duration_ms / created_at / status
     * orders disagree on purpose (insertion ids ascending with durations
     * 300/100/200/400, created_at newest→oldest, status 3/4/3/2): every
     * whitelisted key then proves a different ORDER BY path, the duplicated
     * status 3 pins the id DESC tie-break, and the executor window stays
     * independent from other executors' rows in the shared database.
     */
    private void seedExecutorSortWindow(long exId, long projectId, long caseId) {
        jdbcTemplate.update("""
                INSERT INTO tp_exec_task (project_id, case_id, executor_id, status, schema_version, duration_ms, created_at)
                VALUES (?, ?, ?, 3, 'v2', 300, now()),
                       (?, ?, ?, 4, 'v2', 100, now() - interval '2 minutes'),
                       (?, ?, ?, 3, 'v2', 200, now() - interval '3 minutes'),
                       (?, ?, ?, 2, 'v2', 400, now() - interval '4 minutes')
                """, projectId, caseId, exId, projectId, caseId, exId, projectId, caseId, exId, projectId, caseId, exId);
    }

    private long[] seedExecutorSortFixture(String executorName) {
        long exId = registerExecutor(executorName, 2);
        long pid = createProject();
        long caseId = createApprovedCase(pid);
        seedExecutorSortWindow(exId, pid, caseId);
        return new long[]{exId, pid, caseId};
    }

    /** Null sort keys keep the historical id DESC window; the 4-arg legacy call
     * delegates to the same default (zero behaviour change for old callers). */
    @Test
    void listByExecutor_sortAbsent_keepsHistoricalIdDesc() {
        long[] fx = seedExecutorSortFixture("ex-svc-k2-sort-default");
        List<Integer> expected = List.of(400, 200, 100, 300);
        assertEquals(expected, durationsOf(execTaskService.listByExecutor(fx[0], "all", 1, 10, null, null).list()));
        assertEquals(expected, durationsOf(execTaskService.listByExecutor(fx[0], "all", 1, 10, "", "").list()));
        assertEquals(expected, durationsOf(execTaskService.listByExecutor(fx[0], "all", 1, 10).list()));
    }

    /** duration_ms asc lists the whole executor window; desc is its mirror. */
    @Test
    void listByExecutor_sortDuration_bothDirections() {
        long[] fx = seedExecutorSortFixture("ex-svc-k2-sort-duration");
        assertEquals(List.of(100, 200, 300, 400),
                durationsOf(execTaskService.listByExecutor(fx[0], "all", 1, 10, "duration_ms", "asc").list()));
        assertEquals(List.of(400, 300, 200, 100),
                durationsOf(execTaskService.listByExecutor(fx[0], "all", 1, 10, "duration_ms", "desc").list()));
    }

    /** created_at desc differs from the id order here — proves the query really
     * orders by the requested column instead of falling back to id. */
    @Test
    void listByExecutor_sortCreatedAtDesc_isNotIdOrder() {
        long[] fx = seedExecutorSortFixture("ex-svc-k2-sort-created");
        assertEquals(List.of(300, 100, 200, 400),
                durationsOf(execTaskService.listByExecutor(fx[0], "all", 1, 10, "created_at", "desc").list()));
    }

    /** status asc with the id DESC tie-breaker: the two status-3 rows stay in
     * id desc order, so paging windows under equal values are deterministic. */
    @Test
    void listByExecutor_sortStatus_tiePinnedByIdDesc() {
        long[] fx = seedExecutorSortFixture("ex-svc-k2-sort-status");
        var sorted = execTaskService.listByExecutor(fx[0], "all", 1, 10, "status", "asc").list();
        assertEquals(List.of(2, 3, 3, 4), sorted.stream().map(ExecTaskInfo::status).toList());
        assertEquals(List.of(400, 200, 300, 100), durationsOf(sorted));
        assertTrue(sorted.get(1).id() > sorted.get(2).id(), "equal-status rows must order by id desc");
    }

    /** The pending tab is the other SQL branch (global queue, executor_id is
     * null); it must honour the same whitelist through the shared order clause.
     * The global queue is shared across tests, so this fixture's rows carry
     * durations that top a duration-asc ordering (9001xx = above any real
     * task duration) and the assertion filters to the fixture's own project. */
    @Test
    void listByExecutor_sortAppliesToPendingBranch() {
        long exId = registerExecutor("ex-svc-k2-sort-pending", 2);
        long pid = createProject();
        long caseId = createApprovedCase(pid);
        jdbcTemplate.update("""
                INSERT INTO tp_exec_task (project_id, case_id, status, schema_version, duration_ms, created_at)
                VALUES (?, ?, 1, 'v2', 900300, now()),
                       (?, ?, 1, 'v2', 900100, now()),
                       (?, ?, 1, 'v2', 900200, now()),
                       (?, ?, 1, 'v2', 900400, now())
                """, pid, caseId, pid, caseId, pid, caseId, pid, caseId);
        var rows = execTaskService.listByExecutor(exId, "pending", 1, 200, "duration_ms", "asc").list()
                .stream().filter(t -> t.projectId() == pid).toList();
        assertEquals(List.of(900100, 900200, 900300, 900400), durationsOf(rows));
    }

    // ---------- server-side sort over the running-task window ----------

    /**
     * Seed four running (status=2) rows whose id / batch_id / case_id /
     * executor_id / started_at / elapsed_ms orders disagree on purpose. The
     * assertion keeps the batch_id column as the row identity (it is returned
     * by the view and was chosen per row): rows are inserted in id-ascending
     * order with batches 30/10/40/20, cases 300/100/200/400, executors
     * A/B/A/B and started_at three/four/two/one minutes ago, so every
     * whitelisted key walks a different ORDER BY path and the equal
     * executor_id pairs pin the id ASC tie-break. The per-test fixture sweep
     * keeps the cross-project running window free of other rows (the factory
     * baseline carries zero tp_exec_task rows).
     */
    private long[] seedRunningSortFixture(String executorName) {
        long exA = registerExecutor(executorName + "-A", 2);
        long exB = registerExecutor(executorName + "-B", 2);
        long pid = createProject();
        long caseSmall = createApprovedCase(pid);
        long caseMid1 = createApprovedCase(pid);
        long caseMid2 = createApprovedCase(pid);
        long caseBig = createApprovedCase(pid);
        // explicit case ids keep the wanted order (creation ids are ascending:
        // small < mid1 < mid2 < big) — assign 300/100/200/400 to the four rows.
        jdbcTemplate.update("""
                INSERT INTO tp_exec_task (project_id, case_id, batch_id, executor_id, status, started_at)
                VALUES (?, ?, 30, ?, 2, now() - interval '3 minutes'),
                       (?, ?, 10, ?, 2, now() - interval '4 minutes'),
                       (?, ?, 40, ?, 2, now() - interval '2 minutes'),
                       (?, ?, 20, ?, 2, now() - interval '1 minutes')
                """, pid, caseMid2, exA, pid, caseSmall, exB, pid, caseMid1, exA, pid, caseBig, exB);
        return new long[]{exA, exB, pid};
    }

    private List<Integer> batchesOf(PageResult<RunningTaskView> page) {
        return page.list().stream().map(RunningTaskView::batchId).map(Long::intValue).toList();
    }

    /** No sort key keeps the historical id ASC order of the running window
     * (note: the project task list defaults to id DESC — the two windows keep
     * their own historical order, and the 2-arg legacy call delegates to it). */
    @Test
    void listRunning_sortAbsent_keepsHistoricalIdAsc() {
        seedRunningSortFixture("ex-svc-r53c-default");
        List<Integer> expected = List.of(30, 10, 40, 20);
        assertEquals(expected, batchesOf(execTaskService.listRunning(1, 20, null, null)));
        assertEquals(expected, batchesOf(execTaskService.listRunning(1, 20, "", "")));
        assertEquals(expected, batchesOf(execTaskService.listRunning(1, 20)));
    }

    /** batch_id asc/desc mirror each other over the whole window. */
    @Test
    void listRunning_sortBatchId_bothDirections() {
        seedRunningSortFixture("ex-svc-r53c-batch");
        assertEquals(List.of(10, 20, 30, 40), batchesOf(execTaskService.listRunning(1, 20, "batch_id", "asc")));
        assertEquals(List.of(40, 30, 20, 10), batchesOf(execTaskService.listRunning(1, 20, "batch_id", "desc")));
    }

    /** case_id differs from the id order here — proves the SQL really
     * re-orders by the requested column instead of falling back to id. */
    @Test
    void listRunning_sortCaseId_isNotIdOrder() {
        seedRunningSortFixture("ex-svc-r53c-case");
        assertEquals(List.of(10, 40, 30, 20), batchesOf(execTaskService.listRunning(1, 20, "case_id", "asc")));
        assertEquals(List.of(20, 30, 40, 10), batchesOf(execTaskService.listRunning(1, 20, "case_id", "desc")));
    }

    /** executor_id asc groups the two executors; inside each group the id ASC
     * tie-break keeps paging deterministic under equal values. */
    @Test
    void listRunning_sortExecutorId_tiePinnedByIdAsc() {
        long[] fx = seedRunningSortFixture("ex-svc-r53c-executor");
        var asc = batchesOf(execTaskService.listRunning(1, 20, "executor_id", "asc"));
        // exA rows (id ascending) first, then exB rows: batch 30,40 then 10,20
        assertEquals(List.of(30, 40, 10, 20), asc);
        // the id ASC tie-break stays constant regardless of the primary direction
        // (same posture as the executor window's constant id DESC pin)
        assertEquals(List.of(10, 20, 30, 40), batchesOf(
                execTaskService.listRunning(1, 20, "executor_id", "desc")));
        assertTrue(fx[0] < fx[1], "fixture assumes exA id < exB id");
    }

    /** started_at asc = oldest first; the order disagrees with id order. */
    @Test
    void listRunning_sortStartedAt_ordersByColumn() {
        seedRunningSortFixture("ex-svc-r53c-started");
        assertEquals(List.of(10, 30, 40, 20), batchesOf(execTaskService.listRunning(1, 20, "started_at", "asc")));
        assertEquals(List.of(20, 40, 30, 10), batchesOf(execTaskService.listRunning(1, 20, "started_at", "desc")));
    }

    /** elapsed_ms is the computed output alias (now - started_at): the sort
     * reuses the same expression the row renders, so the header order matches
     * the visible duration instead of a second clock. */
    @Test
    void listRunning_sortElapsedMs_usesRenderedAlias() {
        seedRunningSortFixture("ex-svc-r53c-elapsed");
        assertEquals(List.of(20, 40, 30, 10), batchesOf(execTaskService.listRunning(1, 20, "elapsed_ms", "asc")));
        assertEquals(List.of(10, 30, 40, 20), batchesOf(execTaskService.listRunning(1, 20, "elapsed_ms", "desc")));
    }

    /** Sorting keeps paging consistent: page 2 continues the order and total
     * stays truthful; a sort change resets to page 1 on the front-end side. */
    @Test
    void listRunning_sortAppliesAcrossPages() {
        seedRunningSortFixture("ex-svc-r53c-paging");
        var first = execTaskService.listRunning(1, 2, "case_id", "asc");
        var second = execTaskService.listRunning(2, 2, "case_id", "asc");
        assertEquals(List.of(10, 40), batchesOf(first));
        assertEquals(List.of(30, 20), batchesOf(second));
        assertEquals(4, first.total());
    }

    /** Injection attempts and off-list keys are refused fail-closed with the
     * whitelist named in the message; the running window stays intact. */
    @Test
    void listRunning_sortOffWhitelist_rejectedFailClosed() {
        seedRunningSortFixture("ex-svc-r53c-reject");
        for (String bad : List.of("1; DROP TABLE tp_exec_task", "project_id", "id DESC, id")) {
            ServiceException ex = assertThrows(ServiceException.class,
                    () -> execTaskService.listRunning(1, 20, bad, "asc"));
            assertTrue(ex.getMessage().contains("不在白名单"), "人话报错须含白名单提示: " + ex.getMessage());
            assertTrue(ex.getMessage().contains("elapsed_ms"), "报错须点名可选键: " + ex.getMessage());
        }
        assertEquals(4, execTaskService.listRunning(1, 20).list().size(), "拒后窗口数据必须完好");
    }

    /** Injection attempts and off-list keys are refused fail-closed with the
     * whitelist named in the message; the executor window stays intact. */
    @Test
    void listByExecutor_sortOffWhitelist_rejectedFailClosed() {
        long[] fx = seedExecutorSortFixture("ex-svc-k2-sort-reject");
        for (String bad : List.of("1; DROP TABLE tp_exec_task", "project_id")) {
            ServiceException ex = assertThrows(ServiceException.class,
                    () -> execTaskService.listByExecutor(fx[0], "all", 1, 10, bad, "asc"));
            assertTrue(ex.getMessage().contains("不在白名单"), "人话报错须含白名单提示: " + ex.getMessage());
            assertTrue(ex.getMessage().contains("duration_ms"), "报错须点名可选键: " + ex.getMessage());
        }
        assertEquals(4, execTaskService.listByExecutor(fx[0], "all", 1, 10).list().size(), "拒后窗口数据必须完好");
    }

    @Test
    void submitResult_console日志落库_详情与logs端点可取() {
        long taskId = createTask();
        long exId = registerExecutor("ex-svc-k2-log", 2);
        ExecTaskInfo task = runToClaimed(taskId, exId);
        String consoleLog = "page.goto → 200 OK\nconsole: 页面加载完成\nconsole: [error] Uncaught TypeError";

        execTaskService.submitResult(new ExecResultInput(task.id(), task.claimToken(), "pass",
                null, null, null, null, 800, consoleLog));

        ExecTaskInfo detail = execTaskService.get(taskId);
        assertEquals(consoleLog, detail.log(), "任务详情应含 console 日志");

        String log = execTaskService.getLog(taskId);
        assertEquals(consoleLog, log, "logs 端点返回同一份日志");
    }

    @Test
    void getLog_任务不存在_抛人话异常() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> execTaskService.getLog(999999L));
        assertTrue(ex.getMessage().contains("执行任务不存在"), "错误信息应人话: " + ex.getMessage());
    }


 
    private void setScriptKey(String key, String value) {
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_sys_config WHERE config_key = ?", Long.class, key);
        assertNotNull(id, "V40 种子键必须存在: " + key);
        configService.update(1L, id, new ConfigInput(key, value, "系统", "int", 0, "测试期临时配置值"));
    }

    @Test
    void claim_下发scriptLimits六键_与配置现值同源_热更下一次领取即生效() {
        long pid = createProject();
        long envId = createTestEnv(pid);
        long caseId = createApprovedCase(pid);
        long taskId = execTaskService.create(new ExecTaskCreateInput(pid, caseId, "v2", ACTION_JSON, envId));
        long exId = registerExecutor("ex-svc-limits", 1);

        setScriptKey("ai.script.max-steps", "7");
        setScriptKey("ai.script.max-loop", "8");
        setScriptKey("ai.script.max-nest-depth", "9");
        setScriptKey("ai.script.assert-timeout-sec", "11");
        setScriptKey("ai.script.screenshot-mode", "2");
        setScriptKey("ai.script.fail-policy", "1");
        try {
            ClaimTaskView view = execTaskService.claim(exId);
            assertNotNull(view, "必须领取到任务");
            assertEquals(taskId, view.taskId());
            Map<String, Integer> limits = view.scriptLimits();
            assertNotNull(limits, "claim 必须下发 scriptLimits（03 §2.3.3）");
            assertEquals(Map.of("max-steps", 7, "max-loop", 8, "max-nest-depth", 9,
                            "assert-timeout-sec", 11, "screenshot-mode", 2, "fail-policy", 1),
                    limits, "六键=配置现值同源（键名=执行器 from_payload 消费口径，无 ai.script. 前缀）");

            setScriptKey("ai.script.max-steps", "42");
            long caseId2 = createApprovedCase(pid);
            execTaskService.create(new ExecTaskCreateInput(pid, caseId2, "v2", ACTION_JSON, envId));
            long exId2 = registerExecutor("ex-svc-limits2", 1);
            ClaimTaskView view2 = execTaskService.claim(exId2);
            assertNotNull(view2, "第二任务必须领取（独立执行器容量）");
            assertEquals(42, view2.scriptLimits().get("max-steps"), "热更下一次领取即生效");
            assertEquals(8, view2.scriptLimits().get("max-loop"), "其余键不受影响");
        } finally {
            setScriptKey("ai.script.max-steps", "50");
            setScriptKey("ai.script.max-loop", "20");
            setScriptKey("ai.script.max-nest-depth", "5");
            setScriptKey("ai.script.assert-timeout-sec", "10");
            setScriptKey("ai.script.screenshot-mode", "3");
            setScriptKey("ai.script.fail-policy", "1");
        }
    }


 
    private void setK8Key(String key, String value, String type) {
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_sys_config WHERE config_key = ?", Long.class, key);
        assertNotNull(id, "V71 种子键必须存在: " + key);
        configService.update(1L, id, new ConfigInput(key, value, "系统", type, 0, "测试期临时配置值"));
    }

    @Test
    void claim_下发execPolicy七键_与配置现值同源() {
        long pid = createProject();
        long envId = createTestEnv(pid);
        long caseId = createApprovedCase(pid);
        execTaskService.create(new ExecTaskCreateInput(pid, caseId, "v2", ACTION_JSON, envId));
        long exId = registerExecutor("ex-svc-policy", 1);

        setK8Key("exec.http.allow-hosts", "a.example.com,*.b.example.com", "string");
        setK8Key("exec.http.timeout-seconds", "20", "int");
        setK8Key("exec.net-assert.enabled", "0", "int");
        setK8Key("sql.max-rows", "500", "int");
        try {
            ClaimTaskView view = execTaskService.claim(exId);
            assertNotNull(view, "必须领取到任务");
            Map<String, Object> policy = view.execPolicy();
            assertNotNull(policy, "claim 必须下发 execPolicy（03 §2.3 尾追）");
            assertEquals("a.example.com,*.b.example.com", policy.get("allowHosts"),
                    "allow-hosts=配置现值原样传（csv 拆分/小写归一在执行器闸内，禁两处解析漂移）");
            assertEquals(20, policy.get("timeoutSeconds"), "timeout-seconds=配置现值");
            assertEquals(0, policy.get("netAssertEnabled"), "net-assert.enabled=配置现值");
            assertNotNull(policy.get("denyHosts"), "denyHosts 键必须在位（同源黑名单，可为空串）");
            assertEquals(30, policy.get("sqlAssertTimeoutSeconds"),
                    "sqlAssertTimeoutSeconds=V71 种子现读（T191a）");
            assertEquals(2000, policy.get("sqlAssertIntervalMs"),
                    "sqlAssertIntervalMs=V71 种子现读（T191a）");
            assertEquals(500, policy.get("sqlMaxRows"),
                    "sqlMaxRows=sql.max-rows 配置现读（T191b，sql.query 截断防护）");
        } finally {
            setK8Key("exec.http.allow-hosts", "", "string");
            setK8Key("exec.http.timeout-seconds", "10", "int");
            setK8Key("exec.net-assert.enabled", "1", "int");
            setK8Key("sql.max-rows", "1000", "int");
        }
    }


    @Test
    void claim_下发consoleAssertMode_与配置现值同源() {
        long pid = createProject();
        long envId = createTestEnv(pid);
        long caseId = createApprovedCase(pid);
        execTaskService.create(new ExecTaskCreateInput(pid, caseId, "v2", ACTION_JSON, envId));
        long exId = registerExecutor("ex-svc-console-mode", 1);

        setK8Key("exec.console-assert.mode", "warn", "string");
        try {
            ClaimTaskView view = execTaskService.claim(exId);
            assertNotNull(view, "必须领取到任务");
            assertEquals("warn", view.execPolicy().get("consoleAssertMode"),
                    "consoleAssertMode=exec.console-assert.mode 配置现读（T192 第八键）");
        } finally {
            setK8Key("exec.console-assert.mode", "fail", "string");
        }
    }

    @Test
    void submitResult_尾追recordEvents_聚合落result_ext含键() {
        long taskId = createTask();
        long exId = registerExecutor("ex-svc-recevents", 2);
        ClaimTaskView view = execTaskService.claim(exId);

        execTaskService.submitResult(new ExecResultInput(
                taskId, view.claimToken(), "pass", null, null,
                "[]", "[]", 100, null,
                null, null, null, null, null, null, null, null,
                Map.of("level", "stream",
                        "events", List.of(Map.of("t", 1000, "action", "tap", "x", 540, "y", 1200)),
                        "dropped", 0)));

        String ext = jdbcTemplate.queryForObject(
                "SELECT result_ext::text FROM tp_exec_task WHERE id = ?",
                String.class, taskId);
        assertNotNull(ext, "recordEvents 回传必须落 result_ext jsonb");
        var node = parseJsonArray(ext);
        assertEquals("stream", node.get("recordEvents").get("level").asText());
        assertEquals("tap",
                node.get("recordEvents").get("events").get(0).get("action").asText());
        assertEquals(0, node.get("recordEvents").get("dropped").asInt());
    }

    @Test
    void submitResult_recordEvents_四键全空_result_ext为NULL_零污染() {
        long taskId = createTask();
        long exId = registerExecutor("ex-svc-recevents-none", 2);
        ClaimTaskView view = execTaskService.claim(exId);

        execTaskService.submitResult(new ExecResultInput(
                taskId, view.claimToken(), "pass", null, null,
                "[]", "[]", 100, null,
                null, null, null, null, null, null, null, null));

        Object ext = jdbcTemplate.queryForObject(
                "SELECT result_ext FROM tp_exec_task WHERE id = ?", Object.class, taskId);
        assertNull(ext, "recordEvents 空回传=列 NULL（禁写空对象污染）");

        long t2 = createTask();
        long ex2 = registerExecutor("ex-svc-recevents-read", 2);
        ClaimTaskView v2 = execTaskService.claim(ex2);
        execTaskService.submitResult(new ExecResultInput(
                t2, v2.claimToken(), "pass", null, null,
                "[]", "[]", 100, null,
                null, null, null, null, null, null, null, null,
                Map.of("level", "fallback", "events", List.of(), "dropped", 3)));
        ExecTaskInfo detail = execTaskService.get(t2);
        assertNotNull(detail.resultExt(), "详情必须带 recordEvents（录制载入读路径）");
        var node = parseJsonArray(detail.resultExt());
        assertEquals("fallback", node.get("recordEvents").get("level").asText());
        assertEquals(3, node.get("recordEvents").get("dropped").asInt());
    }

    @Test
    void submitResult_尾追mockNotes_聚合落result_ext含键() {
        // Result trailing key mockNotes: the executor-side route-interception audit
        // bucket {path,mode,status?} is aggregated verbatim into result_ext, so the
        // batch report can badge "this case passed under N mocks". Value shaping and
        // trimming live only in the executor; core never re-implements them.
        long taskId = createTask();
        long exId = registerExecutor("ex-svc-mocknotes", 2);
        ClaimTaskView view = execTaskService.claim(exId);

        execTaskService.submitResult(new ExecResultInput(
                taskId, view.claimToken(), "pass", null, null,
                "[]", "[]", 100, null,
                null, null, null, null, null, null, null, null, null,
                List.of(Map.of("path", "/api/orders", "mode", "stub", "status", 201),
                        Map.of("path", "/api/users", "mode", "proxy"))));

        String ext = jdbcTemplate.queryForObject(
                "SELECT result_ext::text FROM tp_exec_task WHERE id = ?",
                String.class, taskId);
        assertNotNull(ext, "mockNotes 回传必须落 result_ext jsonb");
        var node = parseJsonArray(ext);
        assertEquals("/api/orders", node.get("mockNotes").get(0).get("path").asText());
        assertEquals("stub", node.get("mockNotes").get(0).get("mode").asText());
        assertEquals(201, node.get("mockNotes").get(0).get("status").asInt());
        assertEquals("proxy", node.get("mockNotes").get(1).get("mode").asText());

        // Read path: task detail exposes resultExt with mockNotes visible, which is
        // the data source the batch report badge reads.
        ExecTaskInfo detail = execTaskService.get(taskId);
        assertNotNull(detail.resultExt());
        assertEquals(2, parseJsonArray(detail.resultExt()).get("mockNotes").size());
    }

    @Test
    void submitResult_mockNotes_空回传_result_ext为NULL_零污染() {
        // Trailing-key compatibility precedent: the 18-arg constructor (the shape
        // before mockNotes) delegates without writing the key, so legacy executors
        // leave the column NULL and existing rows stay untouched.
        long taskId = createTask();
        long exId = registerExecutor("ex-svc-mocknotes-none", 2);
        ClaimTaskView view = execTaskService.claim(exId);

        execTaskService.submitResult(new ExecResultInput(
                taskId, view.claimToken(), "pass", null, null,
                "[]", "[]", 100, null,
                null, null, null, null, null, null, null, null, null));

        Object ext = jdbcTemplate.queryForObject(
                "SELECT result_ext FROM tp_exec_task WHERE id = ?", Object.class, taskId);
        assertNull(ext, "mockNotes 空回传=列 NULL（禁写空对象/空数组污染）");

        // Same discipline for an explicitly empty list: no key, no empty-array noise.
        long t2 = createTask();
        long ex2 = registerExecutor("ex-svc-mocknotes-emptylist", 2);
        ClaimTaskView v2 = execTaskService.claim(ex2);
        execTaskService.submitResult(new ExecResultInput(
                t2, v2.claimToken(), "pass", null, null,
                "[]", "[]", 100, null,
                null, null, null, null, null, null, null, null, null,
                java.util.List.of()));
        Object ext2 = jdbcTemplate.queryForObject(
                "SELECT result_ext FROM tp_exec_task WHERE id = ?", Object.class, t2);
        assertNull(ext2, "mockNotes=空 list 同样不写键（isEmptyEvidence 同族闸）");
    }

    @Test
    void submitResult_尾追mobilePerfSummary_聚合落result_ext含键() {
        // Result trailing key mobilePerfSummary (03 §2.4, W5 phase two): the executor's
        // only aggregation point driver/mobile/perf_session.summarize_metrics emits the
        // in-window rollup {metrics:{<name>:{median,count}}, sampleCount}. core stores
        // those numbers verbatim into result_ext and never re-parses the raw sample
        // rows, because a second extraction source would drift from the executor one.
        long taskId = createTask();
        long exId = registerExecutor("ex-svc-mobileperf", 2);
        ClaimTaskView view = execTaskService.claim(exId);

        Map<String, Object> summary = Map.of(
                "metrics", Map.of("jankPct", Map.of("median", 4.5, "count", 12),
                        "pssMb", Map.of("median", 128.0, "count", 10)),
                "sampleCount", 12);
        execTaskService.submitResult(new ExecResultInput(
                taskId, view.claimToken(), "pass", null, null,
                "[]", "[]", 100, null,
                null, null, null, null, null, null, null, null, null, null, summary));

        String ext = jdbcTemplate.queryForObject(
                "SELECT result_ext::text FROM tp_exec_task WHERE id = ?",
                String.class, taskId);
        assertNotNull(ext, "mobilePerfSummary 回传必须落 result_ext jsonb");
        var node = parseJsonArray(ext);
        assertEquals(4.5, node.get("mobilePerfSummary").get("metrics")
                .get("jankPct").get("median").asDouble(), 0.001);
        assertEquals(12, node.get("mobilePerfSummary").get("metrics")
                .get("jankPct").get("count").asInt());
        assertEquals(12, node.get("mobilePerfSummary").get("sampleCount").asInt());

        // Read path: the batch report performance column reads the same numbers back
        // through the task detail view instead of recomputing them.
        ExecTaskInfo detail = execTaskService.get(taskId);
        assertNotNull(detail.resultExt());
        assertEquals(128.0, parseJsonArray(detail.resultExt()).get("mobilePerfSummary")
                .get("metrics").get("pssMb").get("median").asDouble(), 0.001);
    }

    @Test
    void submitResult_mobilePerfSummary_缺席或空_map_不写键() {
        // Trailing-key compatibility precedent: the 19-arg constructor (the shape before
        // mobilePerfSummary) delegates without the key, so a web or api task that never
        // sampled mobile metrics leaves result_ext exactly as it is today. When every
        // trailing key is empty the column stays NULL instead of carrying an empty object.
        long taskId = createTask();
        long exId = registerExecutor("ex-svc-mobileperf-none", 2);
        ClaimTaskView view = execTaskService.claim(exId);

        execTaskService.submitResult(new ExecResultInput(
                taskId, view.claimToken(), "pass", null, null,
                "[]", "[]", 100, null,
                null, null, null, null, null, null, null, null, null));

        Object ext = jdbcTemplate.queryForObject(
                "SELECT result_ext FROM tp_exec_task WHERE id = ?", Object.class, taskId);
        assertNull(ext, "mobilePerfSummary 缺席且其余尾追键全空=列 NULL（禁写空对象）");

        // An explicitly empty map is the same shape as an empty list: no key, no noise.
        long t2 = createTask();
        long ex2 = registerExecutor("ex-svc-mobileperf-empty", 2);
        ClaimTaskView v2 = execTaskService.claim(ex2);
        execTaskService.submitResult(new ExecResultInput(
                t2, v2.claimToken(), "pass", null, null,
                "[]", "[]", 100, null,
                null, null, null, null, null, null, null, null, null, null, Map.of()));
        Object ext2 = jdbcTemplate.queryForObject(
                "SELECT result_ext FROM tp_exec_task WHERE id = ?", Object.class, t2);
        assertNull(ext2, "mobilePerfSummary=空 map 同样不写键（isEmptyEvidence 同族闸）");
    }

    @Test
    void submitResult_证据三键_聚合落result_ext() {
        long taskId = createTask();
        long exId = registerExecutor("ex-svc-evidence", 2);
        ClaimTaskView view = execTaskService.claim(exId);

        execTaskService.submitResult(new ExecResultInput(
                taskId, view.claimToken(), "fail", 1, "断言不满足：console 命中",
                null, null, 900, null,
                java.util.List.of(java.util.Map.of("stepNo", 3, "level", "error",
                        "text", "Uncaught TypeError: orderTotal")),
                java.util.List.of(java.util.Map.of("url", "http://a/api",
                        "status", 200, "durationMs", 12)),
                java.util.List.of("step5: scalar=42")));

        String ext = jdbcTemplate.queryForObject(
                "SELECT result_ext::text FROM tp_exec_task WHERE id = ?",
                String.class, taskId);
        assertNotNull(ext, "证据三键必须落 result_ext jsonb");
        var node = parseJsonArray(ext);
        assertEquals("Uncaught TypeError: orderTotal",
                node.get("consoleViolations").get(0).get("text").asText());
        assertEquals(12, node.get("netHits").get(0).get("durationMs").asInt());
        assertEquals("step5: scalar=42", node.get("dbDigest").get(0).asText());
    }

    @Test
    void submitResult_无证据键_result_ext为NULL_存量兼容() {
        long taskId = createTask();
        long exId = registerExecutor("ex-svc-no-evidence", 2);
        ClaimTaskView view = execTaskService.claim(exId);

        execTaskService.submitResult(new ExecResultInput(
                taskId, view.claimToken(), "pass", null, null,
                "[]", "[]", 100, null));

        Object ext = jdbcTemplate.queryForObject(
                "SELECT result_ext FROM tp_exec_task WHERE id = ?", Object.class, taskId);
        assertNull(ext, "无证据回传=列 NULL（禁写空对象污染）");
    }

    @Test
    void get_尾追resultExt_证据三键读路径可见() {
        long taskId = createTask();
        long exId = registerExecutor("ex-svc-readleg", 2);
        ClaimTaskView view = execTaskService.claim(exId);
        execTaskService.submitResult(new ExecResultInput(
                taskId, view.claimToken(), "fail", 1, "断言不满足：console 命中",
                null, null, 900, null,
                java.util.List.of(java.util.Map.of("stepNo", 3, "level", "error",
                        "text", "Uncaught TypeError: orderTotal")),
                java.util.List.of(java.util.Map.of("url", "http://a/api",
                        "status", 200, "durationMs", 12)),
                java.util.List.of("step5: scalar=42")));

        ExecTaskInfo detail = execTaskService.get(taskId);
        assertNotNull(detail.resultExt(), "详情必须带 resultExt（证据三键读路径）");
        var node = parseJsonArray(detail.resultExt());
        assertEquals("Uncaught TypeError: orderTotal",
                node.get("consoleViolations").get(0).get("text").asText());
        assertEquals(200, node.get("netHits").get(0).get("status").asInt());
        assertEquals("step5: scalar=42", node.get("dbDigest").get(0).asText());

        long plain = createTask();
        assertNull(execTaskService.get(plain).resultExt(), "无证据任务 resultExt 必须为 null");
    }


    @Test
    void claim_下发connections_解密明文含键名七件套() {
        Assumptions.assumeTrue(configCipher.isEnabled(),
                "密文解密钉需 CONFIG_AES_KEY（.env 注入）");
        long pid = createProject();
        long envId = createTestEnv(pid);
        jdbcTemplate.update("""
                INSERT INTO tp_db_connection (project_id, env_id, name, dialect, jdbc_url,
                        username, password_enc, readonly, max_pool, status)
                VALUES (?, ?, 'DB_SIT', 'pg', 'jdbc:postgresql://127.0.0.1:15432/demo',
                        'demo_user', ?, true, 2, 1)
                """, pid, envId, configCipher.encrypt("p@ss!db"));
        jdbcTemplate.update("""
                INSERT INTO tp_db_connection (project_id, env_id, name, dialect, jdbc_url,
                        username, password_enc, readonly, max_pool, status)
                VALUES (?, ?, 'DB_OFF', 'pg', 'jdbc:postgresql://127.0.0.1:15432/off',
                        'off_user', ?, true, 2, 0)
                """, pid, envId, configCipher.encrypt("off-pass"));
        long caseId = createApprovedCase(pid);
        execTaskService.create(new ExecTaskCreateInput(pid, caseId, "v2", ACTION_JSON, envId));
        long exId = registerExecutor("ex-svc-conn", 2);

        ClaimTaskView view = execTaskService.claim(exId);

        assertNotNull(view);
        assertNotNull(view.connections(), "connections 键必须在位（空=空 list 非 null）");
        assertEquals(1, view.connections().size(), "status=1 启用连接才下发（停用拒下发）");
        Map<String, Object> conn = view.connections().get(0);
        assertEquals("DB_SIT", conn.get("name"));
        assertEquals("pg", conn.get("dialect"));
        assertEquals("jdbc:postgresql://127.0.0.1:15432/demo", conn.get("url"));
        assertEquals("demo_user", conn.get("user"));
        assertEquals("p@ss!db", conn.get("password"), "claim 下发=解密明文（执行器侧仅内存）");
        assertEquals(Boolean.TRUE, conn.get("readonly"));
        assertEquals(2, conn.get("maxPool"));
    }

    @Test
    void claim_环境无连接_connections空list() {
        long pid = createProject();
        long envId = createTestEnv(pid);
        long caseId = createApprovedCase(pid);
        execTaskService.create(new ExecTaskCreateInput(pid, caseId, "v2", ACTION_JSON, envId));
        long exId = registerExecutor("ex-svc-conn-none", 2);

        ClaimTaskView view = execTaskService.claim(exId);

        assertNotNull(view);
        assertNotNull(view.connections());
        assertTrue(view.connections().isEmpty(), "无连接=空 list（契约稳定，执行器人话拒连）");
    }
}
