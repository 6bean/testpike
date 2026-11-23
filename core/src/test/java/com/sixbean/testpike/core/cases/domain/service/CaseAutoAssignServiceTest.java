package com.sixbean.testpike.core.cases.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cases.api.dto.AutoAssignRequest;
import com.sixbean.testpike.core.cases.api.dto.AutoAssignResultView;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.support.ProjectFixtureRig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class CaseAutoAssignServiceTest {

    @Autowired
    private CaseAutoAssignService autoAssignService;

    @Autowired
    private CaseService caseService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Fixture projects of this suite; the teardown sweeps only these rows. */
    private final java.util.List<Long> assignTrackedProjects =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    @BeforeEach
    @AfterEach
    void cleanTables() {
        // Shared dev database (R37): no blanket table wipe — the tracked-project
        // FK sweep purges this suite's cases, steps, tag links and audit rows.
        ProjectFixtureRig.sweep(jdbcTemplate, assignTrackedProjects);
        assignTrackedProjects.clear();
        jdbcTemplate.update("""
                DELETE FROM tp_user_role WHERE user_id BETWEEN 9300 AND 9399
                  AND role_id IN (SELECT id FROM tp_role WHERE code IN ('tester', 'test_lead', 'developer', 'admin'))
                """);
        jdbcTemplate.update("DELETE FROM tp_user WHERE id BETWEEN 9300 AND 9399");
    }

    private long createProject(String code) {
        return ProjectFixtureRig.createSelfHeal(jdbcTemplate, projectService, 1L,
                assignTrackedProjects, "项目" + code, code);
    }

    
    private long createUnassignedCase(long projectId, String title, int priority) {
        return caseService.create(1L, new CaseCreateInput(projectId, title, "前置", "预期",
                priority, 2, "web", 1, 1, 1, null,
                List.of())).id();
    }

    
    private void seedAssignedCase(long projectId, long assigneeId, long caseId) {
        jdbcTemplate.update("""
                INSERT INTO tp_test_case (id, case_no, project_id, title, priority, status,
                                          assignee_id, assigned_at, created_by, created_at, updated_at)
                OVERRIDING SYSTEM VALUE
                VALUES (?, ?, ?, ?, 1, 1, ?, now(), 1, now() - (? * interval '1 second'), now())
                """, caseId, "SEED-" + caseId, projectId, "在审单" + caseId, assigneeId, caseId);
    }

    private Map<Long, Integer> byAssigneeToMap(AutoAssignResultView view) {
        Map<Long, Integer> m = new HashMap<>();
        for (var row : view.byAssignee()) {
            m.put(row.userId(), row.count());
        }
        return m;
    }

    
    @Test
    void tcC401_deterministicBalancedGreedy() {
        long projectId = createProject("C4A");
        long userA = 9301;
        long userB = 9302;
        long userC = 9303;
        long dev = 9304;
        CaseTestMembers.addMemberWithRole(jdbcTemplate, projectId, userA, "tester");
        CaseTestMembers.addMemberWithRole(jdbcTemplate, projectId, userB, "tester");
        CaseTestMembers.addMemberWithRole(jdbcTemplate, projectId, userC, "test_lead");
        CaseTestMembers.addMemberWithRole(jdbcTemplate, projectId, dev, "developer");
        seedAssignedCase(projectId, userB, 70021);
        for (long id = 70031; id <= 70035; id++) {
            seedAssignedCase(projectId, userC, id);
        }
        long p0 = createUnassignedCase(projectId, "P0单", 0);
        long p1 = createUnassignedCase(projectId, "P1单", 1);
        long p2 = createUnassignedCase(projectId, "P2单", 2);

        AutoAssignResultView result = autoAssignService.autoAssign(1L,
                new AutoAssignRequest(projectId, List.of(p0, p1, p2), false));

        assertEquals(3, result.assignedCount());
        Map<Long, Integer> dist = byAssigneeToMap(result);
        assertEquals(Map.of(userA, 2, userB, 1), dist,
                "虚拟负载滚动：0载者连得 2（0→1 并列时历史总分配+userId 破平），第 3 条归 1 载者");
        List<Long> actual = jdbcTemplate.queryForList(
                "SELECT assignee_id FROM tp_test_case WHERE id IN (?, ?, ?) ORDER BY priority, id",
                Long.class, p0, p1, p2);
        assertEquals(List.of(userA, userA, userB), actual, "P0→A、P1→A、P2→B（确定性贪心）");
        Long devHits = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_test_case WHERE assignee_id = ?", Long.class, dev);
        assertEquals(0L, devHits, "开发角色永不入候选（K6）");
        Long cHits = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_test_case WHERE assignee_id = ?", Long.class, userC);
        assertEquals(5L, cHits, "5 载成员不沾新单");
    }

    
    @Test
    void tcC402_previewEqualsFormalAndDefaultScopeIdempotent() {
        long projectId = createProject("C4B");
        long userA = 9311;
        long userB = 9312;
        CaseTestMembers.addMemberWithRole(jdbcTemplate, projectId, userA, "tester");
        CaseTestMembers.addMemberWithRole(jdbcTemplate, projectId, userB, "tester");
        seedAssignedCase(projectId, userA, 70101);
        createUnassignedCase(projectId, "单1", 0);
        createUnassignedCase(projectId, "单2", 1);
        createUnassignedCase(projectId, "单3", 2);

        AutoAssignRequest preview = new AutoAssignRequest(projectId, null, true);
        AutoAssignResultView pre = autoAssignService.autoAssign(1L, preview);
        assertEquals(3, pre.assignedCount());
        Map<Long, Integer> preDist = byAssigneeToMap(pre);

        // Preview writes nothing: pin the count to this fixture project instead
        // of the global unassigned rows (shared dev db, R37 posture of the
        // already-converted case families).
        Long unassignedStill = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_test_case"
                        + " WHERE assignee_id IS NULL AND deleted = 0 AND project_id = ?",
                Long.class, projectId);
        assertEquals(3L, unassignedStill, "preview=true 不得写库");

        AutoAssignResultView formal = autoAssignService.autoAssign(1L,
                new AutoAssignRequest(projectId, null, false));
        assertEquals(pre.assignedCount(), formal.assignedCount(), "预览数=实派数");
        assertEquals(preDist, byAssigneeToMap(formal), "预览与实派逐人等值");

        AutoAssignResultView again = autoAssignService.autoAssign(1L, preview);
        assertEquals(0, again.assignedCount(), "二次调用 N=0（条件更新天然幂等）");
        assertTrue(again.byAssignee().isEmpty());
    }

 
    @Test
    void itC401_boundaries() {
        long projectId = createProject("C4C");
        long otherProject = createProject("C4D");
        long dev = 9321;
        CaseTestMembers.addMemberWithRole(jdbcTemplate, projectId, dev, "developer");
        long c1 = createUnassignedCase(projectId, "单1", 1);

        ServiceException noCandidate = assertThrows(ServiceException.class,
                () -> autoAssignService.autoAssign(1L, new AutoAssignRequest(projectId, null, false)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, noCandidate.getCode());
        assertTrue(noCandidate.getMessage().contains("项目内暂无可承接的测试成员"),
                "人话消息：" + noCandidate.getMessage());

        long foreign = createUnassignedCase(otherProject, "他项目单", 1);
        CaseTestMembers.addMemberWithRole(jdbcTemplate, projectId, 9322L, "tester");
        ServiceException cross = assertThrows(ServiceException.class,
                () -> autoAssignService.autoAssign(1L,
                        new AutoAssignRequest(projectId, List.of(c1, foreign), false)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, cross.getCode());
        assertTrue(cross.getMessage().contains("跨项目") || cross.getMessage().contains("不属于该项目"),
                "跨项目人话消息：" + cross.getMessage());

        seedAssignedCase(projectId, 9323L, 70201);
        AutoAssignResultView result = autoAssignService.autoAssign(1L,
                new AutoAssignRequest(projectId, List.of(c1, 70201L), false));
        assertEquals(1, result.assignedCount(), "已分配行跳过（单次胜出语义），不计入失败");
        Long won = jdbcTemplate.queryForObject(
                "SELECT assignee_id FROM tp_test_case WHERE id = 70201", Long.class);
        assertEquals(9323L, won, "已分配行的受理人不被覆盖");
    }

    
    @Test
    void itC401_batchOverFiveHundred() {
        long projectId = createProject("C4E");
        long userA = 9331;
        long userB = 9332;
        CaseTestMembers.addMemberWithRole(jdbcTemplate, projectId, userA, "tester");
        CaseTestMembers.addMemberWithRole(jdbcTemplate, projectId, userB, "tester");
        jdbcTemplate.update("""
                INSERT INTO tp_test_case (id, case_no, project_id, title, priority, status, created_at)
                OVERRIDING SYSTEM VALUE
                SELECT 80000 + g, 'BULK-' || g, ?, '批量单' || g, 1, 1, now()
                FROM generate_series(1, 503) g
                """, projectId);

        AutoAssignResultView result = autoAssignService.autoAssign(1L,
                new AutoAssignRequest(projectId, null, false));

        assertEquals(503, result.assignedCount());
        Map<Long, Integer> dist = byAssigneeToMap(result);
        assertEquals(2, dist.size(), "两成员均摊");
        assertTrue(Math.abs(dist.get(userA) - dist.get(userB)) <= 1,
                "均摊差≤1：" + dist);
        Long leftovers = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_test_case WHERE project_id = ? AND assignee_id IS NULL AND deleted = 0",
                Long.class, projectId);
        assertEquals(0L, leftovers, "分批事务不半写：无未分配残留");
    }
}
