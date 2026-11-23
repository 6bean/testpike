package com.sixbean.testpike.core.requirement.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseStepInput;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.config.api.dto.ConfigInput;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.requirement.api.dto.RequirementCreateInput;
import com.sixbean.testpike.core.requirement.api.dto.RequirementInfo;
import com.sixbean.testpike.core.requirement.api.dto.RequirementUpdateInput;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Server-computed case-gap list (generation pipeline behaviour kernel,
 * part 3 second half): requirements of one project that no active case
 * covers, paginated, behind the hot-reloadable ai.doc.case-gap-suggest switch.
 * <p>The covered/uncovered wording is pinned to the coverage-matrix semantics
 * (CoverageMatrixServiceImpl): a link counts only when the case row is alive
 * (deleted = 0) and not archived (status &lt;&gt; 9); retired requirements
 * (status = 3) are out of the list entirely.
 * <p>Real dev database, no mocks (strict-testing iron law). Fixtures create
 * their own project subtree and sweep by project id (R37: never TRUNCATE the
 * shipped baseline tables).
 */
@SpringBootTest(classes = CoreApplication.class)
class RequirementCaseGapListTest {

    @Autowired
    private RequirementService requirementService;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private CaseService caseService;
    @Autowired
    private ConfigService configService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> fixtureProjectIds = new ArrayList<>();

    /** Same text as the V378 factory seed description (fixture reset must not
     * introduce a second user-facing wording). */
    private static final String GAP_SWITCH_DESCRIPTION =
            "无用例需求点建议开关（默认开启；开启时可查询本项目尚无任何有效用例覆盖的需求点清单，"
                    + "用于一键补生成用例；关闭时查询被拒绝并提示在本系统设置中打开。修改即时生效，无需重启）";

    @AfterEach
    void cleanUpFixtures() {
        SharedDbFixtureSweep.byProjectList(jdbcTemplate, fixtureProjectIds);
        fixtureProjectIds.clear();
        // The switch row is factory-seeded baseline: reset to shipped "on",
        // never delete the row. Written through ConfigService so the in-memory
        // cache follows (raw SQL would leave a stale cached value — fixture
        // must replicate the real write shape, iron law 5e).
        Long id = jdbcTemplate.query(
                "SELECT id FROM tp_sys_config WHERE config_key = ?",
                rs -> rs.next() ? rs.getLong(1) : null,
                RequirementService.KEY_CASE_GAP_SUGGEST);
        if (id != null) {
            configService.update(1L, id, new ConfigInput(
                    RequirementService.KEY_CASE_GAP_SUGGEST, "true",
                    "系统", "bool", 0, GAP_SWITCH_DESCRIPTION));
        }
    }

    private long createProject(String tag) {
        String uniq = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        long id = projectService.create(1L, new ProjectCreateInput(
                "缺口项目" + tag + "-" + uniq,
                "gap-" + uniq,
                "zh-CN", "P1", null));
        fixtureProjectIds.add(id);
        return id;
    }

    private long createRequirement(long projectId, String title) {
        return requirementService.create(1L, new RequirementCreateInput(
                projectId, 3, "R-" + title, title, "验收标准：" + title, "3.1")).id();
    }

    private long createCase(long projectId, String title) {
        return caseService.create(1L, new CaseCreateInput(projectId, title,
                "打开系统登录页", "登录成功进入首页", 1, 2, "web", 1, null, 1, null,
                List.of(new CaseStepInput(1, "输入账号密码", 0)))).id();
    }

    private void upsertSwitch(String value) {
        Long id = jdbcTemplate.query(
                "SELECT id FROM tp_sys_config WHERE config_key = ?",
                rs -> rs.next() ? rs.getLong(1) : null,
                RequirementService.KEY_CASE_GAP_SUGGEST);
        ConfigInput input = new ConfigInput(RequirementService.KEY_CASE_GAP_SUGGEST,
                value, "系统", "bool", 0, GAP_SWITCH_DESCRIPTION);
        if (id != null) {
            configService.update(1L, id, input);
        } else {
            configService.create(1L, input);
        }
    }

    private Set<Long> idsOf(PageResult<RequirementInfo> page) {
        return page.list().stream().map(RequirementInfo::id).collect(Collectors.toSet());
    }

