package com.sixbean.testpike.core.imports.domain.service;

import com.alibaba.excel.EasyExcel;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.api.dto.CaseQuery;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.imports.api.dto.ImportItemResult;
import com.sixbean.testpike.core.imports.api.dto.ImportResult;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.requirement.api.dto.RequirementQuery;
import com.sixbean.testpike.core.requirement.domain.service.RequirementService;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class ImportServiceTest {

    @Autowired
    private ImportService importService;

    @Autowired
    private CaseService caseService;

    @Autowired
    private RequirementService requirementService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
 * R37 fixture-scoped teardown : every seeded row
 * hangs off the PJ* fixture-project family — imported cases with their
 * steps and requirement links, requirements, memberships and the
 * case-import audit rows (project-keyed, default sweep set). The family
 * is resolved from the database so a crashed run's residue is cleared
 * before the next test. The old blanket TRUNCATE wiped shipped projects
 * and concurrent suites' rows on the shared dev database.
 */
    @BeforeEach
    @AfterEach
    void cleanTables() {
        SharedDbFixtureSweep.byProjectList(jdbcTemplate, jdbcTemplate.queryForList(
                "SELECT id FROM tp_project WHERE code LIKE 'PJ%'", Long.class));
    }

    private long createProject(String code) {
        return projectService.create(1L, new ProjectCreateInput("项目" + code, code, "zh-CN", "P1", null));
    }

    
    private byte[] caseExcel(List<List<Object>> rows) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        EasyExcel.write(baos)
                .head(List.of(List.of("测试目标"), List.of("前置条件"), List.of("预期结果"),
                        List.of("优先级"), List.of("置信度"), List.of("终端类型"), List.of("步骤")))
                .sheet("用例")
                .doWrite(rows);
        return baos.toByteArray();
    }

    private byte[] requirementExcel(List<List<Object>> rows) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        EasyExcel.write(baos)
                .head(List.of(List.of("需求标题"), List.of("章节"), List.of("验收标准"), List.of("来源")))
                .sheet("需求")
                .doWrite(rows);
        return baos.toByteArray();
    }


    @Test
    void testImportCasesNormal_TCJ101() {
        long projectId = createProject("PJ1");
        List<List<Object>> rows = new ArrayList<>();
        rows.add(List.of("登录功能", "已注册账号", "登录成功进入首页", "1", "2", "web", "打开系统 => 登录页显示; 输入账号密码 => 登录成功"));
        rows.add(List.of("注册功能", "未注册", "注册成功跳转登录", "1", "2", "web", "点击注册 => 表单显示; 提交 => 注册成功"));
        rows.add(List.of("退出功能", "已登录", "退出回到登录页", "2", "1", "web", "点击退出 => 回到登录页"));

        ImportResult result = importService.importCases(1L, projectId, caseExcel(rows), "cases.xlsx", 1);

        assertEquals(3, result.successCount(), "3 条合法全部成功");
        assertEquals(0, result.failCount());
        PageResult<CaseInfo> list = caseService.list(new CaseQuery(projectId, null, null, null, null, null, null, null, null, null, 1, 20));
        assertEquals(3, list.total());
        Integer auditCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tp_audit_log WHERE object_type = 'case_import' AND action = 'import'",
                Integer.class);
        assertNotNull(auditCount);
        assertTrue(auditCount >= 1, "导入审计日志必须存在");
        Long source = jdbcTemplate.queryForObject(
                "SELECT source FROM tp_test_case WHERE title = '登录功能' AND project_id = ?",
                Long.class, projectId);
        assertEquals(3L, source, "导入用例来源=3 存量导入");
    }


    @Test
    void testImportPartialFailAndRetry_TCJ102_ITJ102() {
        long projectId = createProject("PJ2");
        List<List<Object>> first = new ArrayList<>();
        first.add(List.of("登录功能", "已注册账号", "登录成功进入首页", "1", "2", "web", "打开系统 => 登录页显示"));
        first.add(List.of("", "无标题", "非法行", "1", "2", "web", ""));
        ImportResult firstResult = importService.importCases(1L, projectId, caseExcel(first), "cases.xlsx", 1);

        assertEquals(1, firstResult.successCount());
        assertEquals(1, firstResult.failCount());
        ImportItemResult failedRow = firstResult.items().get(1);
        assertTrue(!failedRow.success() && failedRow.reason().contains("标题"),
                "失败原因必须人话指出字段问题，实际: " + failedRow.reason());

        List<List<Object>> fixed = new ArrayList<>();
        fixed.add(List.of("登录功能", "已注册账号", "登录成功进入首页", "1", "2", "web", "打开系统 => 登录页显示"));
        fixed.add(List.of("注册功能", "未注册", "注册成功跳转登录", "1", "2", "web", "点击注册 => 表单显示"));
        ImportResult retry = importService.retryFailedCases(1L, projectId, caseExcel(fixed), "cases.xlsx", 1, List.of(3));

        assertEquals(1, retry.successCount(), "仅重试失败行号 [3] 应成功");
        assertEquals(0, retry.failCount());
        ImportResult again = importService.retryFailedCases(1L, projectId, caseExcel(fixed), "cases.xlsx", 1, List.of(2, 3));
        assertEquals(0, again.successCount());
        assertEquals(2, again.failCount());
        assertTrue(again.items().get(0).reason().contains("重复"), "库内已存在须标重复，实际: " + again.items().get(0).reason());
        PageResult<CaseInfo> list = caseService.list(new CaseQuery(projectId, null, null, null, null, null, null, null, null, null, 1, 20));
        assertEquals(2, list.total(), "成功项不得重复导入");
    }


    @Test
    void testImportEmptyFile_ITJ101() {
        long projectId = createProject("PJ3");
        ServiceException ex = assertThrows(ServiceException.class,
                () -> importService.importCases(1L, projectId, new byte[0], "empty.xlsx", 1));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode(), "空文件须 40001 人话");
        assertTrue(ex.getMessage().contains("文件"), "错误信息须人话，实际: " + ex.getMessage());
        assertEquals(0, caseService.list(new CaseQuery(projectId, null, null, null, null, null, null, null, null, null, 1, 20)).total(),
                "无部分入库");
    }

    @Test
    void testImportBadFormat_ITJ101() {
        long projectId = createProject("PJ4");
        byte[] bad = "这不是一个 Excel 文件".getBytes(StandardCharsets.UTF_8);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> importService.importCases(1L, projectId, bad, "bad.xlsx", 1));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertEquals(0, caseService.list(new CaseQuery(projectId, null, null, null, null, null, null, null, null, null, 1, 20)).total(),
                "坏格式无部分入库");
    }


    @Test
    void testImportDuplicateInDb_ITJ102() {
        long projectId = createProject("PJ5");
        List<List<Object>> rows = new ArrayList<>();
        rows.add(List.of("登录功能", "已注册账号", "登录成功进入首页", "1", "2", "web", "打开系统 => 登录页显示"));
        ImportResult first = importService.importCases(1L, projectId, caseExcel(rows), "cases.xlsx", 1);
        assertEquals(1, first.successCount());

        ImportResult second = importService.importCases(1L, projectId, caseExcel(rows), "cases.xlsx", 1);
        assertEquals(0, second.successCount());
        assertEquals(1, second.failCount());
        assertTrue(second.items().get(0).reason().contains("重复"));
        assertEquals(1, caseService.list(new CaseQuery(projectId, null, null, null, null, null, null, null, null, null, 1, 20)).total());
    }


    @Test
    void testImportRequirementsNormal() {
        long projectId = createProject("PJ6");
        List<List<Object>> rows = new ArrayList<>();
        rows.add(List.of("用户登录", "3.1", "登录成功进入首页", "3"));
        rows.add(List.of("用户注册", "3.2", "注册成功跳转登录", "3"));
        rows.add(List.of("用户退出", "3.3", "退出回到登录页", "3"));

        ImportResult result = importService.importRequirements(1L, projectId, requirementExcel(rows), "req.xlsx");

        assertEquals(3, result.successCount());
        assertEquals(0, result.failCount());
        assertEquals(3, requirementService.list(new RequirementQuery(projectId, null, null, 1, 20)).total());
    }
}
