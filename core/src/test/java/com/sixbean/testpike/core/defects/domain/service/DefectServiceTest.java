package com.sixbean.testpike.core.defects.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.support.CaseFixtureRig;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.defects.api.dto.DefectInfo;
import com.sixbean.testpike.core.defects.api.dto.DefectLinkInput;
import com.sixbean.testpike.core.defects.api.dto.DefectStatusInput;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.support.ProjectFixtureRig;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class DefectServiceTest {

    @Autowired
    private DefectService defectService;

    @Autowired
    private CaseService caseService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
 * R37 fixture-scoped teardown : the old blanket TRUNCATE
 * (even routed through the bounded-retry helper) wiped shipped projects,
 * every concurrent suite's cases, notifications and audit rows on the
 * shared dev database. Everything this suite seeds hangs off its own
 * fixture projects - tp_exec_task through the project-keyed default sweep,
 * tp_script/tp_case_defect/tp_case_step through the FK walk, audit and
 * notification rows through project_id - so the tracked-project sweep
 * covers the whole footprint with no global wipe.
 */
    private final List<Long> trackedProjects = new ArrayList<>();

    @BeforeEach
    @AfterEach
    void cleanFixtureRows() {
        ProjectFixtureRig.sweep(jdbcTemplate, trackedProjects);
    }

    private long createEnv(long projectId, int envType, String baseUrl) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO tp_project_env (project_id, name, env_type, base_url, version_snapshot)
                VALUES (?, ?, ?, ?, 'v1.0.0') RETURNING id
                """, Long.class, projectId, "env-" + envType + "-" + baseUrl, envType, baseUrl);
    }

    
    private void seedScript(long caseId, int status) {
        jdbcTemplate.update("""
                INSERT INTO tp_script (case_id, schema_version, action_json, status, generated_by, version)
                VALUES (?, 'v2', '{"steps":[{"action":"goto","url":"http://tp-local.test/login"}]}'::jsonb, ?, 1, 1)
                """, caseId, status);
    }

    private long countTasks(long caseId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_exec_task WHERE case_id = ?", Long.class, caseId);
        return n == null ? 0 : n;
    }

    private long createProject(String code) {
        return ProjectFixtureRig.createSelfHealByCode(jdbcTemplate, projectService, 1L, trackedProjects, code);
    }

    private long createCase(long projectId, String title) {
        return CaseFixtureRig.create(caseService, 1L, projectId, title);
    }

    /** Counts only this run's rows: idFloor = tp_notification watermark taken
     *  before the action under test (shared dev DB, no unscoped counts). */
    private long countNotify(String eventType, long idFloor) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_notification WHERE event_type = ? AND id > ?",
                Long.class, eventType, idFloor);
        return n == null ? 0 : n;
    }

    @Test
    void test_link_and_status_backfill_and_close_notify() {
        long pid = createProject("D1");
        long caseId = createCase(pid, "下单优惠券校验");
        long notifyFloor = SharedDbFixtureSweep.maxId(jdbcTemplate, "tp_notification");

        DefectInfo linked = defectService.link(1L, new DefectLinkInput(caseId, "JIRA-233", "open"));
        assertNotNull(linked);
        assertEquals(caseId, linked.caseId());
        assertEquals("JIRA-233", linked.defectKey());
        assertEquals("open", linked.defectStatus());
        assertEquals(1, linked.status(), "初始闭环状态应为开启");
        assertEquals(1, linked.createdBy());
        assertEquals(1, linked.version(), "新关联乐观锁版本应=1");

        DefectInfo progress = defectService.updateStatus(1L, linked.id(), new DefectStatusInput("in_progress", 1));
        assertEquals("in_progress", progress.defectStatus());
        assertEquals(1, progress.status(), "in_progress 不触发关闭闭环");
        assertEquals(2, progress.version(), "回填后乐观锁版本应递增=2");

        DefectInfo closed = defectService.updateStatus(1L, linked.id(), new DefectStatusInput("closed", 2));
        assertEquals("closed", closed.defectStatus());
        assertEquals(2, closed.status(), "closed 应关闭闭环");
        assertEquals(1, countNotify("DEFECT_CLOSED", notifyFloor), "关闭后应产生 DEFECT_CLOSED 站内信");
        String dedup = jdbcTemplate.queryForObject(
                "SELECT dedup_key FROM tp_notification WHERE event_type = 'DEFECT_CLOSED' AND id > ?",
                String.class, notifyFloor);
        assertNotNull(dedup);
        assertTrue(dedup.contains("JIRA-233"), "dedup_key 应含 defectKey：" + dedup);
    }

    @Test
    void test_invalid_defect_key_case_and_status_400() {
        long pid = createProject("D2");
        long caseId = createCase(pid, "登录态校验");

        ServiceException e1 = assertThrows(ServiceException.class,
                () -> defectService.link(1L, new DefectLinkInput(caseId, "  ", "open")));
        assertTrue(e1.getMessage().contains("缺陷 key"), "空 key 提示人话：" + e1.getMessage());

        ServiceException e2 = assertThrows(ServiceException.class,
                () -> defectService.link(1L, new DefectLinkInput(999999L, "JIRA-999", "open")));
        assertTrue(e2.getMessage().contains("用例不存在"), "不存在的用例提示人话：" + e2.getMessage());

        DefectInfo linked = defectService.link(1L, new DefectLinkInput(caseId, "JIRA-234", "open"));
        ServiceException e3 = assertThrows(ServiceException.class,
                () -> defectService.updateStatus(1L, linked.id(), new DefectStatusInput("invalid", 1)));
        assertTrue(e3.getMessage().contains("open"), "非法状态提示合法值：" + e3.getMessage());
    }

    @Test
    void test_close_notify_dedup_merge() {
        long pid = createProject("D3");
        long caseId = createCase(pid, "支付回调校验");
        long notifyFloor = SharedDbFixtureSweep.maxId(jdbcTemplate, "tp_notification");

        DefectInfo linked = defectService.link(1L, new DefectLinkInput(caseId, "JIRA-300", "open"));
        defectService.updateStatus(1L, linked.id(), new DefectStatusInput("closed", 1));
        defectService.updateStatus(1L, linked.id(), new DefectStatusInput("closed", 2));
        assertEquals(1, countNotify("DEFECT_CLOSED", notifyFloor), "同 dedup_key 重跑提示应合并为一条");

        List<DefectInfo> list = defectService.listByCase(1L, caseId);
        assertEquals(1, list.size());
        assertEquals("closed", list.get(0).defectStatus());
        assertEquals(2, list.get(0).status());
    }

    @Test
    void test_get_by_id() {
        long pid = createProject("D5");
        long caseId = createCase(pid, "getById 校验");
        DefectInfo linked = defectService.link(1L, new DefectLinkInput(caseId, "JIRA-501", "open"));
        DefectInfo got = defectService.get(linked.id());
        assertNotNull(got);
        assertEquals(linked.id(), got.id());
        assertEquals(caseId, got.caseId());
        assertEquals("JIRA-501", got.defectKey());
        assertEquals("open", got.defectStatus());

        ServiceException notFound = assertThrows(ServiceException.class,
                () -> defectService.get(999999L));
        assertEquals(40401, notFound.getCode(), "不存在缺陷关联记录 40401：" + notFound.getMessage());
    }

    @Test
    void test_link_idempotent() {
        long pid = createProject("D4");
        long caseId = createCase(pid, "幂等校验");
        DefectInfo first = defectService.link(1L, new DefectLinkInput(caseId, "JIRA-400", "open"));
        DefectInfo second = defectService.link(1L, new DefectLinkInput(caseId, "JIRA-400", "open"));
        assertEquals(first.id(), second.id(), "重复关联应返回同一记录");
        Long cnt = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_case_defect WHERE case_id = ?", Long.class, caseId);
        assertEquals(1L, cnt);
    }

    @Test
    void test_rerun_hint_moves_exec_failed_to_running() {
        long pid = createProject("D6");
        long caseId = createCase(pid, "重跑状态流转");
        createEnv(pid, 1, "http://tp-local.test");
        seedScript(caseId, 1);
        long auditFloor = SharedDbFixtureSweep.maxId(jdbcTemplate, "tp_audit_log");
        jdbcTemplate.update("UPDATE tp_test_case SET status = 7, version = version + 1 WHERE id = ?", caseId);

        long result = defectService.rerunHint(1L, caseId);

        assertEquals(caseId, result, "重跑返回用例 id");
        Integer status = jdbcTemplate.queryForObject(
                "SELECT status FROM tp_test_case WHERE id = ?", Integer.class, caseId);
        assertEquals(6, status, "重跑后用例应进入执行中(6)（PRD 4.5.1 执行任务开始）");
        Long audit = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_audit_log WHERE object_type = 'case_defect' AND action = 'defect_rerun'"
                        + " AND id > ?",
                Long.class, auditFloor);
        assertEquals(1L, audit, "重跑动作应登记审计");
        assertEquals(1L, countTasks(caseId), "重跑应创建 1 条执行任务（否则永卡执行中）");
        java.util.Map<String, Object> task = jdbcTemplate.queryForMap(
                "SELECT status, project_id, env_base_url, action_json FROM tp_exec_task WHERE case_id = ?", caseId);
        assertEquals(1, task.get("status"), "新任务应待派发(1)");
        assertEquals(pid, ((Number) task.get("project_id")).longValue());
        assertEquals("http://tp-local.test", task.get("env_base_url"), "环境快照应取项目环境 base_url");
        assertNotNull(task.get("action_json"), "任务应携带脚本 action_json 快照");
    }

    @Test
    void test_rerun_hint_falls_back_to_any_env_when_no_dev() {
        long pid = createProject("D7");
        long caseId = createCase(pid, "重跑环境回退");
        createEnv(pid, 3, "http://tp-stage.test");
        createEnv(pid, 2, "http://tp-test.test");
        seedScript(caseId, 1);
        jdbcTemplate.update("UPDATE tp_test_case SET status = 8, version = version + 1 WHERE id = ?", caseId);

        defectService.rerunHint(1L, caseId);

        assertEquals(1L, countTasks(caseId), "回归失败(8)重跑同样应建任务");
        String baseUrl = jdbcTemplate.queryForObject(
                "SELECT env_base_url FROM tp_exec_task WHERE case_id = ?", String.class, caseId);
        assertEquals("http://tp-test.test", baseUrl, "无开发环境应回退 env_type 最小（测试）");
    }

    @Test
    void test_rerun_hint_no_script_400() {
        long pid = createProject("D8");
        long caseId = createCase(pid, "重跑缺脚本");
        createEnv(pid, 1, "http://tp-local.test");
        jdbcTemplate.update("UPDATE tp_test_case SET status = 7, version = version + 1 WHERE id = ?", caseId);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> defectService.rerunHint(1L, caseId));
        assertTrue(ex.getMessage().contains("请先生成脚本"), "无脚本重跑提示人话：" + ex.getMessage());
        assertEquals(0L, countTasks(caseId), "400 拒绝时不应建任务");
        Integer status = jdbcTemplate.queryForObject(
                "SELECT status FROM tp_test_case WHERE id = ?", Integer.class, caseId);
        assertEquals(7, status, "400 拒绝时用例状态不应被置 6（否则永卡）");
    }

    @Test
    void test_rerun_hint_no_env_400() {
        long pid = createProject("D9");
        long caseId = createCase(pid, "重跑缺环境");
        seedScript(caseId, 1);
        jdbcTemplate.update("UPDATE tp_test_case SET status = 7, version = version + 1 WHERE id = ?", caseId);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> defectService.rerunHint(1L, caseId));
        assertTrue(ex.getMessage().contains("环境"), "无环境重跑提示人话：" + ex.getMessage());
        assertEquals(0L, countTasks(caseId), "400 拒绝时不应建任务");
    }

    @Test
    void test_rerun_hint_script_pending_update_400() {
        long pid = createProject("D10");
        long caseId = createCase(pid, "重跑脚本待更新");
        createEnv(pid, 1, "http://tp-local.test");
        seedScript(caseId, 2);
        jdbcTemplate.update("UPDATE tp_test_case SET status = 7, version = version + 1 WHERE id = ?", caseId);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> defectService.rerunHint(1L, caseId));
        assertTrue(ex.getMessage().contains("重新生成"), "待更新脚本重跑提示人话：" + ex.getMessage());
        assertEquals(0L, countTasks(caseId), "拒绝时不应建任务");
    }

    @Test
    void test_rerun_hint_illegal_case_400() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> defectService.rerunHint(1L, 999999L));
        assertTrue(ex.getMessage().contains("用例不存在"), "重跑不存在用例提示人话：" + ex.getMessage());
    }
}
