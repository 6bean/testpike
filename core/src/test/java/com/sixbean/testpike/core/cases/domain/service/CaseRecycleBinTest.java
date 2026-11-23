package com.sixbean.testpike.core.cases.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.OptimisticLockingException;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.api.dto.CaseRecycleInfo;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class CaseRecycleBinTest {

    @Autowired
    private CaseService caseService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Fixture projects of this suite; the teardown sweeps only these rows. */
    private final java.util.List<Long> recycleBinTrackedProjects =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    @BeforeEach
    @AfterEach
    void cleanTables() {
        // Shared dev database (R37): no blanket table wipe — the tracked-project
        // FK sweep reaches this suite's cases, steps, requirements, case-requirement
        // links and audit rows through the tp_project/tp_requirement graph.
        ProjectFixtureRig.sweep(jdbcTemplate, recycleBinTrackedProjects);
        recycleBinTrackedProjects.clear();
    }

    private long createProject(String code) {
        return ProjectFixtureRig.createSelfHeal(jdbcTemplate, projectService, 1L,
                recycleBinTrackedProjects, "项目" + code, code);
    }

    private CaseInfo createCase(long projectId, String title) {
        return caseService.create(1L, new CaseCreateInput(projectId, title, "前置", "预期",
                1, 2, "web", null, null, null, null, null));
    }

    
    private long seedRelations(long caseId, long projectId) {
        jdbcTemplate.update("INSERT INTO tp_case_version (case_id, version, snapshot_json, change_type, operator_id)"
                + " VALUES (?, 99, '{}'::jsonb, 1, 1)", caseId);
        jdbcTemplate.update("INSERT INTO tp_review_log (case_id, user_id, action)"
                + " VALUES (?, 1, 'pass')", caseId);
        Long reqId = jdbcTemplate.queryForObject(
                "INSERT INTO tp_requirement (project_id, source, title, status, created_by)"
                        + " VALUES (?, 3, '级联断言需求点', 1, 1) RETURNING id", Long.class, projectId);
        jdbcTemplate.update("INSERT INTO tp_case_requirement (case_id, requirement_id, created_by)"
                + " VALUES (?, ?, 1)", caseId, reqId);
        return reqId;
    }


    @Test
    void recycleBin_软删用例可见_携带删除人与时间() {
        long pid = createProject("rcy");
        CaseInfo c = createCase(pid, "将被删除的用例");
        caseService.delete(7L, c.id());

        PageResult<CaseRecycleInfo> bin = caseService.recycleBin(pid, null, 1, 10);
        assertEquals(1L, bin.total(), "回收站只有 1 条软删用例");
        CaseRecycleInfo item = bin.list().get(0);
        assertEquals(c.id(), item.id());
        assertEquals(c.caseNo(), item.caseNo(), "case_no 保留不重编号（IT-P1-01）");
        assertEquals("将被删除的用例", item.title());
        assertEquals(7L, item.deletedBy(), "删除人=审计 delete 动作的操作人");
        assertNotNull(item.deletedAt(), "删除时间取自审计");
    }

    @Test
    void recycleBin_H1隔离_仅本项目_且正常列表不可见软删行() {
        long pidA = createProject("rcyA");
        long pidB = createProject("rcyB");
        CaseInfo a = createCase(pidA, "A项目用例");
        CaseInfo b = createCase(pidB, "B项目用例");
        caseService.delete(1L, a.id());
        caseService.delete(1L, b.id());

        PageResult<CaseRecycleInfo> binA = caseService.recycleBin(pidA, null, 1, 10);
        assertEquals(1L, binA.total());
        assertEquals(a.id(), binA.list().get(0).id(), "回收站只见本项目（H1 隔离）");

        assertEquals(0L, caseService.list(new com.sixbean.testpike.core.cases.api.dto.CaseQuery(
                pidA, null, null, null, null, null, null, null, null, null, 1, 10)).total(),
                "正常列表不含软删行");
    }

    @Test
    void restore_还原后列表详情恢复可见_version不重置_审计restore() {
        long pid = createProject("rcy");
        CaseInfo c = createCase(pid, "还原用例");
        int versionBefore = c.version();
        caseService.review(1L, c.id(), com.sixbean.testpike.core.cases.domain.component.ReviewAction.PASS,
                null, versionBefore);
        CaseInfo current = caseService.get(c.id());
        caseService.delete(1L, current.id());

        CaseInfo restored = caseService.restore(9L, c.id(), current.version());
        assertEquals(current.version(), restored.version(), "还原不 bump version（TC-P1-01）");
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT deleted FROM tp_test_case WHERE id = ?", Integer.class, c.id()));
        assertNotNull(caseService.get(c.id()));
        assertEquals(1L, caseService.list(new com.sixbean.testpike.core.cases.api.dto.CaseQuery(
                pid, null, null, null, null, null, null, null, null, null, 1, 10)).total());
        assertEquals(0L, caseService.recycleBin(pid, null, 1, 10).total());
        List<String> actions = jdbcTemplate.queryForList(
                "SELECT action FROM tp_audit_log WHERE object_type = 'case' AND object_id = ? ORDER BY id",
                String.class, String.valueOf(c.id()));
        assertTrue(actions.contains("restore"), "还原动作进审计: " + actions);
        Long restoreOperator = jdbcTemplate.queryForObject(
                "SELECT operator_id FROM tp_audit_log"
                        + " WHERE object_type = 'case' AND object_id = ? AND action = 'restore'",
                Long.class, String.valueOf(c.id()));
        assertEquals(9L, restoreOperator, "还原审计记录操作人（无请求头回退 operatorId 入参）");
    }


    @Test
    void restore_版本冲突_40901人话() {
        long pid = createProject("rcy");
        CaseInfo c = createCase(pid, "冲突用例");
        caseService.delete(1L, c.id());

        OptimisticLockingException ex = assertThrows(OptimisticLockingException.class,
                () -> caseService.restore(1L, c.id(), c.version() + 5));
        assertTrue(ex.getMessage().contains("版本"), "冲突提示人话: " + ex.getMessage());
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT deleted FROM tp_test_case WHERE id = ?", Integer.class, c.id()),
                "冲突后仍在回收站");
    }

    @Test
    void restore_未软删用例_404人话() {
        long pid = createProject("rcy");
        CaseInfo c = createCase(pid, "未删用例");

        ServiceException ex = assertThrows(ServiceException.class,
                () -> caseService.restore(1L, c.id(), c.version()));
        assertEquals(BizErrorCode.NOT_FOUND, ex.getCode());
    }


    @Test
    void purge_彻底删除_数据真删_级联关联行() {
        long pid = createProject("rcy");
        CaseInfo c = createCase(pid, "彻底删除用例");
        caseService.delete(1L, c.id());
        long reqId = seedRelations(c.id(), pid);
        assertEquals(1, countLinkRows(reqId), "软删不触发级联，关联行仍在");

        caseService.purge(1L, c.id());

        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_test_case WHERE id = ?", Integer.class, c.id()), "主行真删");
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_case_version WHERE case_id = ?", Integer.class, c.id()),
                "级联删版本快照（FK/P15 清单）");
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_review_log WHERE case_id = ?", Integer.class, c.id()),
                "级联删评审日志");
        assertEquals(0, countLinkRows(reqId), "purge 级联删需求关联行（无孤儿引用）");
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_case_requirement WHERE case_id = ?", Integer.class, c.id()));
        assertThrows(ServiceException.class, () -> caseService.get(c.id()));
    }

    private int countLinkRows(long requirementId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_case_requirement WHERE requirement_id = ?",
                Integer.class, requirementId);
        return n == null ? 0 : n;
    }

 
    @Test
    void 软删还原_需求关联保留_矩阵覆盖可恢复() {
        long pid = createProject("rcy2");
        CaseInfo c = createCase(pid, "还原保关联用例");
        long reqId = jdbcTemplate.queryForObject(
                "INSERT INTO tp_requirement (project_id, source, title, status, created_by)"
                        + " VALUES (?, 3, '关联需求点', 1, 1) RETURNING id", Long.class, pid);
        jdbcTemplate.update("INSERT INTO tp_case_requirement (case_id, requirement_id, created_by)"
                + " VALUES (?, ?, 1)", c.id(), reqId);

        caseService.delete(1L, c.id());
        caseService.restore(1L, c.id(), c.version());

        assertEquals(1, countLinkRows(reqId), "还原后关联行保留（覆盖恢复，TC-REQ-18）");
    }

    @Test
    void purge_未软删用例_404人话() {
        long pid = createProject("rcy");
        CaseInfo c = createCase(pid, "活跃用例");
        ServiceException ex = assertThrows(ServiceException.class, () -> caseService.purge(1L, c.id()));
        assertEquals(BizErrorCode.NOT_FOUND, ex.getCode());
        assertTrue(ex.getMessage().contains("回收站"), "提示人话: " + ex.getMessage());
    }

    /** Contract: the recycle-bin pointer appears only when a real delete record exists; a wrong path is a defect. */
    @Test
    void get_软删用例_删除账在才提回收站() {
        long pid = createProject("typ1");
        CaseInfo c = createCase(pid, "分型软删用例");
        caseService.delete(1L, c.id());
        ServiceException ex = assertThrows(ServiceException.class, () -> caseService.get(c.id()));
        assertEquals(BizErrorCode.NOT_FOUND, ex.getCode());
        assertTrue(ex.getMessage().contains("回收站"),
                "删除账在位时应给出回收站恢复指引: " + ex.getMessage());
        assertTrue(ex.getMessage().contains(String.valueOf(c.id())),
                "文案点名当前输入 id: " + ex.getMessage());
    }

    /** Contract: a sentinel id (row never existed or was purged) must not get the recycle-bin pointer. */
    @Test
    void get_哨兵id_无删除账不提回收站() {
        long goneId = 900000001L;
        ServiceException ex = assertThrows(ServiceException.class, () -> caseService.get(goneId));
        assertEquals(BizErrorCode.NOT_FOUND, ex.getCode());
        assertFalse(ex.getMessage().contains("回收站"),
                "无删除账时指回收站=假路，禁出现该指引: " + ex.getMessage());
        assertTrue(ex.getMessage().contains(String.valueOf(goneId)),
                "文案点名当前输入 id: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("用例库"),
                "修法必须给可达入口路径字面: " + ex.getMessage());
    }

    /** Contract: the delete path shares the same typed 404 for sentinel ids (single-source helper). */
    @Test
    void delete_哨兵id_无删除账不提回收站() {
        long goneId = 900000002L;
        ServiceException ex = assertThrows(ServiceException.class, () -> caseService.delete(1L, goneId));
        assertEquals(BizErrorCode.NOT_FOUND, ex.getCode());
        assertFalse(ex.getMessage().contains("回收站"),
                "无删除账时禁回收站指引: " + ex.getMessage());
        assertTrue(ex.getMessage().contains(String.valueOf(goneId)),
                "文案点名当前输入 id: " + ex.getMessage());
    }

    @Test
    void clearRecycleBin_清空仅本项目软删行() {
        long pidA = createProject("clA");
        long pidB = createProject("clB");
        CaseInfo a = createCase(pidA, "清空A");
        CaseInfo b = createCase(pidB, "清空B");
        CaseInfo b2 = createCase(pidB, "活跃B2");
        caseService.delete(1L, a.id());
        caseService.delete(1L, b.id());

        long cleared = caseService.clearRecycleBin(1L, pidA);
        assertEquals(1L, cleared);
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_test_case WHERE project_id = ?", Integer.class, pidA));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_test_case WHERE project_id = ? AND deleted = 1",
                Integer.class, pidB), "B 项目回收站不受影响");
        assertNotNull(caseService.get(b2.id()), "未软删的用例不受清空影响");
    }


    @Test
    void archived_列表仅归档项目_restore还原活跃() {
        long pid1 = createProject("ar1");
        long pid2 = createProject("ar2");
        projectService.archive(pid1);

        // The archived view is a global scan on the shared dev database (R37):
        // the aggregate is asserted as a lower bound and the exact expectation
        // moves to this suite's own rows (present with status=2, sibling absent).
        // A wide page keeps other suites' archived rows from pushing ours off it.
        PageResult<com.sixbean.testpike.core.project.api.dto.ProjectInfo> archived =
                projectService.listArchived(1, 500);
        assertTrue(archived.total() >= 1L, "aggregate lower bound: at least our archived row");
        com.sixbean.testpike.core.project.api.dto.ProjectInfo archivedRow = archived.list().stream()
                .filter(p -> p.id() == pid1).findFirst().orElseThrow(
                        () -> new AssertionError("本套件归档行不在列表"));
        assertEquals(2, archivedRow.status(), "归档视图携带 status=2");
        assertTrue(archived.list().stream().noneMatch(p -> p.id() == pid2), "未归档项目不在归档列表");

        projectService.restore(pid1);
        assertEquals(1, projectService.get(pid1).status(), "还原后恢复活跃");
        assertTrue(projectService.listArchived(1, 500).list().stream().noneMatch(p -> p.id() == pid1),
                "还原后归档列表不含本项目");

        ServiceException ex = assertThrows(ServiceException.class, () -> projectService.restore(pid2));
        assertEquals(BizErrorCode.NOT_FOUND, ex.getCode());
        assertTrue(ex.getMessage().contains("归档"), "提示人话: " + ex.getMessage());
    }


    @Test
    void recycleBin_存量软删行无delete审计_不炸且删除人为空() {
        long pid = createProject("legacy");
        CaseInfo c = createCase(pid, "存量软删");
        jdbcTemplate.update("UPDATE tp_test_case SET deleted = 1 WHERE id = ?", c.id());

        PageResult<CaseRecycleInfo> bin = caseService.recycleBin(pid, null, 1, 10);
        assertEquals(1L, bin.total());
        assertEquals(c.id(), bin.list().get(0).id());
        assertEquals(null, bin.list().get(0).deletedBy(), "无审计 delete 行删除人为空");
    }

    @Test
    void recycleBin_关键词筛选命中编号与标题() {
        long pid = createProject("kw");
        CaseInfo hit = createCase(pid, "登录超时用例");
        createCase(pid, "无关用例");
        caseService.delete(1L, hit.id());
        createCase(pid, "活跃不出现");

        PageResult<CaseRecycleInfo> bin = caseService.recycleBin(pid, "登录", 1, 10);
        assertEquals(1L, bin.total());
        assertEquals(hit.id(), bin.list().get(0).id());
    }

    @Test
    void recycleBin_彻底删除后审计保留_还原再删可查() {
        long pid = createProject("audit");
        CaseInfo c = createCase(pid, "审计保留");
        caseService.delete(1L, c.id());
        caseService.restore(1L, c.id(), c.version());
        caseService.delete(1L, c.id());

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT action, operator_id FROM tp_audit_log WHERE object_type = 'case' AND object_id = ?"
                        + " AND id = (SELECT max(id) FROM tp_audit_log WHERE object_type = 'case'"
                        + " AND object_id = ?)",
                String.valueOf(c.id()), String.valueOf(c.id()));
        assertEquals("delete", row.get("action"));
    }
}
