package com.sixbean.testpike.core.cases.domain.service;

import com.sixbean.testpike.common.exception.OptimisticLockingException;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.api.dto.CaseQuery;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.support.ProjectFixtureRig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class CaseExcellentTest {

    @Autowired
    private CaseService caseService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Fixture projects of this suite; the teardown sweeps only these rows. */
    private final java.util.List<Long> trackedProjects =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    @BeforeEach
    @AfterEach
    void cleanFixtureRows() {
        // Shared dev database (R37): purge only the rows reachable from this
        // run's tracked fixture projects (cases, steps, versions, exec tasks,
        // scripts, audit) — never a whole-table wipe of shared tables.
        ProjectFixtureRig.sweep(jdbcTemplate, trackedProjects);
        trackedProjects.clear();
    }

    private long createProject(String code) {
        // Same-code self-heal drops a crashed run's leftover before creating,
        // so the unique key never collides without touching other suites' rows.
        return ProjectFixtureRig.createSelfHeal(jdbcTemplate, projectService, 1L,
                trackedProjects, "项目" + code, code);
    }

    
    private CaseInfo createCase(long pid, String title, int status) {
        CaseTestMembers.ensureUserWithRole(jdbcTemplate, 1L, "admin");
        CaseInfo info = caseService.create(1L, new CaseCreateInput(pid, title, "前置", "预期",
                1, 2, "web", 3, 2, 1, null, List.of()));
        jdbcTemplate.update("UPDATE tp_test_case SET status = ? WHERE id = ?", status, info.id());
        return caseService.get(info.id());
    }

    @Test
    void 标记优秀_version加1_列落库_审计excellent_mark留痕() {
        long pid = createProject("d3a");
        CaseInfo info = createCase(pid, "优秀候选", 3);

        CaseInfo marked = caseService.markExcellent(1L, info.id(), true, info.version());
        assertEquals(1, marked.excellent(), "回显 excellent=1");
        assertEquals(info.version() + 1, marked.version(), "乐观锁 version+1");

        Integer dbFlag = jdbcTemplate.queryForObject(
                "SELECT excellent_flag FROM tp_test_case WHERE id = ?", Integer.class, info.id());
        assertEquals(1, dbFlag, "落库持久化（reload 语义=直查库）");

        Integer auditCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_audit_log WHERE action = 'excellent-mark' AND object_id = ?",
                Integer.class, String.valueOf(info.id()));
        assertEquals(1, auditCount, "标记动作审计留痕");
    }

    @Test
    void 非已通过标记_40001人话含状态label() {
        long pid = createProject("d3b");
        CaseInfo draft = createCase(pid, "待评审用例", 1);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> caseService.markExcellent(1L, draft.id(), true, draft.version()));
        assertTrue(ex.getMessage().contains("仅已通过用例可标记优秀"), "人话文案: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("待评审"), "含当前状态 label: " + ex.getMessage());
        assertEquals(0, caseService.get(draft.id()).excellent(), "被拒不落标");
    }

    @Test
    void 取消标记不限状态_回退待评审后仍可取消() {
        long pid = createProject("d3c");
        CaseInfo info = createCase(pid, "先标后取消", 3);
        CaseInfo marked = caseService.markExcellent(1L, info.id(), true, info.version());

        jdbcTemplate.update("UPDATE tp_test_case SET status = 1 WHERE id = ?", info.id());
        CaseInfo unmarked = caseService.markExcellent(1L, info.id(), false, marked.version());
        assertEquals(0, unmarked.excellent(), "取消不限态（TC-D3-02）");

        Integer auditCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_audit_log WHERE action = 'excellent-unmark' AND object_id = ?",
                Integer.class, String.valueOf(info.id()));
        assertEquals(1, auditCount, "取消动作审计 action=excellent-unmark（双值手记）");
    }

    @Test
    void 过期version乐观锁_409人话含当前版本() {
        long pid = createProject("d3d");
        CaseInfo info = createCase(pid, "并发冲突", 3);

        OptimisticLockingException ex = assertThrows(OptimisticLockingException.class,
                () -> caseService.markExcellent(1L, info.id(), true, info.version() + 99));
        assertTrue(ex.getMessage().contains("版本冲突"), "乐观锁文案: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("当前版本"), "含当前版本提示: " + ex.getMessage());
    }

    @Test
    void excellentOnly筛选_AND组合与回显() {
        long pid = createProject("d3e");
        CaseInfo good = createCase(pid, "优秀用例", 3);
        createCase(pid, "普通用例", 3);
        caseService.markExcellent(1L, good.id(), true, good.version());

        CaseQuery excellentOnly = new CaseQuery(pid, null, null, null, null, null, null,
                null, null, null, 1, 10, true);
        PageResult<CaseInfo> page = caseService.list(excellentOnly, null);
        assertEquals(1, page.total(), "仅优秀命中 1 条");
        assertEquals(1, page.list().get(0).excellent(), "列表回显 excellent 字段");

        CaseQuery combined = new CaseQuery(pid, null, "不存在关键词", null, null, null, null,
                null, null, null, 1, 10, true);
        assertEquals(0, caseService.list(combined, null).total(), "AND 组合收敛");
    }
}
