package com.sixbean.testpike.core.defects.domain.service;

import com.alibaba.excel.EasyExcel;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.defects.api.dto.DefectCreateInput;
import com.sixbean.testpike.core.defects.api.dto.DefectExportRequest;
import com.sixbean.testpike.core.defects.api.dto.DefectExportResult;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.support.ProjectFixtureRig;
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
class DefectExportTest {

    @Autowired
    private DefectLibraryService defectLibraryService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private com.sixbean.testpike.core.cases.domain.service.CaseService caseService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long operator;

    /**
 * R37 fixture-scoped teardown : the old blanket TRUNCATE
 * wiped shipped projects, tags, notifications and audit rows on the shared
 * dev database. Everything this suite seeds hangs off its own fixture
 * projects (tp_export_task through the FK walk, audit/notification/tag rows
 * through project_id), so the tracked-project sweep covers the footprint;
 * the dexp_* users carry no project column and self-heal by username
 * family (usersDown also drops their role links and notifications).
 */
    private final List<Long> trackedProjects = new ArrayList<>();

    @BeforeEach
    @AfterEach
    void cleanFixtureRows() {
        ProjectFixtureRig.sweep(jdbcTemplate, trackedProjects);
        SharedDbFixtureSweep.usersByPatternDown(jdbcTemplate, "dexp_%");
        seedBuiltinRoles();
        operator = createUser("dexp_operator");
    }

    
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

    private long createUser(String username) {
        long id = jdbcTemplate.queryForObject("""
                INSERT INTO tp_user (username, password_hash, display_name, role, status)
                VALUES (?, 'x', ?, 1, 1) RETURNING id
                """, Long.class, username, username);
        Long roleId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_role WHERE code = ? LIMIT 1", Long.class, "test_lead");
        jdbcTemplate.update("INSERT INTO tp_user_role (user_id, role_id) VALUES (?, ?)", id, roleId);
        return id;
    }

    private long createProject(String code) {
        return ProjectFixtureRig.createSelfHeal(jdbcTemplate, projectService, 1L,
                trackedProjects, "项目" + code, code);
    }

    private long createDefect(long projectId, String title, int severity) {
        return defectLibraryService.create(operator, new DefectCreateInput(projectId, null, null,
                title, "描述", severity, null, null, null)).id();
    }

    
    private List<Map<Integer, String>> readBack(byte[] content) {
        return EasyExcel.read(new ByteArrayInputStream(content)).headRowNumber(1).sheet().doReadSync();
    }

    private List<Map<Integer, String>> readBackWithHeader(byte[] content) {
        return EasyExcel.read(new ByteArrayInputStream(content)).headRowNumber(0).sheet().doReadSync();
    }


