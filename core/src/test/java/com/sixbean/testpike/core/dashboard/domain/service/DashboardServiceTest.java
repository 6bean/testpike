package com.sixbean.testpike.core.dashboard.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.dashboard.api.dto.DashboardOverview;
import com.sixbean.testpike.core.notification.api.dto.NotificationCreateInput;
import com.sixbean.testpike.core.notification.domain.service.NotificationService;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <p>R37 fixture-scoped teardown : the old blanket TRUNCATE wiped
 * factory-baseline projects, cases and notifications on the shared dev database.
 * Every viewer here is now a fresh {@code o7_} family account owning only this
 * suite's fixture projects, so owner/member branches assert absolute counts.
 * The two {@code allVisible} admin branches (999L/1L) see every project in the
 * database, so they switched to a before/after delta captured inside the test —
 * factory rows and other suites never enter the window arithmetic. The former
 * "no data at all" structure test (global empty lists under TRUNCATE) became a
 * fresh-account zero-family assertion, per the group-13 lesson.
 *
 */
@SpringBootTest(classes = CoreApplication.class)
class DashboardServiceTest {

    @Autowired
    private DashboardService dashboardService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Fixture-project ids tracked for the project-scoped sweep. */
    private final List<Long> trackedProjects = new ArrayList<>();

    @BeforeEach
    void healFamilyResidue() {
        purgeFamilyUsers();
    }

    @AfterEach
    void sweepRunRows() {
        SharedDbFixtureSweep.byProjectList(jdbcTemplate, trackedProjects);
        trackedProjects.clear();
        purgeFamilyUsers();
    }

    /**
     * Name-family purge for this suite's accounts: link tables first (FK into
     * tp_user), then the users themselves. Runs in both hooks so a crashed
     * run's leftover never blocks the fixed usernames on the next round.
     */
    private void purgeFamilyUsers() {
        jdbcTemplate.update("DELETE FROM tp_user_project WHERE user_id IN"
                + " (SELECT id FROM tp_user WHERE username LIKE 'o7_%')");
        jdbcTemplate.update("DELETE FROM tp_user_role WHERE user_id IN"
                + " (SELECT id FROM tp_user WHERE username LIKE 'o7_%')");
        jdbcTemplate.update("DELETE FROM tp_user WHERE username LIKE 'o7_%'");
    }

