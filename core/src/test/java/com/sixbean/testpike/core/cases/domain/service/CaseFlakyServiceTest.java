package com.sixbean.testpike.core.cases.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.api.dto.FlakyDetectionResult;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.support.ProjectFixtureRig;
import com.sixbean.testpike.core.support.SharedDbTruncate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class CaseFlakyServiceTest {

    @Autowired
    private CaseService caseService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Fixture projects of this suite; the teardown sweeps only these rows. */
    private final java.util.List<Long> flakyTrackedProjects =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Suite-owned actor on the high test band: the shared dev database's
     * factory administrator (id=1) is a notification recipient set and must
     * never be role-mutated by a suite (its ensure helper overwrites the
     * role column in place). */
    private static final long OPERATOR_ID = 9_312_001L;

    @BeforeEach
    void seedSuiteActor() {
        CaseTestMembers.ensureUserWithRole(jdbcTemplate, OPERATOR_ID, "admin");
    }

    @AfterEach
    void removeSuiteActor() {
        CaseTestMembers.dropUser(jdbcTemplate, OPERATOR_ID);
    }

    @BeforeEach
    @AfterEach
    void cleanTables() {
        // Concurrent writers on the shared dev database (the resident scheduler,
        // other chains' long transactions) can turn teardown into a deadlock
        // victim or an unbounded lock-wait — neither is a real regression. The
        // conversion swaps the cleanup channel from blanket TRUNCATE to the
        // row-scoped tracked-project FK sweep; the session lock budget and the
        // bounded 3-attempt backoff retry are unchanged, still owned by
        // SharedDbTruncate (rethrows with evidence once the budget is spent).
        SharedDbTruncate.runSweepWithLockBound(jdbcTemplate,
                jdbc -> ProjectFixtureRig.sweep(jdbc, flakyTrackedProjects));
        flakyTrackedProjects.clear();
    }

    private long createProject(String code) {
        return ProjectFixtureRig.createSelfHeal(jdbcTemplate, projectService, OPERATOR_ID,
                flakyTrackedProjects, "项目" + code, code);
    }

    private CaseInfo createCase(long pid, String title) {
        return caseService.create(OPERATOR_ID, new CaseCreateInput(pid, title, "前置", "预期",
                1, 2, "web", 3, 2, 1, null, List.of()));
    }

    
    private void seedExec(long projectId, long caseId, int status, int minutesAgo) {
        jdbcTemplate.update("""
                INSERT INTO tp_exec_task (project_id, case_id, status, finished_at, created_at)
                VALUES (?, ?, ?, now() - (? || ' minutes')::interval, now() - (? || ' minutes')::interval)
                """, projectId, caseId, status, minutesAgo, minutesAgo);
    }


    @Test
    void 一键隔离_写flaky与理由与时间_重复隔离幂等不叠加() {
        long pid = createProject("q3a");
        CaseInfo info = createCase(pid, "登录偶发失败");

        CaseInfo isolated = caseService.setFlaky(OPERATOR_ID, info.id(), true, "登录页偶发元素定位失败");
        assertEquals(1, isolated.flaky());
        assertEquals("登录页偶发元素定位失败", isolated.flakyReason());
        assertNotNull(isolated.flakyMarkedAt());

        CaseInfo again = caseService.setFlaky(OPERATOR_ID, info.id(), true, "改为：断言超时");
        assertEquals(1, again.flaky());
        assertEquals("改为：断言超时", again.flakyReason());

        Integer auditCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_audit_log WHERE action = 'flaky' AND object_id = ?",
                Integer.class, String.valueOf(info.id()));
        assertEquals(2, auditCount, "两次隔离动作各留一条审计");
    }

    @Test
    void 隔离理由缺省填人话默认_解除清空三字段() {
        long pid = createProject("q3b");
        CaseInfo info = createCase(pid, "偶发用例");

        CaseInfo isolated = caseService.setFlaky(OPERATOR_ID, info.id(), true, null);
        assertNotNull(isolated.flakyReason(), "空理由→默认人话文案");
        assertTrue(isolated.flakyReason().contains("不稳定"), "默认文案含「不稳定」: " + isolated.flakyReason());

        CaseInfo cleared = caseService.setFlaky(OPERATOR_ID, info.id(), false, null);
        assertEquals(0, cleared.flaky());
        assertNull(cleared.flakyReason(), "解除清空理由");
        assertNull(cleared.flakyMarkedAt(), "解除清空标记时间");
    }

 // detectFlaky is a global scan job; on the shared dev database its aggregate
 // counters are asserted as lower bounds only, and the exact expectations move
 // to this suite's per-project case state and flaky counts.

    @Test
    void 检测_翻转达标标记_理由落库_列表字段透出() {
        long pid = createProject("q3c");
        CaseInfo flakyCase = createCase(pid, "翻转用例");
        CaseInfo stableCase = createCase(pid, "稳定用例");
        seedExec(pid, flakyCase.id(), 3, 30);
        seedExec(pid, flakyCase.id(), 4, 20);
        seedExec(pid, flakyCase.id(), 3, 10);
        seedExec(pid, stableCase.id(), 3, 30);
        seedExec(pid, stableCase.id(), 3, 20);
        seedExec(pid, stableCase.id(), 3, 10);

        FlakyDetectionResult result = caseService.detectFlaky();
        assertTrue(result.scanned() >= 2, "聚合下界：本套件 2 条用例均在扫描集内");
        assertTrue(result.marked() >= 1, "聚合下界：翻转用例至少贡献 1 次新标记");
        assertEquals(1, flakyCountOf(pid), "精确账：本项目仅翻转用例被标记");

        CaseInfo marked = caseService.get(flakyCase.id());
        assertEquals(1, marked.flaky());
        assertNotNull(marked.flakyReason());
        assertTrue(marked.flakyReason().contains("2 次"), "理由含翻转次数: " + marked.flakyReason());
        assertNotNull(marked.flakyMarkedAt());
        assertEquals(0, caseService.get(stableCase.id()).flaky(), "稳定用例不标");
    }

    @Test
    void 检测重跑幂等_标记不叠加_不产新审计() {
        long pid = createProject("q3d");
        CaseInfo info = createCase(pid, "翻转用例");
        seedExec(pid, info.id(), 3, 30);
        seedExec(pid, info.id(), 4, 20);
        seedExec(pid, info.id(), 3, 10);

        assertTrue(caseService.detectFlaky().marked() >= 1, "聚合下界：首轮至少标记本用例");
        java.sql.Timestamp markedAtBefore = markedAtOf(info.id());
        caseService.detectFlaky();
        // Idempotency exact account (project-scoped): the rerun may refresh the
        // reason but never resets the mark time nor stacks a second mark.
        assertEquals(markedAtBefore, markedAtOf(info.id()), "重跑不重置标记时间（IT-Q3-01 幂等）");
        assertEquals(1, flakyCountOf(pid), "重跑后本项目 flaky 计数不叠加");
        assertEquals(1, caseService.get(info.id()).flaky(), "隔离态保持");
    }

    @Test
    void 已隔离用例_窗口翻转不足且一致不足5_保持隔离不建议() {
        long pid = createProject("q3e");
        CaseInfo info = createCase(pid, "曾翻转用例");
        caseService.setFlaky(OPERATOR_ID, info.id(), true, null);
        seedExec(pid, info.id(), 4, 50);
        seedExec(pid, info.id(), 4, 40);
        seedExec(pid, info.id(), 3, 30);
        seedExec(pid, info.id(), 3, 20);
        seedExec(pid, info.id(), 3, 10);

        String reasonBefore = caseService.get(info.id()).flakyReason();
        caseService.detectFlaky();
        // The global marked/suggestClear counters are not pinned to 0 (other
        // projects may contribute); the exact per-case account is that an
        // under-threshold window neither refreshes nor un-isolates the case.
        CaseInfo after = caseService.get(info.id());
        assertEquals(1, after.flaky(), "检测不自动解除（解除=人工动作）");
        assertEquals(reasonBefore, after.flakyReason(), "未达标窗口不刷新理由");
    }

    @Test
    void 已隔离用例_最近连续5次一致_计入建议解除并保持标记() {
        long pid = createProject("q3f");
        CaseInfo info = createCase(pid, "隔离后恢复用例");
        caseService.setFlaky(OPERATOR_ID, info.id(), true, null);
        for (int i = 5; i >= 1; i--) {
            seedExec(pid, info.id(), 3, i * 10);
        }

        FlakyDetectionResult result = caseService.detectFlaky();
        assertTrue(result.suggestClear() >= 1, "聚合下界：本用例连续 5 次一致贡献建议解除计数");
        assertEquals(1, caseService.get(info.id()).flaky(), "只建议不自动解除（IT-Q3-01）");
    }

    @Test
    void 样本不足窗口不标记_超时计失败侧() {
        long pid = createProject("q3g");
        CaseInfo few = createCase(pid, "仅两次执行");
        seedExec(pid, few.id(), 3, 20);
        seedExec(pid, few.id(), 4, 10);
        CaseInfo timeoutCase = createCase(pid, "超时翻转用例");
        seedExec(pid, timeoutCase.id(), 3, 30);
        seedExec(pid, timeoutCase.id(), 5, 20);
        seedExec(pid, timeoutCase.id(), 3, 10);

        FlakyDetectionResult result = caseService.detectFlaky();
        assertTrue(result.marked() >= 1, "聚合下界：超时翻转用例（3 样本 2 翻转）贡献标记");
        assertEquals(0, caseService.get(few.id()).flaky(), "样本 <3 不判");
        assertEquals(1, caseService.get(timeoutCase.id()).flaky(), "超时计入失败侧");
        assertEquals(1, flakyCountOf(pid), "精确账：本项目仅超时翻转用例被标记");
    }

    private int flakyCountOf(long projectId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_test_case WHERE project_id = ? AND flaky = 1 AND deleted = 0",
                Integer.class, projectId);
        return n == null ? 0 : n;
    }

    private java.sql.Timestamp markedAtOf(long caseId) {
        return jdbcTemplate.queryForObject(
                "SELECT flaky_marked_at FROM tp_test_case WHERE id = ?",
                java.sql.Timestamp.class, caseId);
    }
}