    @Test
    void export_勾选三条_xlsx含编号标题严重程度状态关联用例列() {
        long pid = createProject("dexp1");
        long caseId = caseService.create(1L,
                new com.sixbean.testpike.core.cases.api.dto.CaseCreateInput(pid, "被关联用例", "前置", "预期",
                        1, 2, "web", null, null, null, null, List.of())).id();
        long d1 = defectLibraryService.create(operator, new DefectCreateInput(pid, caseId, null,
                "登录崩溃", "复现描述", 1, null, null, "PROJ-1")).id();
        long d2 = createDefect(pid, "登出白屏", 3);
        long d3 = createDefect(pid, "文案错误", 4);
        long assigneeId = jdbcTemplate.queryForObject(
                "INSERT INTO tp_user (username, password_hash, display_name, role, status)"
                        + " VALUES ('dexp_assignee', 'x', '缺陷修复员', 1, 1) RETURNING id", Long.class);
        jdbcTemplate.update("UPDATE tp_defect SET assignee_id = ? WHERE id = ?", assigneeId, d2);

        // Shared dev database: only rows above this run's floor are ours
        // (group 13-15 lesson - global counts are never suite-owned).
        long auditFloor = SharedDbFixtureSweep.maxId(jdbcTemplate, "tp_audit_log");
        DefectExportResult result = defectLibraryService.export(operator,
                new DefectExportRequest(pid, List.of(d1, d2, d3), null, null, null, null, null));

        assertFalse(result.async(), "3 条同步返回");
        assertEquals(3, result.total());
        assertEquals(0, result.removed());
        assertTrue(result.content().length > 4, "xlsx 字节（PK 魔数）");
        assertEquals('P', (char) result.content()[0]);
        assertEquals('K', (char) result.content()[1]);
        assertTrue(result.fileName().endsWith(".xlsx"));

        List<Map<Integer, String>> header = readBackWithHeader(result.content());
        assertEquals(List.of("缺陷编号", "标题", "严重程度", "状态", "关联用例编号",
                "修复人", "报告人", "外部Key", "描述", "创建时间"),
                header.get(0).values().stream().toList().subList(0, 10));

        List<Map<Integer, String>> rows = readBack(result.content());
        assertEquals(3, rows.size());
        Map<Integer, String> rowD1 = rows.stream()
                .filter(r -> "登录崩溃".equals(r.get(1))).findFirst().orElseThrow();
        assertEquals(String.valueOf(d1), rowD1.get(0), "缺陷编号列=id");
        assertEquals("P0", rowD1.get(2), "严重程度列=中文 label");
        assertEquals("新建", rowD1.get(3), "状态列=中文 label");
        String caseNo = jdbcTemplate.queryForObject(
                "SELECT case_no FROM tp_test_case WHERE id = ?", String.class, caseId);
        assertEquals(caseNo, rowD1.get(4), "关联用例编号列（join tp_test_case）");
        assertEquals("PROJ-1", rowD1.get(7), "外部Key列");
        Map<Integer, String> rowD2 = rows.stream()
                .filter(r -> "登出白屏".equals(r.get(1))).findFirst().orElseThrow();
        assertEquals("缺陷修复员", rowD2.get(5), "修复人列=display_name");

        Long auditCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_audit_log WHERE object_type = 'defect' AND action = 'export'"
                        + " AND id > ?",
                Long.class, auditFloor);
        assertEquals(1L, auditCount, "导出审计一条");
        String auditAfter = jdbcTemplate.queryForObject(
                "SELECT after_json::text FROM tp_audit_log WHERE object_type='defect' AND action='export'"
                        + " AND id > ? LIMIT 1",
                String.class, auditFloor);
        String compact = auditAfter.replace(" ", "");
        assertTrue(compact.contains("\"total\":3") && compact.contains("removed"),
                "审计 after 含导出范围 total/removed：" + auditAfter);
    }


    @Test
    void export_ids含他项目与不存在_静默剔除并count提示() {
        long pid = createProject("dexp2");
        long otherPid = createProject("dexp2b");
        long mine = createDefect(pid, "可见缺陷", 2);
        long foreign = createDefect(otherPid, "他项目缺陷", 2);

        DefectExportResult result = defectLibraryService.export(operator,
                new DefectExportRequest(pid, List.of(mine, foreign, 999_999L), null, null, null, null, null));

        assertEquals(1, result.total(), "仅本项目缺陷导出");
        assertEquals(2, result.removed(), "他项目 + 不存在各剔除 1 条");
        List<Map<Integer, String>> rows = readBack(result.content());
        assertEquals(1, rows.size());
        assertEquals("可见缺陷", rows.get(0).get(1));
    }

    @Test
    void export_ids与filter均空_400请选择导出范围() {
        long pid = createProject("dexp3");
        ServiceException ex = assertThrows(ServiceException.class, () ->
                defectLibraryService.export(operator,
                        new DefectExportRequest(pid, null, null, null, null, null, null)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("请选择导出范围"), ex.getMessage());
    }

    @Test
    void export_ids与filter同给_400二选一() {
        long pid = createProject("dexp4");
        long d = createDefect(pid, "任一条", 2);
        ServiceException ex = assertThrows(ServiceException.class, () ->
                defectLibraryService.export(operator,
                        new DefectExportRequest(pid, List.of(d), 1, null, null, null, null)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("二选一"), ex.getMessage());
    }

    @Test
    void export_ids超5000上限_400() {
        long pid = createProject("dexp5");
        List<Long> ids = new ArrayList<>();
        for (long i = 1; i <= 5001; i++) {
            ids.add(i);
        }
        ServiceException ex = assertThrows(ServiceException.class, () ->
                defectLibraryService.export(operator,
                        new DefectExportRequest(pid, ids, null, null, null, null, null)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("5000"), ex.getMessage());
    }


    @Test
    void export_筛选导出_仅命中筛选条件() {
        long pid = createProject("dexp6");
        createDefect(pid, "严重缺陷A", 1);
        createDefect(pid, "严重缺陷B", 1);
        createDefect(pid, "轻微缺陷C", 4);

        DefectExportResult bySeverity = defectLibraryService.export(operator,
                new DefectExportRequest(pid, null, null, 1, null, null, null));
        assertEquals(2, bySeverity.total(), "筛选=P0 两条");
        List<Map<Integer, String>> rows = readBack(bySeverity.content());
        assertTrue(rows.stream().allMatch(r -> "P0".equals(r.get(2))), "全部行严重程度=P0");
        assertEquals(0, bySeverity.removed(), "筛选模式无剔除概念（查询即过滤）");

        DefectExportResult byKeyword = defectLibraryService.export(operator,
                new DefectExportRequest(pid, null, null, null, null, null, "轻微"));
        assertEquals(1, byKeyword.total(), "关键词命中一条");
        assertEquals("轻微缺陷C", readBack(byKeyword.content()).get(0).get(1));
    }

    @Test
    void export_筛选状态_命中集合收敛() {
        long pid = createProject("dexp7");
        long open = createDefect(pid, "未关闭缺陷", 2);
        long closing = createDefect(pid, "将关闭缺陷", 2);
        jdbcTemplate.update("UPDATE tp_defect SET status = 5 WHERE id = ?", closing);
        assertTrue(open > 0 && closing > 0, "前置两条造数完成");

        DefectExportResult result = defectLibraryService.export(operator,
                new DefectExportRequest(pid, null, 5, null, null, null, null));
        assertEquals(1, result.total(), "筛选=已关闭一条");
        Map<Integer, String> row = readBack(result.content()).get(0);
        assertEquals("将关闭缺陷", row.get(1));
        assertEquals("已关闭", row.get(3), "状态列=中文 label");
    }


    @Test
    void export_标题含公式注入字符_单元格转义() {
        long pid = createProject("dexp8");
        long evil = createDefect(pid, "=HYPERLINK(\"http://evil.example\")", 2);
        DefectExportResult result = defectLibraryService.export(operator,
                new DefectExportRequest(pid, List.of(evil), null, null, null, null, null));
        List<Map<Integer, String>> rows = readBack(result.content());
        assertEquals(1, rows.size());
        String titleCell = rows.get(0).get(1);
        assertTrue(titleCell.startsWith("'="),
                "以 = 开头的单元格必须转义为文本（实际: " + titleCell + "）");
    }


    @Test
    void export_超2000条_异步任务完成站内信() throws InterruptedException {
        long pid = createProject("dexp9");
        jdbcTemplate.update("""
                INSERT INTO tp_defect (project_id, title, severity, status, reporter_id)
                SELECT ?, '批量缺陷' || i, 2, 1, ?
                FROM generate_series(1, 2001) i
                """, pid, operator);

        long noteFloor = SharedDbFixtureSweep.maxId(jdbcTemplate, "tp_notification");
        DefectExportResult result = defectLibraryService.export(operator,
                new DefectExportRequest(pid, null, 1, null, null, null, null));
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
                "SELECT count(*) FROM tp_notification WHERE event_type = 'EXPORT_DONE' AND id > ?",
                Long.class, noteFloor);
        assertTrue(notes != null && notes >= 1, "EXPORT_DONE 站内信");
    }
}
