package com.sixbean.testpike.core.requirement.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.OptimisticLockingException;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseStepInput;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.coverage.api.dto.CoverageMatrixView;
import com.sixbean.testpike.core.coverage.domain.service.CoverageMatrixService;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.requirement.api.dto.RequirementCreateInput;
import com.sixbean.testpike.core.requirement.api.dto.RequirementInfo;
import com.sixbean.testpike.core.requirement.api.dto.RequirementQuery;
import com.sixbean.testpike.core.requirement.api.dto.RequirementUpdateInput;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Requirement version chain tests (optimistic lock, content-deduped snapshots,
 * soft delete and reader visibility). Runs against the real dev database - no mocks.
 * <p>Covers: create seeds v1 + first snapshot; no-op edit writes nothing (R28
 * idempotency); real edit bumps version and writes EDIT(1)/STATUS(2) snapshot;
 * stale expectedVersion refused with 40901 naming current and submitted baseline;
 * delete is soft (row stays for the version chain, change_type=3 snapshot) and
 * the row turns invisible on detail/list/matrix/trace/offline readers.
 * <p>Data discipline: fixtures self-create rows and clean up by collected ids;
 * factory tables are never TRUNCATED here.
 */
@SpringBootTest(classes = CoreApplication.class)
class RequirementVersionChainTest {

    @Autowired
    private RequirementService requirementService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private CaseService caseService;

    @Autowired
    private CoverageMatrixService coverageMatrixService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> projectIds = new ArrayList<>();
    private final List<Long> requirementIds = new ArrayList<>();
    private final List<Long> caseIds = new ArrayList<>();

    @AfterEach
    void cleanupCreatedRows() {
        for (long rid : requirementIds) {
            jdbcTemplate.update("DELETE FROM tp_case_requirement WHERE requirement_id = ?", rid);
            jdbcTemplate.update("DELETE FROM tp_requirement_version WHERE requirement_id = ?", rid);
            jdbcTemplate.update("DELETE FROM tp_requirement WHERE id = ?", rid);
        }
        for (long cid : caseIds) {
            jdbcTemplate.update("DELETE FROM tp_case_step WHERE case_id = ?", cid);
            jdbcTemplate.update("DELETE FROM tp_test_case WHERE id = ?", cid);
        }
        for (long pid : projectIds) {
            jdbcTemplate.update("DELETE FROM tp_user_project WHERE project_id = ?", pid);
            jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", pid);
        }
    }

    private String tag() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private long createProject() {
        String code = "alx" + tag();
        long pid = projectService.create(1L,
                new ProjectCreateInput("版本链项目" + code, code, "zh-CN", "P1", null));
        projectIds.add(pid);
        return pid;
    }

    private long createCase(long projectId) {
        CaseCreateInput input = new CaseCreateInput(projectId, "版本链用例" + tag(),
                "打开系统登录页", "登录成功进入首页", 1, 2, "web", 1, null, 1, null,
                List.of(new CaseStepInput(1, "输入账号密码", 0),
                        new CaseStepInput(2, "页面出现登录成功提示", 1)));
        long cid = caseService.create(1L, input).id();
        caseIds.add(cid);
        return cid;
    }

    private RequirementInfo createRequirement(long projectId, String title) {
        RequirementInfo info = requirementService.create(1L, new RequirementCreateInput(
                projectId, 3, "R-" + title, title, "验收标准：" + title, "3.1"));
        requirementIds.add(info.id());
        return info;
    }

    /** change_type list of the version rows, ordered by version ascending. */
    private List<Integer> changeTypes(long requirementId) {
        return jdbcTemplate.queryForList(
                "SELECT change_type FROM tp_requirement_version WHERE requirement_id = ? ORDER BY version",
                Integer.class, requirementId);
    }

    private String snapshotTitle(long requirementId, int version) {
        return jdbcTemplate.queryForObject(
                "SELECT snapshot_json ->> 'title' FROM tp_requirement_version "
                        + "WHERE requirement_id = ? AND version = ?",
                String.class, requirementId, version);
    }

