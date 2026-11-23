package com.sixbean.testpike.core.defects.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.support.CaseFixtureRig;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.defects.api.dto.DefectCreateInput;
import com.sixbean.testpike.core.defects.api.dto.DefectLibraryInfo;
import com.sixbean.testpike.core.defects.api.dto.DefectQuery;
import com.sixbean.testpike.core.defects.api.dto.DefectSummary;
import com.sixbean.testpike.core.defects.api.dto.DefectTransitionInput;
import com.sixbean.testpike.core.defects.api.dto.DefectUpdateInput;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.support.ProjectFixtureRig;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class DefectLibraryServiceTest {

    @Autowired
    private DefectLibraryService defectLibraryService;

    @Autowired
    private CaseService caseService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
 * R37 fixture-scoped teardown : the old blanket TRUNCATE
 * wiped shipped projects, the factory admin (tp_user!) and every
 * concurrent suite's notifications and audit rows on the shared dev
 * database. tp_plan_case declares neither an FK nor a project column, so
 * the per-plan join purge runs BEFORE the project sweep (shared helper,
 * R40); tp_defect comments are polymorphic and go through the defect join
 * helper. Member rows (fixer/reporter/devowner families) keep no FK from
 * tp_user_project.user_id, so they self-heal by username family BEFORE the
 * tp_user delete - a crashed run never poisons the next createMember.
 */
    private static final List<String> MEMBER_USER_PATTERNS =
            List.of("fixer%", "reporter%", "devowner%");

    private final List<Long> trackedProjects = new ArrayList<>();

    @BeforeEach
    @AfterEach
    void cleanFixtureRows() {
        SharedDbFixtureSweep.defectCommentRowsOfProjects(jdbcTemplate, trackedProjects);
        SharedDbFixtureSweep.childRowsOfProjects(jdbcTemplate, "tp_plan_case", "plan_id",
                "tp_exec_plan", trackedProjects);
        ProjectFixtureRig.sweep(jdbcTemplate, trackedProjects);
        for (String pattern : MEMBER_USER_PATTERNS) {
            jdbcTemplate.update("DELETE FROM tp_user_project WHERE user_id IN"
                    + " (SELECT id FROM tp_user WHERE username LIKE ?)", pattern);
            SharedDbFixtureSweep.usersByPatternDown(jdbcTemplate, pattern);
        }
    }

    private long createProject(String code) {
        return ProjectFixtureRig.createSelfHealByCode(jdbcTemplate, projectService, 1L, trackedProjects, code);
    }

    private long createCase(long projectId, String title) {
        return CaseFixtureRig.create(caseService, 1L, projectId, title);
    }

    
    private long createMember(long projectId, String username, String displayName) {
        Long uid = jdbcTemplate.queryForObject("""
                INSERT INTO tp_user (username, password_hash, display_name, role, status)
                VALUES (?, '$2a$10$x', ?, 1, 1) RETURNING id
                """, Long.class, username, displayName);
        jdbcTemplate.update("INSERT INTO tp_user_project (project_id, user_id, role) VALUES (?, ?, 1)",
                projectId, uid);
        return uid;
    }

    
    private long createPlan(long projectId) {
        Long pid = jdbcTemplate.queryForObject("""
                INSERT INTO tp_exec_plan (project_id, name, target_version, status, env_ids_json)
                VALUES (?, '回归计划', 'v2.0.0', 1, '[1]') RETURNING id
                """, Long.class, projectId);
        return pid;
    }

    private DefectLibraryInfo createDefect(long projectId, Long caseId, Long planId, long assigneeId, int severity) {
        return defectLibraryService.create(1L, new DefectCreateInput(projectId, caseId, planId,
                "下单金额计算错误", "满减叠加后金额不符", severity, assigneeId, null, "JIRA-101"));
    }

    @Test
    void test_create_and_full_lifecycle_close_and_reopen() {
        long pid = createProject("D1");
        long caseId = createCase(pid, "下单优惠券校验");
        long fixer = createMember(pid, "fixer1", "修复人甲");
        long reporter = createMember(pid, "reporter1", "报告人乙");

        DefectLibraryInfo created = defectLibraryService.create(reporter, new DefectCreateInput(
                pid, caseId, null, "优惠券叠加金额不符", "满减与券叠加后金额多扣", 2, fixer, null, null));
        assertEquals(1, created.status(), "初始状态=新建");
        assertEquals(reporter, created.reporterId());
        assertEquals(fixer, created.assigneeId());
        assertNotNull(created.reporterName(), "join 报告人姓名");

        DefectLibraryInfo st = defectLibraryService.transition(fixer, created.id(),
                new DefectTransitionInput(2, null, created.version()));
        assertEquals(2, st.status());
        st = defectLibraryService.transition(fixer, st.id(), new DefectTransitionInput(3, null, st.version()));
        assertEquals(3, st.status());
        st = defectLibraryService.transition(reporter, st.id(), new DefectTransitionInput(4, null, st.version()));
        assertEquals(4, st.status());
        st = defectLibraryService.transition(reporter, st.id(),
                new DefectTransitionInput(5, "验证通过，金额正确", st.version()));
        assertEquals(5, st.status());
        assertEquals("验证通过，金额正确", st.closedReason());

        final long closedId = st.id();
        final int closedVer = st.version();
        ServiceException ex = assertThrows(ServiceException.class,
                () -> defectLibraryService.transition(reporter, closedId,
                        new DefectTransitionInput(2, null, closedVer)));
        assertTrue(ex.getMessage().contains("非法状态流转"), ex.getMessage());
        ServiceException ex2 = assertThrows(ServiceException.class,
                () -> defectLibraryService.update(reporter, closedId,
                        new DefectUpdateInput("改标题", null, 1, fixer, null, null, closedVer)));
        assertTrue(ex2.getMessage().contains("已关闭"), ex2.getMessage());

        DefectLibraryInfo d2 = createDefect(pid, caseId, null, fixer, 1);
        d2 = defectLibraryService.transition(fixer, d2.id(), new DefectTransitionInput(2, null, d2.version()));
        d2 = defectLibraryService.transition(fixer, d2.id(), new DefectTransitionInput(3, null, d2.version()));
        d2 = defectLibraryService.transition(reporter, d2.id(), new DefectTransitionInput(4, null, d2.version()));
        d2 = defectLibraryService.transition(reporter, d2.id(),
                new DefectTransitionInput(6, "复现步骤 2 仍失败", d2.version()));
        assertEquals(6, d2.status());
        assertEquals("复现步骤 2 仍失败", d2.closedReason());
        d2 = defectLibraryService.transition(fixer, d2.id(), new DefectTransitionInput(2, null, d2.version()));
        assertEquals(2, d2.status(), "重新打开后回到处理中");
    }

    
    private void updateProjectAttrs(long pid, String jiraId, Long devOwnerId) {
        projectService.update(pid, new com.sixbean.testpike.core.project.api.dto.ProjectUpdateInput(
                "项目属性扩展", "zh-CN", "P1", null, jiraId, null, devOwnerId, null, null, 1, null));
    }

    @Test
    void test_jira_key_defect_key_generation_and_explicit_wins() {
        long pid = createProject("JK1");
        updateProjectAttrs(pid, "TP2026", null);
        DefectLibraryInfo d1 = defectLibraryService.create(1L, new DefectCreateInput(
                pid, null, null, "缺陷甲", null, 2, null, null, null));
        assertEquals("TP2026-001", d1.externalKey(), "首单自动生成 {jiraId}-001");
        DefectLibraryInfo d2 = defectLibraryService.create(1L, new DefectCreateInput(
                pid, null, null, "缺陷乙", null, 2, null, null, null));
        assertEquals("TP2026-002", d2.externalKey(), "次单序号递增");
        DefectLibraryInfo d3 = defectLibraryService.create(1L, new DefectCreateInput(
                pid, null, null, "缺陷丙", null, 2, null, null, "OTHER-9"));
        assertEquals("OTHER-9", d3.externalKey(), "显式 key 原样保留");
        DefectLibraryInfo d4 = defectLibraryService.create(1L, new DefectCreateInput(
                pid, null, null, "缺陷丁", null, 2, null, null, null));
        assertEquals("TP2026-003", d4.externalKey(), "异前缀显式 key 不入本前缀序号池");
    }

    @Test
    void test_jira_key_absent_fallback_zero_break_IT_PRJ_02() {
        long pid = createProject("JK2");
        DefectLibraryInfo d = defectLibraryService.create(1L, new DefectCreateInput(
                pid, null, null, "无 JIRA 项目缺陷", null, 2, null, null, null));
        assertNull(d.externalKey(), "未设 jira_project_id 且未传 key → 保持 null（既有语义）");
    }

    @Test
    void test_dev_owner_default_assignee_and_member_guard() {
        long pid = createProject("JK3");
        long devOwner = createMember(pid, "devowner1", "开发负责人甲");
        updateProjectAttrs(pid, null, devOwner);
        DefectLibraryInfo auto = defectLibraryService.create(1L, new DefectCreateInput(
                pid, null, null, "待分派缺陷", null, 2, null, null, null));
        assertEquals(devOwner, auto.assigneeId(), "未指派修复人 → 默认分派开发 OWNER");
        long fixer = createMember(pid, "fixer9", "修复人丁");
        DefectLibraryInfo explicit = defectLibraryService.create(1L, new DefectCreateInput(
                pid, null, null, "已指派缺陷", null, 2, fixer, null, null));
        assertEquals(fixer, explicit.assigneeId(), "显式 assignee 不被 dev_owner 覆盖");
        jdbcTemplate.update("DELETE FROM tp_user_project WHERE project_id = ? AND user_id = ?", pid, devOwner);
        DefectLibraryInfo skip = defectLibraryService.create(1L, new DefectCreateInput(
                pid, null, null, "分派跳过缺陷", null, 2, null, null, null));
        assertNull(skip.assigneeId(), "dev_owner 非成员 → 跳过默认分派（不 400 阻断提缺陷）");
    }

    @Test
    void test_create_validation_illegal_severity_and_assignee() {
        long pid = createProject("D2");
        long caseId = createCase(pid, "登录校验");

        ServiceException sev = assertThrows(ServiceException.class, () -> defectLibraryService.create(1L,
                new DefectCreateInput(pid, null, null, "标题", null, 9, null, null, null)));
        assertTrue(sev.getMessage().contains("严重级别"), sev.getMessage());

        ServiceException title = assertThrows(ServiceException.class, () -> defectLibraryService.create(1L,
                new DefectCreateInput(pid, null, null, "  ", null, 2, null, null, null)));
        assertTrue(title.getMessage().contains("缺陷标题"), title.getMessage());

        ServiceException noProject = assertThrows(ServiceException.class, () -> defectLibraryService.create(1L,
                new DefectCreateInput(99999L, null, null, "标题", null, 2, null, null, null)));
        assertTrue(noProject.getMessage().contains("项目不存在"), noProject.getMessage());

        ServiceException member = assertThrows(ServiceException.class, () -> defectLibraryService.create(1L,
                new DefectCreateInput(pid, null, null, "标题", null, 2, 99999L, null, null)));
        assertTrue(member.getMessage().contains("不是本项目成员"), member.getMessage());

        ServiceException key = assertThrows(ServiceException.class, () -> defectLibraryService.create(1L,
                new DefectCreateInput(pid, null, null, "标题", null, 2, null, null, "K".repeat(51))));
        assertTrue(key.getMessage().contains("外部缺陷 key"), key.getMessage());

        long pidB = createProject("D2B");
        long caseB = createCase(pidB, "B 项目用例");
        ServiceException cross = assertThrows(ServiceException.class, () -> defectLibraryService.create(1L,
                new DefectCreateInput(pid, caseB, null, "标题", null, 2, null, null, null)));
        assertTrue(cross.getMessage().contains("不属于该项目"), cross.getMessage());
    }

    @Test
    void test_source_and_jira_sync_fields_surfaced_TC_V3_03() {
        long pid = createProject("D9");
        long fixer = createMember(pid, "fixer9", "修复人庚");
        DefectLibraryInfo d = createDefect(pid, null, null, fixer, 2);
        assertEquals(1, d.source(), "人工建单 source=1（TC-V3-03 AI/人可识别）");
        assertEquals(0, d.jiraSyncStatus(), "未配置 JIRA 项目=0 未同步/跳过（IT-V3-03 零变化面）");
        assertNull(d.jiraSyncError());

        DefectLibraryInfo got = defectLibraryService.get(d.id());
        assertEquals(1, got.source(), "详情视图同口径");

        jdbcTemplate.update("UPDATE tp_defect SET source = 2, jira_sync_status = 2,"
                + " jira_sync_error = 'JIRA 服务不可达（连接超时）' WHERE id = ?", d.id());
        DefectLibraryInfo auto = defectLibraryService.get(d.id());
        assertEquals(2, auto.source(), "自动建单标识 2");
        assertEquals(2, auto.jiraSyncStatus(), "失败待重试态透出");
        assertEquals("JIRA 服务不可达（连接超时）", auto.jiraSyncError(), "人话留痕透出");
    }

    @Test
    void test_update_with_optimistic_lock_and_conflict() {
        long pid = createProject("D3");
        long fixer = createMember(pid, "fixer3", "修复人丙");
        long newFixer = createMember(pid, "fixer3b", "修复人丁");
        DefectLibraryInfo d = createDefect(pid, null, null, fixer, 2);

        DefectLibraryInfo updated = defectLibraryService.update(1L, d.id(),
                new DefectUpdateInput("金额计算错误（已定位）", "浮点精度问题", 1, newFixer, null, "JIRA-101", d.version()));
        assertEquals("金额计算错误（已定位）", updated.title());
        assertEquals(1, updated.severity(), "P0");
        assertEquals(newFixer, updated.assigneeId());
        assertEquals(d.version() + 1, updated.version());

        ServiceException stale = assertThrows(ServiceException.class, () -> defectLibraryService.update(1L,
                d.id(), new DefectUpdateInput("并发修改", null, 2, fixer, null, null, d.version())));
        assertTrue(stale.getMessage().contains("已被他人修改"), stale.getMessage());

        ServiceException noVer = assertThrows(ServiceException.class, () -> defectLibraryService.update(1L,
                d.id(), new DefectUpdateInput("无版本", null, 2, fixer, null, null, null)));
        assertTrue(noVer.getMessage().contains("版本号不能为空"), noVer.getMessage());
    }

    @Test
    void test_transition_validation_illegal_and_missing_reason() {
        long pid = createProject("D4");
        long fixer = createMember(pid, "fixer4", "修复人戊");
        DefectLibraryInfo d = createDefect(pid, null, null, fixer, 2);
        final long dId = d.id();
        final int dVer = d.version();

        ServiceException jump = assertThrows(ServiceException.class, () -> defectLibraryService.transition(1L,
                dId, new DefectTransitionInput(3, null, dVer)));
        assertTrue(jump.getMessage().contains("非法状态流转"), jump.getMessage());

        ServiceException bad = assertThrows(ServiceException.class, () -> defectLibraryService.transition(1L,
                dId, new DefectTransitionInput(9, null, dVer)));
        assertTrue(bad.getMessage().contains("目标状态仅支持"), bad.getMessage());

        d = defectLibraryService.transition(fixer, d.id(), new DefectTransitionInput(2, null, d.version()));
        d = defectLibraryService.transition(fixer, d.id(), new DefectTransitionInput(3, null, d.version()));
        d = defectLibraryService.transition(1L, d.id(), new DefectTransitionInput(4, "复现", d.version()));
        final long closeId = d.id();
        final int closeVer = d.version();
        ServiceException noReason = assertThrows(ServiceException.class, () -> defectLibraryService.transition(1L,
                closeId, new DefectTransitionInput(5, null, closeVer)));
        assertTrue(noReason.getMessage().contains("关闭/重新打开必须填写原因"), noReason.getMessage());

        DefectLibraryInfo d2 = createDefect(pid, null, null, fixer, 2);
        d2 = defectLibraryService.transition(fixer, d2.id(), new DefectTransitionInput(2, null, d2.version()));
        final long d2Id = d2.id();
        final int staleVer = d2.version();
        jdbcTemplate.update("UPDATE tp_defect SET version = version + 5 WHERE id = ?", d2Id);
        ServiceException stale = assertThrows(ServiceException.class, () -> defectLibraryService.transition(fixer,
                d2Id, new DefectTransitionInput(3, null, staleVer)));
        assertTrue(stale.getMessage().contains("已被他人修改"), stale.getMessage());
    }

    @Test
    void test_list_filters_and_pagination() {
        long pid = createProject("D5");
        long fixer = createMember(pid, "fixer5", "修复人己");
        createDefect(pid, null, null, fixer, 1);
        createDefect(pid, null, null, fixer, 2);
        DefectLibraryInfo d3 = createDefect(pid, null, null, fixer, 3);
        defectLibraryService.transition(fixer, d3.id(), new DefectTransitionInput(2, null, d3.version()));

        PageResult<DefectLibraryInfo> all = defectLibraryService.list(1L,
                new DefectQuery(pid, null, null, null, null, null, 1, 10));
        assertEquals(3, all.total());

        PageResult<DefectLibraryInfo> p0 = defectLibraryService.list(1L,
                new DefectQuery(pid, null, 1, null, null, null, 1, 10));
        assertEquals(1, p0.total());
        assertEquals(1, p0.list().get(0).severity());

        PageResult<DefectLibraryInfo> inProgress = defectLibraryService.list(1L,
                new DefectQuery(pid, 2, null, null, null, null, 1, 10));
        assertEquals(1, inProgress.total());
        assertEquals(2, inProgress.list().get(0).status());

        PageResult<DefectLibraryInfo> byAssignee = defectLibraryService.list(1L,
                new DefectQuery(pid, null, null, fixer, null, null, 1, 10));
        assertEquals(3, byAssignee.total());

        PageResult<DefectLibraryInfo> kw = defectLibraryService.list(1L,
                new DefectQuery(pid, null, null, null, null, "金额", 1, 10));
        assertEquals(3, kw.total());

        PageResult<DefectLibraryInfo> none = defectLibraryService.list(1L,
                new DefectQuery(pid, null, null, null, null, "不存在的关键词xyz", 1, 10));
        assertEquals(0, none.total());
    }

    // ── Server-side sorting through a physical-column whitelist (table-feature baseline) ──

    /** Create a defect with an explicit title (records are immutable; sort fixtures need distinct titles). */
    private DefectLibraryInfo createDefectWithTitle(long projectId, long assigneeId, int severity, String title) {
        return defectLibraryService.create(1L, new DefectCreateInput(projectId, null, null,
                title, "排序夹具描述", severity, assigneeId, null, null));
    }

    @Test
    void test_list_sorts_by_whitelisted_columns_both_directions() {
        long pid = createProject("DSRT");
        long fixer = createMember(pid, "fixerd1", "排序修复人");
        // Insertion order deliberately differs from BOTH title orders and from the
        // severity order, so the id-DESC default cannot satisfy the assertions.
        createDefectWithTitle(pid, fixer, 3, "Z缺陷");
        createDefectWithTitle(pid, fixer, 1, "A缺陷");
        createDefectWithTitle(pid, fixer, 2, "M缺陷");

        PageResult<DefectLibraryInfo> asc = defectLibraryService.list(1L,
                new DefectQuery(pid, null, null, null, null, null, 1, 20, "title", "asc"));
        assertEquals(List.of("A缺陷", "M缺陷", "Z缺陷"),
                asc.list().stream().map(DefectLibraryInfo::title).toList(), "asc must reorder server-side");

        PageResult<DefectLibraryInfo> desc = defectLibraryService.list(1L,
                new DefectQuery(pid, null, null, null, null, null, 1, 20, "severity", "desc"));
        assertEquals(List.of("Z缺陷", "M缺陷", "A缺陷"),
                desc.list().stream().map(DefectLibraryInfo::title).toList(), "severity desc mirrors insertion order");

        // Default order (no sort requested) keeps the historical id-DESC behaviour.
        PageResult<DefectLibraryInfo> dflt = defectLibraryService.list(1L,
                new DefectQuery(pid, null, null, null, null, null, 1, 20));
        assertEquals(List.of("M缺陷", "A缺陷", "Z缺陷"),
                dflt.list().stream().map(DefectLibraryInfo::title).toList(), "default stays id DESC");
    }

    @Test
    void test_list_rejects_non_whitelisted_sort_key() {
        long pid = createProject("DSREJ");
        assertThrows(ServiceException.class,
                () -> defectLibraryService.list(1L,
                        new DefectQuery(pid, null, null, null, null, null, 1, 20, "id; DROP TABLE x", "asc")),
                "injection-shaped sort key must fail closed");
    }

    @Test
    void test_summary_by_case_merges_builtin_and_j2() {
        long pid = createProject("D6");
        long caseId = createCase(pid, "支付流程");
        long fixer = createMember(pid, "fixer6", "修复人庚");
        DefectLibraryInfo d1 = createDefect(pid, caseId, null, fixer, 1);
        DefectLibraryInfo d2 = createDefect(pid, caseId, null, fixer, 2);
        jdbcTemplate.update("INSERT INTO tp_case_defect (case_id, defect_key, defect_status, status, version, created_by) "
                + "VALUES (?, 'JIRA-201', 'open', 1, 1, 1), (?, 'JIRA-202', 'closed', 2, 1, 1)", caseId, caseId);

        DefectSummary summary = defectLibraryService.summaryByCase(caseId);
        assertEquals(4, summary.total(), "2 内置 + 2 外部");
        assertEquals(3, summary.openCount(), "内置 2 未关闭 + 外部 1 open");

        d1 = defectLibraryService.transition(fixer, d1.id(), new DefectTransitionInput(2, null, d1.version()));
        d1 = defectLibraryService.transition(fixer, d1.id(), new DefectTransitionInput(3, null, d1.version()));
        d1 = defectLibraryService.transition(1L, d1.id(), new DefectTransitionInput(4, "复现", d1.version()));
        defectLibraryService.transition(1L, d1.id(), new DefectTransitionInput(5, "通过", d1.version()));
        DefectSummary after = defectLibraryService.summaryByCase(caseId);
        assertEquals(2, after.openCount());
    }

    @Test
    void test_count_open_by_plan_for_exit_criteria() {
        long pid = createProject("D7");
        long caseId = createCase(pid, "结算用例");
        long caseId2 = createCase(pid, "对账用例");
        long planId = createPlan(pid);
        jdbcTemplate.update("INSERT INTO tp_plan_case (plan_id, case_id, status) VALUES (?, ?, 1), (?, ?, 1)",
                planId, caseId, planId, caseId2);
        long fixer = createMember(pid, "fixer7", "修复人辛");

        createDefect(pid, null, planId, fixer, 1);
        createDefect(pid, null, planId, fixer, 3);
        createDefect(pid, caseId2, null, fixer, 2);

        assertEquals(3, defectLibraryService.countOpenByPlan(planId, false));
        assertEquals(1, defectLibraryService.countOpenByPlan(planId, true), "仅 P0");

        DefectLibraryInfo p0 = defectLibraryService.list(1L, new DefectQuery(pid, null, 1, null, null, null, 1, 10))
                .list().get(0);
        p0 = defectLibraryService.transition(fixer, p0.id(), new DefectTransitionInput(2, null, p0.version()));
        p0 = defectLibraryService.transition(fixer, p0.id(), new DefectTransitionInput(3, null, p0.version()));
        p0 = defectLibraryService.transition(1L, p0.id(), new DefectTransitionInput(4, "复现", p0.version()));
        defectLibraryService.transition(1L, p0.id(), new DefectTransitionInput(5, "修复验证通过", p0.version()));
        assertEquals(2, defectLibraryService.countOpenByPlan(planId, false));
        assertEquals(0, defectLibraryService.countOpenByPlan(planId, true));
    }
}
