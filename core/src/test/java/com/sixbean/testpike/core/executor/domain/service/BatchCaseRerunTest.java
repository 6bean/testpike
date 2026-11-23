package com.sixbean.testpike.core.executor.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.executor.api.dto.BatchCaseRerunResult;
import com.sixbean.testpike.core.executor.api.dto.ClaimTaskView;
import com.sixbean.testpike.core.executor.api.dto.ExecResultInput;
import com.sixbean.testpike.core.executor.api.dto.ExecTaskCreateInput;
import com.sixbean.testpike.core.executor.api.dto.ExecutorRegisterInput;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.api.dto.ProjectEnvInput;
import com.sixbean.testpike.core.project.domain.service.ProjectEnvService;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
@TestPropertySource(properties = "testpike.prod-blacklist=*.example.com")
class BatchCaseRerunTest {

    private static final String ACTION_JSON =
            "{\"version\":\"v2\",\"steps\":[{\"action\":\"goto\",\"target\":{\"url\":\"{{ENV:URL}}/login\"}}]}";

    @Autowired
    private ExecTaskService execTaskService;

    @Autowired
    private ExecutorService executorService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private CaseService caseService;

    @Autowired
    private ProjectEnvService projectEnvService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
 * R37 fixture-scoped teardown : every seeded row hangs off
 * the suite's fixed-name fixture projects - exec tasks/batches/plans through
 * the project-keyed default sweep, steps through the FK walk, plan detail
 * refs through the join purge below (tp_plan_case carries no FK) - and the
 * executors drop by their exact names. The old blanket TRUNCATE wiped
 * shipped projects and concurrent suites' rows on the shared dev database.
 */
    private static final List<String> FIXTURE_PROJECT_NAMES = List.of("重跑项目");

    @BeforeEach
    @AfterEach
    void cleanFixtureRows() {
        List<Long> family = SharedDbFixtureSweep.projectIdsByNamePrefixes(
                jdbcTemplate, FIXTURE_PROJECT_NAMES);
        SharedDbFixtureSweep.childRowsOfProjects(jdbcTemplate, "tp_plan_case", "plan_id",
                "tp_exec_plan", family);
        SharedDbFixtureSweep.byProjectList(jdbcTemplate, family);
        jdbcTemplate.update("DELETE FROM tp_executor WHERE name IN ('ex-rerun', 'ex-rerun2', 'ex-running')");
    }

    
    private RerunFixture fixture() {
        long pid = projectService.create(1L, new ProjectCreateInput("重跑项目",
                "RR" + System.nanoTime() % 100000, "zh-CN", "P1", null));
        long envId = projectEnvService.create(pid, new ProjectEnvInput(
                "测试环境", 2, "http://demo.local", null, "web", "chromium", "v1.0.0"));
        CaseInfo info = caseService.create(1L, new CaseCreateInput(pid, "重跑用例",
                "前置", "预期", 1, 2, "web", 3, null, 1, null, List.of()));
        // H4 pool isolation : live dev-slot executor runners poll
        // claims against the shared dev database, and the retired blanket TRUNCATE
        // of tp_executor used to sideline them by accident. The pool gate is the
        // product mechanism for the same effect: the batch pins a project-owned
        // resource-pool tag (tp_tag_dict type=7) and only the fixture executors
        // registered as pool members can claim its tasks — foreign runners skip
        // the candidate at queue level (deterministic, no row outside this
        // fixture's reach is ever touched).
        long poolTagId = jdbcTemplate.queryForObject("""
                INSERT INTO tp_tag_dict (project_id, name, type) VALUES (?, '重跑资源池', 7) RETURNING id
                """, Long.class, pid);
        long batchId = jdbcTemplate.queryForObject("""
                INSERT INTO tp_exec_batch (project_id, env_id, batch_no, name, status, type,
                                           schedule_type, pool_tag_id)
                VALUES (?, ?, 'B-RERUN', '重跑批次', 4, 1, 0, ?) RETURNING id
                """, Long.class, pid, envId, poolTagId);
        long taskId = execTaskService.create(new ExecTaskCreateInput(
                pid, info.id(), "v2", ACTION_JSON, envId, batchId));
        return new RerunFixture(pid, envId, info.id(), batchId, taskId, poolTagId);
    }

    private record RerunFixture(long projectId, long envId, long caseId, long batchId,
                                long taskId, long poolTagId) {
    }

    
    private void runToFailed(RerunFixture f) {
        long exId = executorService.register(new ExecutorRegisterInput(
                "ex-rerun", String.valueOf(f.poolTagId()), null, "web", "chromium", 2, "1.0.0", 0));
        ClaimTaskView view = execTaskService.claim(exId);
        assertNotNull(view, "pool-member executor must claim its own pending task");
        assertEquals(f.taskId(), view.taskId());
        execTaskService.submitResult(new ExecResultInput(f.taskId(), view.claimToken(), "fail",
                1, "元素未找到", null, null, 1200, null));
        Integer caseStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM tp_test_case WHERE id = ?", Integer.class, f.caseId());
        assertEquals(7, caseStatus, "失败回填：用例进入执行失败(7)");
    }