    // ── create: version 1 seeded, first snapshot written ─────────────────────

    @Test
    void create_seedsVersionChainWithInitialSnapshot() {
        long pid = createProject();
        RequirementInfo info = createRequirement(pid, "登录功能");

        assertEquals(Integer.valueOf(1), info.version());
        assertEquals(List.of(1), changeTypes(info.id()));
        assertEquals("登录功能", snapshotTitle(info.id(), 1));
    }

    // ── content dedup / empty-save idempotency (R28) ─────────────────────────

    @Test
    void edit_thatChangesNothing_bumpsNoVersionAndWritesNoSnapshot() {
        long pid = createProject();
        RequirementInfo created = createRequirement(pid, "登录功能");
        int snapshotCountBefore = changeTypes(created.id()).size();

        RequirementInfo after = requirementService.update(1L, created.id(),
                new RequirementUpdateInput("登录功能", null, null,
                        "验收标准：登录功能", null, null, null));

        assertEquals(Integer.valueOf(1), after.version());
        assertEquals(snapshotCountBefore, changeTypes(created.id()).size());
        Integer rowVersion = jdbcTemplate.queryForObject(
                "SELECT version FROM tp_requirement WHERE id = ?", Integer.class, created.id());
        assertEquals(Integer.valueOf(1), rowVersion);
    }

    @Test
    void realEdit_incrementsVersionAndWritesEditSnapshot() {
        long pid = createProject();
        RequirementInfo created = createRequirement(pid, "登录功能");

        RequirementInfo after = requirementService.update(1L, created.id(),
                new RequirementUpdateInput("登录功能 v2", null, null, null, null, null, null));

        assertEquals(Integer.valueOf(2), after.version());
        assertEquals(List.of(1, 1), changeTypes(created.id()));
        assertEquals("登录功能 v2", snapshotTitle(created.id(), 2));
    }

    @Test
    void statusOnlyEdit_writesStatusChangeTypeSnapshot() {
        long pid = createProject();
        RequirementInfo created = createRequirement(pid, "报表导出");

        requirementService.update(1L, created.id(),
                new RequirementUpdateInput(null, null, null, null, null, 3, null));

        assertEquals(List.of(1, 2), changeTypes(created.id()));
    }

    // ── optimistic lock: 40901 names current version and submitted baseline ──

    @Test
    void staleExpectedVersion_isRejectedNamingCurrentAndSubmittedBaseline() {
        long pid = createProject();
        RequirementInfo created = createRequirement(pid, "对账导入");
        requirementService.update(1L, created.id(),
                new RequirementUpdateInput("对账导入 v2", null, null, null, null, null, null));

        OptimisticLockingException conflict = assertThrows(OptimisticLockingException.class,
                () -> requirementService.update(1L, created.id(),
                        new RequirementUpdateInput("对账导入 v2b", null, null, null, null, null, 1)));
        assertEquals(BizErrorCode.VERSION_CONFLICT, OptimisticLockingException.CODE);
        assertTrue(conflict.getMessage().contains("当前版本"),
                "conflict message must name the current version: " + conflict.getMessage());
        assertTrue(conflict.getMessage().contains("v2"),
                "conflict message must show current version number: " + conflict.getMessage());
        assertTrue(conflict.getMessage().contains("v1"),
                "conflict message must show the submitted baseline: " + conflict.getMessage());

        RequirementInfo untouched = requirementService.get(created.id());
        assertEquals(Integer.valueOf(2), untouched.version());
        assertEquals(2, changeTypes(created.id()).size());
    }

    @Test
    void absentExpectedVersion_stillAppliesAndIncrements() {
        long pid = createProject();
        RequirementInfo created = createRequirement(pid, "无锚更新");

        RequirementInfo after = requirementService.update(1L, created.id(),
                new RequirementUpdateInput("无锚更新 v2", null, null, null, null, null, null));

        assertEquals(Integer.valueOf(2), after.version());
    }

    // ── soft delete: row stays, chain keeps delete snapshot, readers hide it ─

