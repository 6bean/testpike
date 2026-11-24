package com.sixbean.testpike.core.report.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.domain.component.ReviewAction;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.executor.api.dto.ExecResultInput;
import com.sixbean.testpike.core.executor.api.dto.ExecTaskCreateInput;
import com.sixbean.testpike.core.executor.api.dto.ExecutorRegisterInput;
import com.sixbean.testpike.core.executor.domain.service.ExecTaskService;
import com.sixbean.testpike.core.executor.domain.service.ExecutorService;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.api.dto.ProjectEnvInput;
import com.sixbean.testpike.core.project.domain.service.ProjectEnvService;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.report.api.dto.AiQualityView;
import com.sixbean.testpike.core.report.api.dto.CompareItemView;
import com.sixbean.testpike.core.report.api.dto.CoverageView;
import com.sixbean.testpike.core.report.api.dto.ExecTrendPoint;
import com.sixbean.testpike.core.report.api.dto.ExecutionStatView;
import com.sixbean.testpike.core.report.api.dto.ExportDownloadView;
import com.sixbean.testpike.core.report.api.dto.ExportResultView;
import com.sixbean.testpike.core.report.api.dto.ExportTaskView;
import com.sixbean.testpike.core.report.api.dto.PersonalReviewView;
import com.sixbean.testpike.core.report.api.dto.PhaseCount;
import com.sixbean.testpike.core.report.api.dto.TeamMemberStatView;
import com.sixbean.testpike.core.requirement.api.dto.RequirementCreateInput;
import com.sixbean.testpike.core.requirement.domain.service.RequirementService;
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
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class ReportServiceTest {

    @Autowired
    private ReportService reportService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private CaseService caseService;

    @Autowired
    private RequirementService requirementService;

    @Autowired
    private ExecTaskService execTaskService;

    @Autowired
    private ExecutorService executorService;

    @Autowired
    private ProjectEnvService projectEnvService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    @BeforeEach
    @AfterEach
    void cleanTables() {
        // Fixture-scoped teardown (R37): remove only this suite's created
        // project subtrees — the blanket TRUNCATE wiped concurrent chains'
        // in-flight rows on the shared dev database (r13 postmortem).
        SharedDbFixtureSweep.byProjectList(jdbcTemplate, fixtureProjectIds);
        fixtureProjectIds.clear();
        // Resolve the shipped demo accounts by name instead of seeding users
        // on fixed ids: on a fresh-install database the low ids already belong
        // to factory demo rows, so an id-keyed INSERT ... DO NOTHING silently
        // no-ops and every id-based assertion reads another account (the demo
        // tester). Factory rows stay untouched (baseline rule R37).
        testerId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_user WHERE username = 'demo_tester' AND deleted = 0", Long.class);
        leaderId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_user WHERE username = 'demo_test_lead' AND deleted = 0", Long.class);
    }

    private final List<Long> fixtureProjectIds = new ArrayList<>();
    private long testerId;
    private long leaderId;

    private long createProject(String code) {
        // Pre-clean a leftover fixture of the same code (a run interrupted
        // before @AfterEach leaves it behind). The sweep touches only this
        // exact code's subtree on the shared dev database.
        SharedDbFixtureSweep.byProject(jdbcTemplate, SharedDbFixtureSweep.projectIdByCode(jdbcTemplate, code));
        long id = projectService.create(testerId, new ProjectCreateInput("项目" + code, code, "zh-CN", "P1", null));
        fixtureProjectIds.add(id);
        return id;
    }

    private long createCase(long projectId, String title, int source) {
        CaseInfo info = caseService.create(testerId, new CaseCreateInput(projectId, title, "前置", "预期",
                1, 2, "web", 1, null, source, null, List.of()));
        return info.id();
    }

    private void review(long caseId, ReviewAction action, String remark) {
        Integer version = jdbcTemplate.queryForObject(
                "SELECT version FROM tp_test_case WHERE id = ?", Integer.class, caseId);
        caseService.review(testerId, caseId, action, remark, version,
                action == ReviewAction.PASS ? null : List.of("logic_missing"));
    }

    
    private void insertReviewLog(long caseId, long userId, String action, int minutesAgo) {
        jdbcTemplate.update("""
                INSERT INTO tp_review_log (case_id, user_id, action, remark, created_at)
                VALUES (?, ?, ?, '测试', now() - (? || ' minutes')::interval)
                """, caseId, userId, action, minutesAgo);
    }

    private long createRequirement(long projectId, String title, String chapter) {
        return requirementService.create(testerId, new RequirementCreateInput(
                projectId, 3, null, title, "验收标准", chapter)).id();
    }

    
    private void createExecResult(long projectId, long caseId, String result, Integer errorType) {
        long exId = executorService.register(new ExecutorRegisterInput(
                "ex-rpt-" + System.nanoTime(), null, null, "web", "chromium", 2, "1.0.0", 0));
        List<Long> existing = jdbcTemplate.queryForList(
                "SELECT id FROM tp_project_env WHERE project_id = ? AND env_type = 2 LIMIT 1",
                Long.class, projectId);
        long envId = existing.isEmpty()
                ? projectEnvService.create(projectId, new ProjectEnvInput(
                        "测试环境", 2, "http://demo.local", null, "web", "chromium", "v2.1.0"))
                : existing.get(0);
        long taskId = execTaskService.create(new ExecTaskCreateInput(
                projectId, caseId, "v2", "{\"version\":\"v2\",\"steps\":[]}", envId));
        var view = execTaskService.claim(exId);
        assertNotNull(view, "执行器必须领取到任务");
        execTaskService.submitResult(new ExecResultInput(
                taskId, view.claimToken(), result, errorType,
                errorType == null ? null : "错误详情", null, null, 1000, null));
    }


    @Test
    void 个人评审进度_数量与平均时长口径() {
        long pid = createProject("R1");
        long c1 = createCase(pid, "用例一", 1);
        long c2 = createCase(pid, "用例二", 1);
        long c3 = createCase(pid, "用例三", 1);
        review(c1, ReviewAction.PASS, null);
        review(c2, ReviewAction.REJECT, "重复用例");
        insertReviewLog(c3, testerId, "需修改", 5);
        insertReviewLog(c3, testerId, "通过", 0);

        PersonalReviewView v = reportService.personalReview(pid, testerId);

        assertEquals(3, v.reviewedCount(), "已评审数=3 个不同用例");
        assertEquals(2, v.passCount(), "通过数=2（c1 一次通过 + c3 通过记录）");
        assertEquals(1, v.needChangeCount(), "需修改数=1（c3）");
        assertEquals(1, v.rejectCount(), "拒绝数=1（c2）");
        assertTrue(v.avgReviewSeconds() > 0, "平均评审时长应>0（c3 两条评审记录差 5 分钟）");
    }

    @Test
    void 团队看板_成员进度与积压() {
        long pid = createProject("R2");
        long c1 = createCase(pid, "成员用例一", 1);
        long c2 = createCase(pid, "成员用例二", 1);
        review(c1, ReviewAction.PASS, null);
        jdbcTemplate.update("UPDATE tp_test_case SET assignee_id = ? WHERE id = ?", leaderId, c2);

        List<TeamMemberStatView> board = reportService.teamBoard(pid);

        assertFalse(board.isEmpty(), "看板应有成员行");
        TeamMemberStatView me = board.stream().filter(s -> s.userId() == testerId).findFirst().orElseThrow();
        assertEquals(1, me.passCount(), "用户 1 通过数=1");
        TeamMemberStatView lead = board.stream().filter(s -> s.userId() == leaderId).findFirst().orElseThrow();
        assertEquals(1, lead.backlogCount(), "用户 2 积压数=1（分配未终态）");
        assertEquals("demo_test_lead", lead.username(), "成员账号展示");
    }

    @Test
    void AI质量_一次通过率修改率拒绝率口径() {
        long pid = createProject("R3");
        long a1 = createCase(pid, "AI一", 1);
        long a2 = createCase(pid, "AI二", 1);
        long a3 = createCase(pid, "AI三", 1);
        long a4 = createCase(pid, "AI四", 1);
        createCase(pid, "模板用例", 2);
        review(a1, ReviewAction.PASS, null);
        review(a2, ReviewAction.PASS, null);
        insertReviewLog(a3, testerId, "需修改", 5);
        insertReviewLog(a3, testerId, "通过", 0);
        review(a4, ReviewAction.REJECT, "重复");

        AiQualityView v = reportService.aiQuality(pid);

        assertEquals(4, v.totalReviewed(), "评审用例总数=4（仅 AI 生成且有评审记录）");
        assertEquals(2, v.firstPassCount(), "首次评审即通过=2（a1/a2）");
        assertEquals(0.5, v.firstPassRate(), 0.001, "一次通过率=2/4");
        assertEquals(1, v.modifyCount(), "修改数=1（a3 发生过需修改）");
        assertEquals(0.25, v.modifyRate(), 0.001, "修改率=1/4");
        assertEquals(1, v.rejectCount(), "拒绝数=1（a4 终态拒绝）");
        assertEquals(0.25, v.rejectRate(), 0.001, "拒绝率=1/4");
        assertFalse(v.statusDist().isEmpty(), "状态分布非空");
    }

    @Test
    void 覆盖率_已覆盖需求点占比_归档用例不计入() {
        long pid = createProject("R4");
        long reqA = createRequirement(pid, "需求A", "章节一");
        long reqB = createRequirement(pid, "需求B", "章节二");
        long c1 = createCase(pid, "覆盖用例", 1);
        requirementService.linkCases(testerId, reqA, List.of(c1));

        CoverageView v = reportService.coverage(pid);
        assertEquals(2, v.totalRequirements(), "需求点总数=2");
        assertEquals(1, v.coveredRequirements(), "已覆盖=1（需求A 有关联用例）");
        assertEquals(0.5, v.coverageRate(), 0.001, "覆盖率=1/2");
        assertEquals(2, v.byChapter().size(), "按章节分组=2");

        jdbcTemplate.update("UPDATE tp_test_case SET status = 9 WHERE id = ?", c1);
        CoverageView archived = reportService.coverage(pid);
        assertEquals(0, archived.coveredRequirements(), "归档用例不计入覆盖");
        assertEquals(0.0, archived.coverageRate(), 0.001);
    }


    @Test
    void 覆盖率_停用需求点不计入_与矩阵同源_启用回算一致() {
        long pid = createProject("R17");
        long reqA = createRequirement(pid, "需求A", "章节一");
        createRequirement(pid, "需求B", "章节二");
        long c1 = createCase(pid, "覆盖用例", 1);
        requirementService.linkCases(testerId, reqA, List.of(c1));

        CoverageView before = reportService.coverage(pid);
        assertEquals(2, before.totalRequirements(), "初始分母=2");
        assertEquals(1, before.coveredRequirements(), "初始分子=1");
        assertEquals(0.5, before.coverageRate(), 0.001, "初始覆盖率=1/2");

        requirementService.update(testerId, reqA, new com.sixbean.testpike.core.requirement.api.dto
                .RequirementUpdateInput(null, null, null, null, null, 3, null));
        CoverageView off = reportService.coverage(pid);
        assertEquals(1, off.totalRequirements(), "停用后分母收缩（已下线不计入）");
        assertEquals(0, off.coveredRequirements(), "停用后分子=0（A 的下线关联不计数）");
        assertEquals(0.0, off.coverageRate(), 0.001, "停用后覆盖率=0/1");
        assertEquals(1, off.byChapter().stream().mapToLong(c -> c.total()).sum(),
                "章节分组同步收缩（章节一随 A 下线整组消失）");
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_case_requirement WHERE requirement_id IN"
                        + " (SELECT id FROM tp_requirement WHERE project_id = ?)",
                Integer.class, pid).intValue(),
                "停用不动关联行");

        requirementService.update(testerId, reqA, new com.sixbean.testpike.core.requirement.api.dto
                .RequirementUpdateInput(null, null, null, null, null, 1, null));
        CoverageView after = reportService.coverage(pid);
        assertEquals(before.totalRequirements(), after.totalRequirements(), "回算分母一致");
        assertEquals(before.coveredRequirements(), after.coveredRequirements(), "回算分子一致");
        assertEquals(before.coverageRate(), after.coverageRate(), 0.001, "回算覆盖率一致");
        assertEquals(before.byChapter().size(), after.byChapter().size(), "回算章节分组一致");
    }

    @Test
    void 执行统计趋势_超时计入分母_通过率与O10注册表同源() {
        long pid = createProject("R5O");
        long c1 = createCase(pid, "趋势用例", 1);
        createExecResult(pid, c1, "pass", null);
        createExecResult(pid, c1, "fail", 1);
        long timeoutId = insertExecTaskWithStatus(pid, c1, 5);
        insertExecTaskWithStatus(pid, c1, 6);

        ExecutionStatView v = reportService.executionStat(pid);

        assertEquals(1, v.trend().size());
        assertEquals(1, v.trend().get(0).passCount(), "通过=1");
        assertEquals(1, v.trend().get(0).failCount(), "失败=1");
        assertEquals(1, v.trend().get(0).timeoutCount(), "超时=1（新增列）");
        assertEquals(1.0 / 3.0, v.trend().get(0).passRate(), 0.001,
                "通过率=1÷(1+1+1)=1/3（超时进分母；旧口径 1/2 判败=RED 实证）");
        assertTrue(timeoutId > 0);
        Double registry = queryCaliberScalar("pass-rate", pid);
        assertNotNull(registry, "注册表直查应有值");
        assertEquals(registry, v.trend().get(0).passRate(), 0.001, "trend 行内率=注册表直查值");
    }

    @Test
    void AI质量_一次通过率与O10注册表同源() {
        long pid = createProject("R5A");
        long a1 = createCase(pid, "AI通过", 1);
        long a2 = createCase(pid, "AI需改后通过", 1);
        review(a1, ReviewAction.PASS, null);
        insertReviewLog(a2, testerId, "需修改", 5);
        insertReviewLog(a2, testerId, "通过", 0);

        AiQualityView v = reportService.aiQuality(pid);
        assertEquals(0.5, v.firstPassRate(), 0.001, "一次通过率=1/2（a1 首次即通过）");

        Double registry = queryCaliberScalar("ai-accept-rate", pid);
        assertNotNull(registry);
        assertEquals(registry, v.firstPassRate(), 0.0001,
                "AI 一次通过率=注册表 ai-accept-rate 直查值（同源禁双算）");
    }

    
    private Double queryCaliberScalar(String code, long projectId) {
        var def = com.sixbean.testpike.common.report.CaliberService.require(code);
        java.util.Map<String, Object> params = new java.util.HashMap<>();
        for (String p : def.namedParams()) {
            params.put(p, "projectId".equals(p) ? projectId : null);
        }
        var query = com.sixbean.testpike.common.report.CaliberService.resolve(code, params);
        return namedParameterJdbcTemplate.query(query.sql(), query.params(),
                rs -> rs.next() ? rs.getDouble("value") : null);
    }

    
    private long insertExecTaskWithStatus(long projectId, long caseId, int status) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO tp_exec_task (project_id, case_id, status, schema_version, action_json) "
                        + "VALUES (?, ?, ?, 'v2', '{}') RETURNING id",
                Long.class, projectId, caseId, status);
    }

    @Test
    void 执行统计_通过率趋势与失败原因分布() {
        long pid = createProject("R5");
        long c1 = createCase(pid, "执行用例一", 1);
        long c2 = createCase(pid, "执行用例二", 1);
        createExecResult(pid, c1, "pass", null);
        createExecResult(pid, c1, "fail", 1);
        createExecResult(pid, c2, "pass", null);

        ExecutionStatView v = reportService.executionStat(pid);

        assertEquals(1, v.trend().size(), "趋势按日期聚合=1 天");
        assertEquals(2, v.trend().get(0).passCount(), "通过=2");
        assertEquals(1, v.trend().get(0).failCount(), "失败=1");
        assertEquals(0, v.trend().get(0).timeoutCount(), "超时=0");
        assertEquals(2.0 / 3.0, v.trend().get(0).passRate(), 0.001, "通过率=2/3");
        var fail1 = v.failDist().stream().filter(f -> f.errorType() == 1).findFirst().orElseThrow();
        assertEquals(1, fail1.count(), "产品缺陷分布=1");
        assertEquals("产品缺陷", fail1.label(), "失败分类中文标签");
    }

    @Test
    void 执行统计_按计划维度过滤_仅统计该计划批次任务() {
        long pid = createProject("R10");
        long c1 = createCase(pid, "计划用例一", 1);
        long c2 = createCase(pid, "计划用例二", 1);
        long c3 = createCase(pid, "计划外用例", 1);
        createExecResult(pid, c3, "pass", null);

        long planId = jdbcTemplate.queryForObject("""
                INSERT INTO tp_exec_plan (project_id, name, status, owner_id)
                VALUES (?, 'P统计', 2, 1) RETURNING id
                """, Long.class, pid);
        long envId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_project_env WHERE project_id = ? AND env_type = 2 LIMIT 1", Long.class, pid);
        long batchId = jdbcTemplate.queryForObject("""
                INSERT INTO tp_exec_batch (project_id, env_id, batch_no, name, status, plan_id, created_by)
                VALUES (?, ?, 'B-P1', 'P统计批次', 6, ?, 1) RETURNING id
                """, Long.class, pid, envId, planId);
        createExecResult(pid, c1, "pass", null);
        createExecResult(pid, c2, "fail", 1);
        jdbcTemplate.update("UPDATE tp_exec_task SET batch_id = ? WHERE case_id IN (?, ?)", batchId, c1, c2);

        ExecutionStatView planView = reportService.executionStat(pid, planId);
        assertEquals(1, planView.trend().get(0).passCount(), "计划维度通过=1");
        assertEquals(1, planView.trend().get(0).failCount(), "计划维度失败=1");
        assertEquals(1L, planView.failDist().stream().filter(f -> f.errorType() == 1)
                .findFirst().orElseThrow().count(), "计划维度失败分布=1（产品缺陷）");

        ExecutionStatView all = reportService.executionStat(pid);
        assertEquals(2, all.trend().get(0).passCount(), "项目级通过=2（含计划外）");
        assertEquals(1, all.trend().get(0).failCount(), "项目级失败=1");
    }

    @Test
    void 跨项目隔离_报表不含他项目数据() {
        long pidA = createProject("RA");
        long pidB = createProject("RB");
        long ca = createCase(pidA, "A用例", 1);
        createCase(pidB, "B用例", 1);
        review(ca, ReviewAction.PASS, null);
        createRequirement(pidA, "A需求", "章");
        createRequirement(pidB, "B需求", "章");

        AiQualityView a = reportService.aiQuality(pidA);
        CoverageView cov = reportService.coverage(pidA);
        assertEquals(1, a.totalReviewed(), "A 报表不含 B 数据（评审总数=1）");
        assertEquals(1, cov.totalRequirements(), "A 覆盖率只算 A 需求点");
    }


    @Test
    void 导出_同步Excel_含脱敏与审计() throws Exception {
        long pid = createProject("R6");
        long c1 = createCase(pid, "导出用例", 1);
        review(c1, ReviewAction.PASS, null);

        ExportResultView out = reportService.export(testerId, pid, "team_board", true);

        assertFalse(out.async(), "小数据量同步返回");
        assertNotNull(out.content(), "Excel 文件内容非空");
        assertTrue(out.content().length > 100, "Excel 字节有效");
        assertTrue((out.content()[0] & 0xFF) == 0x50 && out.content()[1] == 0x4B, "xlsx 文件头 PK");
        String excelText = readExcelText(out.content());
        assertTrue(excelText.contains("***"), "脱敏导出账号应打码: " + excelText);
        assertFalse(excelText.contains("demo_tester"), "原账号不应出现: " + excelText);
        // object_id is a varchar column holding the audited id as text.
        Long audit = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_audit_log WHERE object_type = 'report' AND action = 'export'"
                        + " AND object_id = ?", Long.class, String.valueOf(pid));
        assertEquals(1L, audit, "导出行为进审计");
    }

    @Test
    void 导出_非法报表类型_400人话() {
        long pid = createProject("R7");
        ServiceException ex = assertThrows(ServiceException.class,
                () -> reportService.export(testerId, pid, "unknown_type", false));
        assertTrue(ex.getMessage().contains("报表类型"), "非法报表类型提示人话：" + ex.getMessage());
    }

    @Test
    void 导出_超5000行_异步任务完成站内信通知() throws InterruptedException {
        long pid = createProject("R8");
        long c1 = createCase(pid, "大导出用例", 1);
        jdbcTemplate.update("""
                INSERT INTO tp_exec_task (project_id, case_id, status, schema_version, action_json, created_at)
                SELECT ?, ?, 3, 'v2', '{}', now() - (i || ' minutes')::interval
                FROM generate_series(1, 5001) i
                """, pid, c1);

        ExportResultView out = reportService.export(testerId, pid, "execution_stat", false);

        assertTrue(out.async(), ">5000 行应走异步");
        assertTrue(out.taskId() > 0, "异步导出任务 id");
        Integer status = jdbcTemplate.queryForObject(
                "SELECT status FROM tp_export_task WHERE id = ?", Integer.class, out.taskId());
        assertEquals(1, status, "导出任务初始=处理中");

        ExportTaskView done = null;
        for (int i = 0; i < 30; i++) {
            done = reportService.listExportTasks(pid).stream()
                    .filter(t -> t.id() == out.taskId()).findFirst().orElse(null);
            if (done != null && done.status() == 2) {
                break;
            }
            Thread.sleep(1000);
        }
        assertNotNull(done, "异步任务应存在");
        assertEquals(2, done.status(), "异步导出应完成");
        assertEquals(5001, done.rowCount(), "导出行数=5001");
        assertNotNull(done.fileKey(), "完成时落 S3 fileKey");
        Long notify = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_notification WHERE event_type = 'EXPORT_DONE' AND project_id = ?",
                Long.class, pid);
        assertEquals(1L, notify, "完成站内信通知（IT-J3-02）");
    }

    @Test
    void 导出任务_下载_预签名URL与状态校验() {
        long pid = createProject("R9");
        long taskId = jdbcTemplate.queryForObject("""
                INSERT INTO tp_export_task (project_id, report_type, masked, status, file_key, row_count, created_by)
                VALUES (?, 'team_board', 1, 2, 'export/%d/team_board_test.xlsx', 3, 1) RETURNING id
                """.formatted(pid), Long.class, pid);

        ExportDownloadView ok = reportService.exportTaskDownload(pid, taskId);
        assertEquals(2, ok.status(), "完成态任务返回下载");
        assertNotNull(ok.presignedUrl(), "预签名 URL 非空");
        assertTrue(ok.presignedUrl().contains("X-Amz-Signature"), "S3 预签名参数: " + ok.presignedUrl());
        assertTrue(ok.fileName().contains("team_board"), "文件名含报表类型");

        long pendingId = jdbcTemplate.queryForObject("""
                INSERT INTO tp_export_task (project_id, report_type, masked, status, created_by)
                VALUES (?, 'coverage', 0, 1, 1) RETURNING id
                """, Long.class, pid);
        ServiceException pending = assertThrows(ServiceException.class,
                () -> reportService.exportTaskDownload(pid, pendingId));
        assertTrue(pending.getMessage().contains("尚未完成"), "处理中提示人话: " + pending.getMessage());

        ServiceException missing = assertThrows(ServiceException.class,
                () -> reportService.exportTaskDownload(pid, 999999));
        assertTrue(missing.getMessage().contains("不存在"), "任务不存在提示人话: " + missing.getMessage());

        long pidB = createProject("RB2");
        ServiceException cross = assertThrows(ServiceException.class,
                () -> reportService.exportTaskDownload(pidB, taskId));
        assertTrue(cross.getMessage().contains("不存在"), "跨项目不可见: " + cross.getMessage());
    }

    
    @SuppressWarnings("unchecked")
    private String readExcelText(byte[] content) throws Exception {
        var rows = com.alibaba.excel.EasyExcel.read(new java.io.ByteArrayInputStream(content))
                .sheet().headRowNumber(1).doReadSync();
        StringBuilder sb = new StringBuilder();
        for (Object row : rows) {
            if (row instanceof java.util.Map<?, ?> m) {
                for (Object v : m.values()) {
                    sb.append(String.valueOf(v)).append('|');
                }
            }
        }
        return sb.toString();
    }


    @Test
    void 阶段分布_按阶段聚合_含未归属() {
        long pid = createProject("K10a");
        caseService.create(testerId, new CaseCreateInput(pid, "冒烟", null, null, 1, 2, "web", 1, 1, 1, null, List.of()));
        caseService.create(testerId, new CaseCreateInput(pid, "功能", null, null, 1, 2, "web", 1, 2, 1, null, List.of()));
        caseService.create(testerId, new CaseCreateInput(pid, "回归", null, null, 1, 2, "web", 1, 3, 1, null, List.of()));
        caseService.create(testerId, new CaseCreateInput(pid, "未归属", null, null, 1, 2, "web", 1, null, 1, null, List.of()));

        List<PhaseCount> dist = reportService.phaseDist(pid);
        assertEquals(4, dist.size(), "4 个阶段桶（1/2/3/0）");
        Map<Integer, Long> byPhase = dist.stream().collect(Collectors.toMap(PhaseCount::phase, PhaseCount::count));
        assertEquals(1L, byPhase.get(1));
        assertEquals(1L, byPhase.get(2));
        assertEquals(1L, byPhase.get(3));
        assertEquals(1L, byPhase.get(0), "未归属聚合到 0");
        assertEquals("冒烟", dist.stream().filter(p -> p.phase() == 1).findFirst().orElseThrow().label());
        assertEquals("未归属", dist.stream().filter(p -> p.phase() == 0).findFirst().orElseThrow().label());
    }

    @Test
    void 阶段分布_跨项目隔离_已删除不计入() {
        long pid = createProject("K10b");
        long pid2 = createProject("K10c");
        caseService.create(testerId, new CaseCreateInput(pid, "A冒烟", null, null, 1, 2, "web", 1, 1, 1, null, List.of()));
        caseService.create(testerId, new CaseCreateInput(pid2, "B回归", null, null, 1, 2, "web", 1, 3, 1, null, List.of()));
        long del = caseService.create(testerId, new CaseCreateInput(pid, "已删除", null, null, 1, 2, "web", 1, 4, 1, null, List.of())).id();
        caseService.delete(testerId, del);

        List<PhaseCount> dist = reportService.phaseDist(pid);
        long total = dist.stream().mapToLong(PhaseCount::count).sum();
        assertEquals(1, total, "跨项目 + 已删除不计入，只剩 1 条");
        assertEquals(1L, dist.stream().filter(p -> p.phase() == 1).findFirst().orElseThrow().count());
    }


    @Test
    void 对比_参数校验_空列表与非数字越界400人话() {
        ServiceException empty = assertThrows(ServiceException.class,
                () -> reportService.compare(List.of()));
        assertTrue(empty.getMessage().contains("项目"), "空列表提示人话: " + empty.getMessage());

        long pid = createProject("H6v");
        ServiceException nonNumeric = assertThrows(ServiceException.class,
                () -> reportService.compare(List.of(pid, -1L)));
        assertTrue(nonNumeric.getMessage().contains("非法") || nonNumeric.getMessage().contains("不存在"),
                "非法 id 人话: " + nonNumeric.getMessage());

        List<Long> tooMany = new java.util.ArrayList<>();
        for (long i = 1; i <= 21; i++) {
            tooMany.add(i);
        }
        ServiceException over = assertThrows(ServiceException.class,
                () -> reportService.compare(tooMany));
        assertTrue(over.getMessage().contains("20"), "越界提示含上限: " + over.getMessage());
    }

    @Test
    void 对比_聚合与单项报表同源_归档标注() {
        long pidA = createProject("H6a");
        long pidB = createProject("H6b");
        long reqA = createRequirement(pidA, "需求A", "章一");
        long ca = createCase(pidA, "A用例", 1);
        requirementService.linkCases(testerId, reqA, List.of(ca));
        review(ca, ReviewAction.PASS, null);
        jdbcTemplate.update("UPDATE tp_test_case SET flaky = 1 WHERE id = ?", ca);
        createExecResult(pidA, ca, "pass", null);
        createRequirement(pidB, "需求B", "章一");
        projectService.archive(pidB);

        List<CompareItemView> items = reportService.compare(List.of(pidA, pidB));

        assertEquals(2, items.size(), "两项目各一行");
        CompareItemView a = items.stream().filter(i -> i.projectId() == pidA).findFirst().orElseThrow();
        CompareItemView b = items.stream().filter(i -> i.projectId() == pidB).findFirst().orElseThrow();
        assertEquals("项目H6a", a.projectName(), "项目名回显");
        assertFalse(a.archived(), "活跃项目 archived=false");
        assertTrue(b.archived(), "归档项目标注 archived=true");
        CoverageView covA = reportService.coverage(pidA);
        assertEquals(covA.coverageRate(), a.coverage().rate(), 0.0001, "compare 覆盖率=单项 coverage 同源");
        assertEquals(covA.coveredRequirements(), a.coverage().covered(), "分子同源");
        assertEquals(covA.totalRequirements(), a.coverage().total(), "分母同源");
        AiQualityView qaA = reportService.aiQuality(pidA);
        assertEquals(qaA.totalReviewed(), a.aiQuality().totalReviewed(), "AI 质量总数同源");
        assertEquals(qaA.firstPassRate(), a.aiQuality().firstPassRate(), 0.0001, "一次通过率同源");
        assertEquals(1, a.exec().pass(), "执行通过数");
        assertEquals(0, a.exec().fail(), "执行失败数");
        assertEquals(0, a.exec().skip(), "执行跳过数");
        assertEquals(1, a.flaky(), "flaky=tp_test_case.flaky 计数");
        assertEquals(0, b.flaky(), "B 无 flaky");
    }

    @Test
    void 对比_临时批type2排除_与单项执行统计两侧同规则() {
        long pid = createProject("H6t");
        long c1 = createCase(pid, "对比执行用例", 1);
        long envId = projectEnvService.create(pid, new ProjectEnvInput(
                "测试环境", 2, "http://demo.local", null, "web", "chromium", "v2.1.0"));
        long batchFormal = jdbcTemplate.queryForObject("""
                INSERT INTO tp_exec_batch (project_id, env_id, batch_no, name, status, type, created_by)
                VALUES (?, ?, 'B-F', '正式批', 6, 1, 1) RETURNING id
                """, Long.class, pid, envId);
        long batchTemp = jdbcTemplate.queryForObject("""
                INSERT INTO tp_exec_batch (project_id, env_id, batch_no, name, status, type, created_by)
                VALUES (?, ?, 'B-T', '临时批', 6, 2, 1) RETURNING id
                """, Long.class, pid, envId);
        jdbcTemplate.update("""
                INSERT INTO tp_exec_task (project_id, case_id, batch_id, status, schema_version, action_json)
                VALUES (?, ?, ?, 3, 'v2', '{}'),
                       (?, ?, ?, 3, 'v2', '{}'),
                       (?, ?, ?, 4, 'v2', '{}'),
                       (?, ?, ?, 6, 'v2', '{}')
                """, pid, c1, batchFormal,
                pid, c1, batchTemp,
                pid, c1, batchTemp,
                pid, c1, batchTemp);

        List<CompareItemView> items = reportService.compare(List.of(pid));
        CompareItemView v = items.get(0);
        assertEquals(1, v.exec().pass(), "compare 排除临时批：通过只计正式批=1");
        assertEquals(0, v.exec().fail(), "compare 排除临时批：失败=0");
        assertEquals(0, v.exec().skip(), "compare 排除临时批：跳过=0");
        ExecutionStatView single = reportService.executionStat(pid);
        long singlePass = single.trend().stream().mapToLong(ExecTrendPoint::passCount).sum();
        assertEquals(v.exec().pass(), singlePass, "对比口径 vs 列表口径 type=2 两侧同规则（同源禁双写）");
    }
}