    @Test
    void uncoveredListKeepsOnlyRequirementsWithoutActiveCaseLink() {
        long pid = createProject("keep");
        long coveredA = createRequirement(pid, "登录功能");
        long coveredB = createRequirement(pid, "注册功能");
        long gapA = createRequirement(pid, "找回密码");
        long gapB = createRequirement(pid, "多因子认证");
        requirementService.linkCases(1L, coveredA, List.of(createCase(pid, "登录用例")));
        requirementService.linkCases(1L, coveredB, List.of(createCase(pid, "注册用例")));

        PageResult<RequirementInfo> gaps = requirementService.listUncovered(pid, 1, 20);

        assertEquals(2, gaps.total(), "only the two never-linked requirements are gaps");
        assertEquals(Set.of(gapA, gapB), idsOf(gaps));
    }

    @Test
    void archivedOrSoftDeletedCaseLinkLeavesRequirementUncovered() {
        long pid = createProject("arch");
        long byArchived = createRequirement(pid, "报表导出");
        long byDeleted = createRequirement(pid, "数据看板");
        long archivedCase = createCase(pid, "导出用例");
        long deletedCase = createCase(pid, "看板用例");
        requirementService.linkCases(1L, byArchived, List.of(archivedCase));
        requirementService.linkCases(1L, byDeleted, List.of(deletedCase));
        // Archive semantics are state-machine-guaranteed; SQL seed shape is
        // identical to the coverage-matrix suite (same wording anchor, R40).
        jdbcTemplate.update("UPDATE tp_test_case SET status = 9 WHERE id = ?", archivedCase);
        jdbcTemplate.update("UPDATE tp_test_case SET deleted = 1 WHERE id = ?", deletedCase);

        PageResult<RequirementInfo> gaps = requirementService.listUncovered(pid, 1, 20);

        assertEquals(2, gaps.total(), "archived/soft-deleted case links do not count");
        assertEquals(Set.of(byArchived, byDeleted), idsOf(gaps));
    }

    @Test
    void retiredRequirementsStayOutOfTheGapList() {
        long pid = createProject("retire");
        long activeGap = createRequirement(pid, "在线协助");
        long retired = createRequirement(pid, "旧版导入");
        requirementService.update(1L, retired, new RequirementUpdateInput(
                null, null, null, null, null, 3, null));

        PageResult<RequirementInfo> gaps = requirementService.listUncovered(pid, 1, 20);

        assertEquals(1, gaps.total(), "status=3 rows are out (coverage-matrix same scope)");
        assertEquals(Set.of(activeGap), idsOf(gaps));
    }

    @Test
    void gapListPaginatesAcrossProjectsWithoutLeakingOtherProjects() {
        long pid = createProject("page");
        List<Long> all = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            all.add(createRequirement(pid, "缺口需求" + i));
        }
        long otherPid = createProject("page-other");
        long foreignGap = createRequirement(otherPid, "他项目缺口");

        PageResult<RequirementInfo> first = requirementService.listUncovered(pid, 1, 2);
        PageResult<RequirementInfo> third = requirementService.listUncovered(pid, 3, 2);

        assertEquals(5, first.total());
        assertEquals(2, first.list().size(), "page one carries two rows");
        assertEquals(1, third.list().size(), "page three carries the remainder");
        assertFalse(idsOf(first).contains(foreignGap), "H1 isolation: no foreign rows");
        assertEquals(Set.of(all.get(4), all.get(3)), idsOf(first),
                "newest-first (highest id) slice on page one");
        assertEquals(Set.of(all.get(0)), idsOf(third), "oldest row lands on page three");
    }

    @Test
    void closedSwitchRefusesWithSettingsPointerAndKeyName() {
        long pid = createProject("switch");
        createRequirement(pid, "审计日志");
        upsertSwitch("false");

        ServiceException ex = assertThrows(ServiceException.class,
                () -> requirementService.listUncovered(pid, 1, 20));

        assertTrue(ex.getMessage().contains("系统设置"),
                "refusal must name where to open it: " + ex.getMessage());
        assertTrue(ex.getMessage().contains(RequirementService.KEY_CASE_GAP_SUGGEST),
                "refusal must name the config key: " + ex.getMessage());
        upsertSwitch("true");
        assertEquals(1, requirementService.listUncovered(pid, 1, 20).total(),
                "hot reload: flipping back on takes effect without restart");
    }

    @Test
    void emptyProjectHasNoGapsAndStaysGreen() {
        long pid = createProject("empty");

        PageResult<RequirementInfo> gaps = requirementService.listUncovered(pid, 1, 20);

        assertEquals(0, gaps.total());
        assertTrue(gaps.list().isEmpty());
    }
}
