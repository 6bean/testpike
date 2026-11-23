package com.sixbean.testpike.core.cases.domain.service;

import com.alibaba.excel.EasyExcel;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseExportRequest;
import com.sixbean.testpike.core.cases.api.dto.CaseExportResult;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.api.dto.CaseStepInput;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class CaseExportTest {

    @Autowired
    private CaseService caseService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private com.sixbean.testpike.core.rbac.domain.service.RoleService roleService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    
    private long operator;

    @BeforeEach
    @AfterEach
    void cleanTables() {
        // Fixture-scoped teardown : tp_tag_dict/tp_export_task rows this run
        // created are dropped by their project id, the project tree (cases, steps,
        // case_tag links, audit, notifications) unwinds via the shared sweep —
        // never a whole-table wipe of tables the concurrent suites share.
        jdbcTemplate.update("DELETE FROM tp_tag_dict WHERE project_id IN (SELECT id FROM tp_project"
                + " WHERE code LIKE 'exp%')");
        SharedDbFixtureSweep.byProjectList(jdbcTemplate, List.of("tp_export_task"), projectIds);
        jdbcTemplate.update("DELETE FROM tp_user_role WHERE user_id IN"
                + " (SELECT id FROM tp_user WHERE username IN ('exp_assignee', 'exp_scope_t', 'exp_operator'))");
        jdbcTemplate.update("DELETE FROM tp_user WHERE username IN ('exp_assignee', 'exp_scope_t', 'exp_operator')");
        seedBuiltinRoles();
        operator = createUser("exp_operator", "test_lead");
    }

    /** Project ids created by this run; drained by the sweep on every hook. */
    private final List<Long> projectIds = new java.util.concurrent.CopyOnWriteArrayList<>();

    
    private void seedBuiltinRoles() {
        jdbcTemplate.execute("""
                INSERT INTO tp_role (name, code, description, data_scope, status, builtin) VALUES
                    ('测试人员', 'tester', '执行测试用例、提交缺陷', 3, 1, 1),
                    ('测试负责人', 'test_lead', '管理测试计划、分配用例、评审', 2, 1, 1),
                    ('开发人员', 'developer', '查看用例与缺陷（只读）', 2, 1, 1),
                    ('系统管理员', 'admin', '系统配置、用户与角色管理', 1, 1, 1)
                ON CONFLICT (code) DO NOTHING
                """);
    }

    private long createUser(String username, String roleCode) {
        long id = jdbcTemplate.queryForObject("""
                INSERT INTO tp_user (username, password_hash, display_name, role, status)
                VALUES (?, 'x', ?, 1, 1) RETURNING id
                """, Long.class, username, username);
        Long roleId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_role WHERE code = ? LIMIT 1", Long.class, roleCode);
        roleService.assignUserRoles(id, List.of(roleId));
        return id;
    }

    private long createProject(String code) {
        long id = projectService.create(1L, new ProjectCreateInput("项目" + code, code, "zh-CN", "P1", null));
        projectIds.add(id);
        return id;
    }

    private CaseInfo createCase(long projectId, String title) {
        return caseService.create(1L, new CaseCreateInput(projectId, title, "前置", "预期",
                1, 2, "web", null, null, null, null,
                List.of(new CaseStepInput(1, "打开登录页", 0),
                        new CaseStepInput(2, "输入账号密码 => 登录成功", 1))));
    }

    
    private List<Map<Integer, String>> readBack(byte[] content) {
        return EasyExcel.read(new ByteArrayInputStream(content)).headRowNumber(1).sheet().doReadSync();
    }

    private List<Map<Integer, String>> readBackWithHeader(byte[] content) {
        return EasyExcel.read(new ByteArrayInputStream(content)).headRowNumber(0).sheet().doReadSync();
    }


    @Test
    void export_勾选三条_xlsx含编号标题步骤标签状态列() {
        long pid = createProject("exp1");
        CaseInfo a = createCase(pid, "登录成功");
        CaseInfo b = createCase(pid, "登出正常");
        CaseInfo c = createCase(pid, "密码错误提示");
        long tagId = jdbcTemplate.queryForObject(
                "INSERT INTO tp_tag_dict (project_id, type, name, created_by) VALUES (?, 1, '冒烟', 1) RETURNING id",
                Long.class, pid);
        jdbcTemplate.update("INSERT INTO tp_case_tag (case_id, tag_id) VALUES (?, ?)", a.id(), tagId);
        long assigneeId = jdbcTemplate.queryForObject(
                "INSERT INTO tp_user (username, password_hash, display_name, role, status)"
                        + " VALUES ('exp_assignee', 'x', '导出测试员', 1, 1) RETURNING id", Long.class);
        jdbcTemplate.update("UPDATE tp_test_case SET assignee_id = ? WHERE id = ?", assigneeId, a.id());

        CaseExportResult result = caseService.export(operator,
                new CaseExportRequest(pid, List.of(a.id(), b.id(), c.id()), null, null,
                        null, null, null, null, null));

        assertFalse(result.async(), "3 条同步返回");
        assertEquals(3, result.total());
        assertEquals(0, result.removed());
        assertTrue(result.content().length > 4, "xlsx 字节（PK 魔数）");
        assertEquals('P', (char) result.content()[0]);
        assertEquals('K', (char) result.content()[1]);
        assertTrue(result.fileName().endsWith(".xlsx"));

        List<Map<Integer, String>> header = readBackWithHeader(result.content());
        assertEquals(List.of("测试目标", "前置条件", "预期结果", "优先级", "置信度", "终端类型", "步骤",
                "用例编号", "状态", "标签", "分配人"),
                header.get(0).values().stream().toList().subList(0, 11));

        List<Map<Integer, String>> rows = readBack(result.content());
        assertEquals(3, rows.size());
        Map<Integer, String> rowA = rows.stream()
                .filter(r -> "登录成功".equals(r.get(0))).findFirst().orElseThrow();
        assertEquals(a.caseNo(), rowA.get(7), "用例编号列");
        assertEquals("待评审", rowA.get(8), "状态列=中文 label");
        assertEquals("冒烟", rowA.get(9), "标签列");
        assertEquals("导出测试员", rowA.get(10), "分配人列=display_name");
        assertEquals("打开登录页; 输入账号密码 => 登录成功", rowA.get(6), "步骤列=「操作 => 预期」;分隔（模板口径）");

        Long auditCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_audit_log WHERE object_type = 'case' AND action = 'export'",
                Long.class);
        assertEquals(1L, auditCount, "导出审计一条");
        String auditAfter = jdbcTemplate.queryForObject(
                "SELECT after_json::text FROM tp_audit_log WHERE object_type='case' AND action='export' LIMIT 1",
                String.class);
        String compact = auditAfter.replace(" ", "");
        assertTrue(compact.contains("\"total\":3") && compact.contains("removed"),
                "审计 after 含导出范围 total/removed：" + auditAfter);
    }


    @Test
    void export_ids含已删除与他项目用例_静默剔除并count提示() {
        long pid = createProject("exp2");
        long otherPid = createProject("exp2b");
        CaseInfo a = createCase(pid, "可见用例");
        CaseInfo gone = createCase(pid, "将被删除");
        caseService.delete(1L, gone.id());
        CaseInfo foreign = createCase(otherPid, "他项目用例");

        CaseExportResult result = caseService.export(operator,
                new CaseExportRequest(pid, List.of(a.id(), gone.id(), foreign.id()), null, null,
                        null, null, null, null, null));

        assertEquals(1, result.total(), "仅可见活跃用例导出");
        assertEquals(2, result.removed(), "已删除 + 他项目各剔除 1 条");
        List<Map<Integer, String>> rows = readBack(result.content());
        assertEquals(1, rows.size());
        assertEquals("可见用例", rows.get(0).get(0));
    }

    @Test
    void export_ids与filter均空_400请选择导出范围() {
        long pid = createProject("exp3");
        ServiceException ex = assertThrows(ServiceException.class, () ->
                caseService.export(operator, new CaseExportRequest(pid, null, null, null,
                        null, null, null, null, null)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("请选择导出范围"), ex.getMessage());
    }

    @Test
    void export_ids与filter同给_400二选一() {
        long pid = createProject("exp4");
        CaseInfo a = createCase(pid, "任一条");
        ServiceException ex = assertThrows(ServiceException.class, () ->
                caseService.export(operator, new CaseExportRequest(pid, List.of(a.id()), 3, null,
                        null, null, null, null, null)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("二选一"), ex.getMessage());
    }

    @Test
    void export_ids超5000上限_400() {
        long pid = createProject("exp5");
        List<Long> ids = new ArrayList<>();
        for (long i = 1; i <= 5001; i++) {
            ids.add(i);
        }
        ServiceException ex = assertThrows(ServiceException.class, () ->
                caseService.export(operator, new CaseExportRequest(pid, ids, null, null,
                        null, null, null, null, null)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("5000"), ex.getMessage());
    }


    @Test
    void export_筛选导出_仅命中筛选条件且数据范围过滤() {
        long pid = createProject("exp6");
        createCase(pid, "已通过一");
        createCase(pid, "已通过二");
        jdbcTemplate.update("UPDATE tp_test_case SET status = 3 WHERE title LIKE '已通过%'");
        createCase(pid, "我的待评审");
        createCase(pid, "他人用例");
        jdbcTemplate.update("UPDATE tp_test_case SET created_by = 999 WHERE title = '他人用例'");

        CaseExportResult filtered = caseService.export(operator,
                new CaseExportRequest(pid, null, 3, null, null, null, null, null, null));
        assertEquals(2, filtered.total(), "筛选=状态已通过两条");
        List<Map<Integer, String>> rows = readBack(filtered.content());
        assertTrue(rows.stream().allMatch(r -> "已通过".equals(r.get(8))), "全部行状态=已通过");
        assertEquals(0, filtered.removed(), "筛选模式无剔除概念（查询即过滤）");

        CaseExportResult byKeyword = caseService.export(operator,
                new CaseExportRequest(pid, null, null, "他人", null, null, null, null, null));
        assertEquals(1, byKeyword.total(), "关键词命中一条");
        assertEquals("他人用例", readBack(byKeyword.content()).get(0).get(0));
    }

    @Test
    void export_数据范围本人_只导出本人创建或分配() {
        long pid = createProject("exp7");
        long tester = jdbcTemplate.queryForObject(
                "INSERT INTO tp_user (username, password_hash, display_name, role, status)"
                        + " VALUES ('exp_scope_t', 'x', '范围测试员', 1, 1) RETURNING id", Long.class);
        Long leadRoleId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_role WHERE code = 'tester' LIMIT 1", Long.class);
        if (leadRoleId != null) {
            jdbcTemplate.update("INSERT INTO tp_user_role (user_id, role_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                    tester, leadRoleId);
        }
        createCase(pid, "本人用例").id();
        jdbcTemplate.update("UPDATE tp_test_case SET created_by = ? WHERE title = '本人用例'", tester);
        createCase(pid, "他人用例B");

        CaseExportResult result = caseService.export(tester,
                new CaseExportRequest(pid, null, 1, null, null, null, null, null, null));
        assertEquals(1, result.total(), "本人范围只导出本人用例");
        assertEquals("本人用例", readBack(result.content()).get(0).get(0));
    }


    @Test
    void export_标题含公式注入字符_单元格转义() {
        long pid = createProject("exp8");
        CaseInfo evil = createCase(pid, "=HYPERLINK(\"http://evil.example\")");
        CaseExportResult result = caseService.export(operator,
                new CaseExportRequest(pid, List.of(evil.id()), null, null, null, null, null, null, null));
        List<Map<Integer, String>> rows = readBack(result.content());
        assertEquals(1, rows.size());
        String titleCell = rows.get(0).get(0);
        assertTrue(titleCell.startsWith("'="),
                "以 = 开头的单元格必须转义为文本（实际: " + titleCell + "）");
    }


    @Test
    void export_超2000条_异步任务完成站内信() throws InterruptedException {
        long pid = createProject("exp9");
        jdbcTemplate.update("""
                INSERT INTO tp_test_case (case_no, project_id, title, priority, confidence, end_type,
                                          status, source, steps_text, created_by)
                SELECT 'TC-EXP9-' || lpad(i::text, 5, '0'), ?, '批量用例' || i, 1, 2, 'web',
                       1, 1, '', 1
                FROM generate_series(1, 2001) i
                """, pid);

        CaseExportResult result = caseService.export(operator,
                new CaseExportRequest(pid, null, 1, null, null, null, null, null, null));
        assertTrue(result.async(), ">2000 条应走异步");
        assertTrue(result.taskId() > 0, "异步任务 id");

        Map<String, Object> done = null;
        for (int i = 0; i < 100; i++) {
            List<Map<String, Object>> tasks = jdbcTemplate.queryForList(
                    "SELECT status, file_key, row_count FROM tp_export_task WHERE id = ?", result.taskId());
            if (!tasks.isEmpty() && ((Number) tasks.get(0).get("status")).intValue() >= 2) {
                done = tasks.get(0);
                break;
            }
            Thread.sleep(200);
        }
        assertTrue(done != null, "异步任务应完成");
        assertEquals(2, ((Number) done.get("status")).intValue(), "任务状态=完成");
        assertEquals(2001, ((Number) done.get("row_count")).intValue());
        Long notes = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_notification WHERE event_type = 'EXPORT_DONE'", Long.class);
        assertTrue(notes != null && notes >= 1, "EXPORT_DONE 站内信");
    }
}