    @Test
    void rerun_失败批次明细_新建同批次任务且明细指向新任务() {
        RerunFixture f = fixture();
        runToFailed(f);

        BatchCaseRerunResult result = execTaskService.rerunBatchCase(f.taskId(), null, null);

        assertTrue(result.created(), "首次重跑必须新建任务");
        assertNotNull(result.taskId());
        assertTrue(result.taskId() > f.taskId(), "新任务 id 递增");
        Map<String, Object> task = jdbcTemplate.queryForMap(
                "SELECT project_id, case_id, batch_id, status, schema_version, action_json, env_base_url " +
                        "FROM tp_exec_task WHERE id = ?", result.taskId());
        assertEquals(f.projectId(), ((Number) task.get("project_id")).longValue());
        assertEquals(f.caseId(), ((Number) task.get("case_id")).longValue());
        assertEquals(f.batchId(), ((Number) task.get("batch_id")).longValue(), "重跑任务挂同一批次");
        assertEquals(1, ((Number) task.get("status")).intValue(), "新任务待派发");
        assertEquals("v2", task.get("schema_version"));
        assertEquals(ACTION_JSON, task.get("action_json"), "复用源任务脚本快照");
        assertEquals("http://demo.local", task.get("env_base_url"), "环境从批次 env_id 解析（复用 create 链路）");
        Integer caseStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM tp_test_case WHERE id = ?", Integer.class, f.caseId());
        assertEquals(6, caseStatus, "重跑开始：执行失败(7)→执行中(6)");
    }

    @Test
    void rerun_幂等_重复点击返回既有未完成任务不重建() {
        RerunFixture f = fixture();
        runToFailed(f);

        BatchCaseRerunResult first = execTaskService.rerunBatchCase(f.taskId(), null, null);
        BatchCaseRerunResult again = execTaskService.rerunBatchCase(f.taskId(), null, null);

        assertEquals(first.taskId(), again.taskId(), "IT-O9-01：重复重跑返回既有任务");
        assertEquals(false, again.created(), "幂等：不重复创建");
        Integer cnt = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tp_exec_task WHERE batch_id = ? AND case_id = ?",
                Integer.class, f.batchId(), f.caseId());
        assertEquals(2, cnt, "库中仅源任务 + 一个重跑任务");
    }

    @Test
    void rerun_执行中禁止重跑_409人话() {
        RerunFixture f = fixture();
        long exId = executorService.register(new ExecutorRegisterInput(
                "ex-running", String.valueOf(f.poolTagId()), null, "web", "chromium", 2, "1.0.0", 0));
        execTaskService.claim(exId);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> execTaskService.rerunBatchCase(f.taskId(), null, null));
        assertEquals(BizErrorCode.STATE_MACHINE_REJECT, ex.getCode());
        assertTrue(ex.getMessage().contains("执行中"), "人话提示含执行中：" + ex.getMessage());
    }

    @Test
    void rerun_任务不存在_404人话() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> execTaskService.rerunBatchCase(999999999L, null, null));
        assertEquals(BizErrorCode.NOT_FOUND, ex.getCode());
        assertTrue(ex.getMessage().contains("不存在"));
    }

    @Test
    void rerun_源任务无脚本快照_400人话() {
        RerunFixture f = fixture();
        runToFailed(f);
        jdbcTemplate.update("UPDATE tp_exec_task SET action_json = NULL WHERE id = ?", f.taskId());

        ServiceException ex = assertThrows(ServiceException.class,
                () -> execTaskService.rerunBatchCase(f.taskId(), null, null));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("脚本"), "人话含脚本提示：" + ex.getMessage());
    }

    @Test
    void rerun_计划批次明细_重跑后batch_case_ref指向新任务() {
        RerunFixture f = fixture();
        long planId = jdbcTemplate.queryForObject("""
                INSERT INTO tp_exec_plan (project_id, name, status, created_by, lock_mode)
                VALUES (?, '重跑计划', 3, 1, 0) RETURNING id
                """, Long.class, f.projectId());
        jdbcTemplate.update("""
                INSERT INTO tp_plan_case (plan_id, case_id, status, batch_case_ref)
                VALUES (?, ?, 4, ?)
                """, planId, f.caseId(), f.taskId());
        runToFailed(f);

        BatchCaseRerunResult result = execTaskService.rerunBatchCase(f.taskId(), null, null);

        Long ref = jdbcTemplate.queryForObject(
                "SELECT batch_case_ref FROM tp_plan_case WHERE plan_id = ? AND case_id = ?",
                Long.class, planId, f.caseId());
        assertEquals(result.taskId(), ref, "计划明细指向重跑新任务（进度随新结果回填）");
    }

    @Test
    void rerun_结果回填新任务_用例恢复已通过() {
        RerunFixture f = fixture();
        runToFailed(f);
        BatchCaseRerunResult result = execTaskService.rerunBatchCase(f.taskId(), null, null);

        ClaimTaskView view = execTaskService.claim(executorService.register(
                new ExecutorRegisterInput("ex-rerun2", String.valueOf(f.poolTagId()), null,
                        "web", "chromium", 2, "1.0.0", 0)));
        assertNotNull(view);
        assertEquals(result.taskId(), view.taskId());
        execTaskService.submitResult(new ExecResultInput(result.taskId(), view.claimToken(),
                "pass", null, null, null, null, 900, null));

        Integer caseStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM tp_test_case WHERE id = ?", Integer.class, f.caseId());
        assertEquals(3, caseStatus, "重跑通过：执行中(6)→已通过(3)");
    }
}