    private long createProject(long ownerUserId, String code) {
        return ProjectFixtureRig.createSelfHeal(jdbcTemplate, projectService, ownerUserId,
                trackedProjects, "项目" + code, code);
    }

    
    private void createCase(long projectId, int status, Long assigneeId) {
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO tp_test_case (case_no, project_id, title, status, assignee_id)
                VALUES ('O7-' || nextval('tp_test_case_id_seq'), ?, ?, ?, ?) RETURNING id
                """, Long.class, projectId, "O7用例", status, assigneeId);
        if (id == null) {
            throw new IllegalStateException("种用例失败");
        }
    }

    
    private void createAssignedCase(long projectId, int status, long assigneeId, int hoursAgo) {
        jdbcTemplate.update("""
                INSERT INTO tp_test_case (case_no, project_id, title, status, assignee_id, assigned_at)
                VALUES ('O7-' || nextval('tp_test_case_id_seq'), ?, ?, ?, ?,
                        now() - (? || ' hours')::interval)
                """, projectId, "O7用例", status, assigneeId, hoursAgo);
    }

    private void createTask(long projectId, long caseId, int status) {
        jdbcTemplate.update("INSERT INTO tp_exec_task (project_id, case_id, status) VALUES (?, ?, ?)",
                projectId, caseId, status);
    }

    private void createDefect(long projectId, int status, Long reporterId) {
        jdbcTemplate.update("INSERT INTO tp_defect (project_id, title, status, reporter_id) VALUES (?, ?, ?, ?)",
                projectId, "O7缺陷", status, reporterId);
    }

    private long lastCaseId(long projectId) {
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_test_case WHERE project_id = ? ORDER BY id DESC LIMIT 1",
                Long.class, projectId);
        return id != null ? id : -1L;
    }


    @Test
    void overviewAggregatesTodoCountsAndProjectStats() {
        long owner = insertUser("o7_owner");
        long p = createProject(owner, "O7Alpha");
        long p2 = createProject(owner, "O7Beta");
        createCase(p, 1, owner);
        createCase(p, 1, owner);
        createCase(p, 2, owner);
        createCase(p, 1, null);
        createCase(p, 3, owner);
        long c = lastCaseId(p);
        createTask(p, c, 1);
        createTask(p, c, 2);
        createTask(p, c, 3);
        createDefect(p, 1, owner);
        createDefect(p, 2, owner);
        createDefect(p, 6, owner);
        createDefect(p, 5, owner);
        for (int i = 1; i <= 6; i++) {
            notificationService.create(new NotificationCreateInput(
                    owner, p, "AI_GEN_DONE", "动态" + i, "内容", "o7:gen:" + i));
        }

        DashboardOverview r = dashboardService.overview(owner, false);
        assertEquals(4, r.pendingReviewCount(), "待评审=分配给我 3 + 未分配噪声 1（v2.46 方案 A 负责人计入未分配；o7_owner=项目负责人）");
        assertEquals(1, r.unassignedReviewCount(), "未分配提示行=1");
        assertEquals(2, r.runningTaskCount(), "执行中任务=status 1/2（2 条）");
        assertEquals(3, r.unclosedDefectCount(), "未关闭缺陷=status≠5（含重新打开）");
        assertEquals(2, r.projects().size(), "项目列表=可见项目");
        assertEquals(5, caseStat(r, "O7Alpha").caseCount(), "O7Alpha 用例数 5（含未分配/已通过，不含软删）");
        assertEquals(0, caseStat(r, "O7Beta").caseCount(), "O7Beta 用例数 0");
        assertEquals(5, r.activities().size(), "动态上限 5 条");
        assertEquals("动态6", r.activities().get(0).title(), "动态按新→旧");
    }


    @Test
    void overviewCountsUnassignedReviewForProjectOwner() {
        // Delta baseline for the allVisible admin branch BEFORE seeding: that
        // view scans every project in the shared database, so absolute counts
        // depend on factory rows; only the increment this test introduces is
        // stable.
        long adminBase = dashboardService.overview(999L, true).pendingReviewCount();
        long owner = insertUser("o7_unassigned_owner");
        long p = createProject(owner, "O7Unassigned");
        // 3 unassigned (implicitly visible to the owner) + 1 assigned to me
        // + 1 assigned to someone else (not counted)
        createCase(p, 1, null);
        createCase(p, 2, null);
        createCase(p, 1, null);
        createCase(p, 1, owner);
        createCase(p, 1, 2L);
        DashboardOverview r = dashboardService.overview(owner, false);
        assertEquals(4, r.pendingReviewCount(),
                "待评审=分配给我(1) + 未分配且我可见待审(3)；分配给他人的不计");
        assertEquals(3, r.unassignedReviewCount(), "提示行未分配数=3（负责人口径）");
        // admin (allVisible) = every project visible: this run's 3 unassigned
        // rows enter the delta, rows assigned to others do not
        DashboardOverview adminView = dashboardService.overview(999L, true);
        assertEquals(adminBase + 3, adminView.pendingReviewCount(),
                "系统管理员增量=未分配全部计入（他人已分配不算我的）");
    }

    @Test
    void unassignedReviewNotCountedForPlainMember() {
        long ownerB = insertUser("o7_owner_b");
        long p = createProject(ownerB, "O7MemberView");
        long plainMember = insertUser("o7_plain_member");
        jdbcTemplate.update("INSERT INTO tp_user_project (project_id, user_id, role) VALUES (?, ?, 1)", p, plainMember);
        createCase(p, 1, null);
        createCase(p, 1, plainMember);
        DashboardOverview memberView = dashboardService.overview(plainMember, false);
        assertEquals(1, memberView.pendingReviewCount(), "普通成员卡片=仅分配给我（未分配不进我的账）");
        DashboardOverview ownerView = dashboardService.overview(ownerB, false);
        assertEquals(1, ownerView.pendingReviewCount(), "负责人=可见项目未分配数+分配给我数（0+1）");
        assertEquals(1, ownerView.unassignedReviewCount(), "负责人未分配提示=1");
        assertEquals(0, memberView.unassignedReviewCount(), "成员未分配提示=0");
    }


    @Test
    void overviewCountsMyOverdueReviewCases72h() {
        long owner = insertUser("o7_owner");
        long p = createProject(owner, "O7Q4");
        createAssignedCase(p, 1, owner, 80);
        createAssignedCase(p, 2, owner, 100);
        createAssignedCase(p, 1, owner, 71);
        createAssignedCase(p, 3, owner, 90);
        createAssignedCase(p, 1, 9555L, 90);
        createCase(p, 1, null);

        DashboardOverview r = dashboardService.overview(owner, false);
        assertEquals(2, r.overdueReviewCount(), "角标=分配给我且 status 1/2 且分配超 72h");
        assertEquals(4, r.pendingReviewCount(), "待评审=分配给我三条 + 未分配 1（v2.46 方案 A）；超时与否不影响总数");
    }


    @Test
    void overviewIsolatedToVisibleProjects() {
        // Delta baselines before seeding for the allVisible branch below.
        long allBase = dashboardService.overview(1L, true).pendingReviewCount();
        long allProjectBase = dashboardService.overview(1L, true).projects().size();
        long pa = createProject(1L, "O7SecretA");
        long pb = createProject(1L, "O7SecretB");
        long outsider = insertUser("o7_outsider");
        long memberB = insertUser("o7_memberB");
        jdbcTemplate.update("INSERT INTO tp_user_project (project_id, user_id, role) VALUES (?, ?, 1)", pb, memberB);
        createCase(pa, 1, 1L);
        createCase(pb, 2, memberB);
        long cb = lastCaseId(pb);
        createTask(pb, cb, 2);
        createDefect(pb, 1, 999L);

        DashboardOverview none = dashboardService.overview(outsider, false);
        assertEquals(0, none.pendingReviewCount());
        assertEquals(0, none.runningTaskCount());
        assertEquals(0, none.unclosedDefectCount());
        assertTrue(none.projects().isEmpty(), "非成员项目列表为空");

        DashboardOverview r = dashboardService.overview(memberB, false);
        assertEquals(1, r.runningTaskCount(), "仅统计可见项目任务");
        assertEquals(1, r.unclosedDefectCount(), "仅统计可见项目缺陷");
        assertEquals(1, r.pendingReviewCount(), "待评审=分配给我且在可见项目（B 项目 status2 一条）");
        assertEquals(1, r.projects().size());
        assertEquals("O7SecretB", r.projects().get(0).code());

        // allVisible=true (system admin / test lead) → the increment contains
        // project A data; on the shared database absolute counts include the
        // factory baseline, so assertions run on before/after deltas
        DashboardOverview all = dashboardService.overview(1L, true);
        assertEquals(allBase + 1, all.pendingReviewCount(),
                "增量=A 项目分配给 1 的待评审 1 条");
        assertEquals(allProjectBase + 2, all.projects().size(), "项目总数增量=本轮 2 个新项目");
        List<String> allCodes = all.projects().stream()
                .map(com.sixbean.testpike.core.dashboard.api.dto.ProjectStat::code).toList();
        assertTrue(allCodes.containsAll(List.of("O7SecretA", "O7SecretB")),
                "全量视图含本轮两项目: " + allCodes);
    }

    private com.sixbean.testpike.core.dashboard.api.dto.ProjectStat caseStat(
            DashboardOverview r, String code) {
        return r.projects().stream().filter(s -> code.equals(s.code())).findFirst().orElseThrow();
    }

    private long insertUser(String username) {
        jdbcTemplate.update("DELETE FROM tp_user_project WHERE user_id IN"
                + " (SELECT id FROM tp_user WHERE username = ?)", username);
        jdbcTemplate.update("DELETE FROM tp_user_role WHERE user_id IN"
                + " (SELECT id FROM tp_user WHERE username = ?)", username);
        jdbcTemplate.update("DELETE FROM tp_user WHERE username = ? AND username LIKE 'o7_%'", username);
        Long uid = jdbcTemplate.queryForObject("""
                INSERT INTO tp_user (username, password_hash, display_name, role, status)
                VALUES (?, '$2a$10$x', ?, 1, 1) RETURNING id
                """, Long.class, username, username);
        return uid != null ? uid : -1L;
    }

    @Test
    void zeroDataStillReturnsStructure() {
        // Fresh non-member account: structure complete (empty lists, not null;
        // counts 0) — front-end card rendering depends on it. The old shape
        // guaranteed "no data" via a blanket TRUNCATE; on the shared database
        // this is now a zero-rows-for-this-family assertion (group-13 lesson).
        long freshUser = insertUser("o7_zero");
        DashboardOverview r = dashboardService.overview(freshUser, false);
        assertEquals(0, r.pendingReviewCount());
        assertEquals(List.of(), r.projects());
        assertEquals(List.of(), r.activities());
    }
}