    @Test
    void deleteWithLinks_isRefused_thenSoftDeleteAfterUnlinkWritesDeleteSnapshot() {
        long pid = createProject();
        RequirementInfo req = createRequirement(pid, "被关联需求");
        long caseId = createCase(pid);
        requirementService.linkCases(1L, req.id(), List.of(caseId));

        ServiceException refused = assertThrows(ServiceException.class,
                () -> requirementService.delete(1L, req.id()));
        assertTrue(refused.getMessage().contains("已被用例关联"), refused.getMessage());

        requirementService.unlinkCase(1L, req.id(), caseId);
        assertEquals(req.id(), requirementService.delete(1L, req.id()));

        Integer deletedFlag = jdbcTemplate.queryForObject(
                "SELECT deleted FROM tp_requirement WHERE id = ?", Integer.class, req.id());
        assertNotNull(deletedFlag, "row must physically remain for the version chain");
        assertEquals(Integer.valueOf(1), deletedFlag);
        int lastType = changeTypes(req.id()).get(changeTypes(req.id()).size() - 1);
        assertEquals(3, lastType, "delete writes a change_type=3 snapshot");

        ServiceException gone = assertThrows(ServiceException.class,
                () -> requirementService.get(req.id()));
        assertEquals(BizErrorCode.NOT_FOUND, gone.getCode());
    }

    @Test
    void softDeletedRequirement_isHiddenFromDetailListMatrixTraceAndOfflineReaders() {
        long pid = createProject();
        RequirementInfo live = createRequirement(pid, "在册需求");
        RequirementInfo removed = createRequirement(pid, "已删需求");
        requirementService.delete(1L, removed.id());

        assertThrows(ServiceException.class, () -> requirementService.get(removed.id()));
        PageResult<RequirementInfo> page = requirementService.list(
                new RequirementQuery(pid, null, null, 1, 50));
        List<Long> listed = page.list().stream().map(RequirementInfo::id).toList();
        assertTrue(listed.contains(live.id()));
        assertFalse(listed.contains(removed.id()));

        CoverageMatrixView view = coverageMatrixService.matrix(pid, null, null, null);
        assertTrue(view.uncoveredRequirementIds().contains(live.id()));
        assertFalse(view.uncoveredRequirementIds().contains(removed.id()));

        // Historical-link branch: a link row that predates the flag flip (defensive
        // predicate pinning - service delete refuses while linked, so reach for SQL).
        long caseId = createCase(pid);
        jdbcTemplate.update("INSERT INTO tp_case_requirement (case_id, requirement_id, created_by) "
                + "VALUES (?, ?, 1)", caseId, removed.id());
        jdbcTemplate.update("UPDATE tp_requirement SET status = 3 WHERE id = ?", removed.id());
        RequirementInfo offlineLive = createRequirement(pid, "下线在册需求");
        long caseId2 = createCase(pid);
        requirementService.linkCases(1L, offlineLive.id(), List.of(caseId2));
        requirementService.update(1L, offlineLive.id(),
                new RequirementUpdateInput(null, null, null, null, null, 3, null));

        List<Long> traced = requirementService.listByCaseId(caseId).stream()
                .map(RequirementInfo::id).toList();
        assertFalse(traced.contains(removed.id()), "trace chain must drop soft-deleted rows");

        List<Long> offlineCases = requirementService.offlineCaseIds(pid, List.of(caseId, caseId2));
        assertFalse(offlineCases.contains(caseId),
                "offline check must ignore links onto soft-deleted requirements");
        assertTrue(offlineCases.contains(caseId2));
    }

    @Test
    void linkCasesOntoSoftDeletedRequirement_isRejected() {
        long pid = createProject();
        RequirementInfo req = createRequirement(pid, "删后关联");
        long caseId = createCase(pid);
        requirementService.delete(1L, req.id());

        ServiceException gone = assertThrows(ServiceException.class,
                () -> requirementService.linkCases(1L, req.id(), List.of(caseId)));
        assertEquals(BizErrorCode.NOT_FOUND, gone.getCode());
    }
}
