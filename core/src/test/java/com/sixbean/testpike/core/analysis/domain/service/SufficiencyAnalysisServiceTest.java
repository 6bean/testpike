package com.sixbean.testpike.core.analysis.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.analysis.api.dto.AssertionDensityView;
import com.sixbean.testpike.core.analysis.api.dto.CoverageSummaryView;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.api.dto.CaseStepInput;
import com.sixbean.testpike.core.cases.api.dto.CaseUpdateInput;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import com.sixbean.testpike.core.coverage.api.dto.CoverageMatrixView;
import com.sixbean.testpike.core.coverage.domain.service.CoverageMatrixService;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.requirement.api.dto.RequirementCreateInput;
import com.sixbean.testpike.core.requirement.domain.service.RequirementService;
import com.sixbean.testpike.core.tool.domain.component.ToolExecKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Read-only analysis service spectrum .
 * <p>Covers: the new ANALYSIS exec kind (enum value domain + DB CHECK), the
 * requirement-coverage projection over the J4 matrix single source (chapter
 * narrowing, uncovered-title cap, exit-gate key pass-through incl. the honest
 * "no contract yet" message) and the assertion-density calculation over the
 * latest version of active cases (archived cases excluded, per-case rows).
 * <p>Fixture hygiene: rows are created through services and removed by id —
 * factory tables are never truncated here.
 */
@SpringBootTest(classes = CoreApplication.class)
class SufficiencyAnalysisServiceTest {

    @Autowired
    private SufficiencyAnalysisService sufficiencyAnalysisService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private CaseService caseService;

    @Autowired
    private RequirementService requirementService;

    @Autowired
    private CoverageMatrixService coverageMatrixService;

    @Autowired
    private ConfigCacheHolder configCacheHolder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> projectIds = new ArrayList<>();

    @BeforeEach
    void cleanPreviousFixtures() {
        purgeFixtures();
    }

    // ---------- ANALYSIS exec kind (enum + DB value domain) ----------

    @Test
    void analysisExecKind_isPartOfTheValueDomain() {
        ToolExecKind analysis = ToolExecKind.valueOf("ANALYSIS");
        assertEquals(5, analysis.code());
        assertEquals("只读分析函数", analysis.label());
        assertEquals(analysis, ToolExecKind.of(5));
        assertTrue(ToolExecKind.allLabels().contains("只读分析函数"),
                "400 value-range string must cover the new kind");
        assertNull(ToolExecKind.of(6), "6 stays out of the domain");
    }

    @Test
    void toolTable_acceptsExecKindFive_rejectsSix() {
        long id5 = insertTool(5);
        assertTrue(id5 > 0, "CHECK constraint must allow the ANALYSIS kind (5)");
        assertThrows(DataAccessException.class, () -> insertTool(6),
                "values beyond the domain must still be refused by the DB");
        jdbcTemplate.update("DELETE FROM tp_tool WHERE id = ?", id5);
    }

    // ---------- coverage summary ----------

    @Test
    void coverageSummary_projectsMatrixCountsAndCap() {
        long pid = newProject("SA1");
        long reqCovered1 = newRequirement(pid, "登录超时重定向", "认证");
        long reqCovered2 = newRequirement(pid, "令牌刷新", "认证");
        newRequirement(pid, "未覆盖一", "认证");
        newRequirement(pid, "未覆盖二", "报表");
        long caseId = newCase(pid, "登录用例", "web", new Object[][]{{"点击登录", 1}});
        linkCase(caseId, List.of(reqCovered1, reqCovered2));

        CoverageSummaryView all = sufficiencyAnalysisService.coverageSummary(pid, null, null);
        assertEquals(4, all.total());
        assertEquals(2, all.covered());
        assertEquals(2, all.uncoveredTitles().size());
        assertFalse(all.truncated());
        assertFalse(all.gateEnabled());
        assertEquals(0, all.minApitestCoverage());
        assertEquals(-1, all.apitestCoveragePercent());
        assertNull(all.gateMessage(), "gate off must stay silent, never a fake verdict");

        CoverageSummaryView chapter = sufficiencyAnalysisService.coverageSummary(pid, "报表", null);
        assertEquals(1, chapter.total());
        assertEquals(0, chapter.covered());
        assertEquals(List.of("未覆盖二"), chapter.uncoveredTitles());

        long caseId2 = newCase(pid, "报表用例", "multi", new Object[][]{{"查询报表", 1}});
        long reqApi = newRequirement(pid, "报表导出", "报表");
        linkCase(caseId2, List.of(reqApi));
        // single-source contract: the projection mirrors whatever the J4 matrix
        // returns for the same ticket — no second coverage semantics here
        CoverageMatrixView matrixMulti = coverageMatrixService.matrix(pid, null, null, "multi");
        CoverageSummaryView multiOnly = sufficiencyAnalysisService.coverageSummary(pid, null, "multi");
        assertEquals(matrixMulti.totalRequirements(), multiOnly.total());
        assertEquals(matrixMulti.coveredRequirements(), multiOnly.covered());
    }

    @Test
    void coverageSummary_capsUncoveredTitlesAtFifty() {
        long pid = newProject("SA2");
        for (int i = 1; i <= 60; i++) {
            newRequirement(pid, "未覆盖需求" + i, "批量");
        }
        CoverageSummaryView view = sufficiencyAnalysisService.coverageSummary(pid, null, null);
        assertEquals(60, view.total());
        assertEquals(0, view.covered());
        assertEquals(50, view.uncoveredTitles().size(), "worst-scale cap for conversation rendering");
        assertTrue(view.truncated());
    }

    @Test
    void coverageSummary_projectsExitGateKey_andHonestNoContractMessage() {
        long pid = newProject("SA3");
        newRequirement(pid, "孤立需求", "认证");
        try {
            configCacheHolder.put("plan.exit.apitest.min-coverage", "80");
            CoverageSummaryView view = sufficiencyAnalysisService.coverageSummary(pid, null, null);
            assertTrue(view.gateEnabled());
            assertEquals(80, view.minApitestCoverage());
            assertEquals(-1, view.apitestCoveragePercent());
            assertNotNull(view.gateMessage());
        } finally {
            configCacheHolder.remove("plan.exit.apitest.min-coverage");
        }
    }

    // ---------- assertion density ----------

    @Test
    void assertionDensity_countsLatestVersionOfActiveCases() {
        long pid = newProject("SA4");
        newCase(pid, "密度A", "web", new Object[][]{{"操作1", 0}, {"断言1", 1}, {"断言2", 1}});
        newCase(pid, "密度B", "web", new Object[][]{{"操作2", 0}, {"断言3", 1}});
        long zero = newCase(pid, "零断言C", "web", new Object[][]{{"操作3", 0}, {"操作4", 0}});
        long archived = newCase(pid, "归档D", "web", new Object[][]{{"断言4", 1}});
        jdbcTemplate.update("UPDATE tp_test_case SET status = 9 WHERE id = ?", archived);

        AssertionDensityView view = sufficiencyAnalysisService.assertionDensity(pid, null);
        assertEquals(3, view.caseCount(), "archived case leaves the scope");
        assertEquals(7, view.totalSteps());
        assertEquals(3, view.assertionSteps());
        assertEquals(3.0 / 7.0, view.density(), 1e-6);
        assertEquals(1, view.zeroAssertCases());
        assertEquals(3, view.perCase().size());
        assertTrue(view.perCase().stream().noneMatch(r -> r.caseId() == archived),
                "per-case rows keep the same active scope");
        var zeroRow = view.perCase().stream().filter(r -> r.caseId() == zero).findFirst().orElseThrow();
        assertEquals(0, zeroRow.assertionSteps());

        // latest-version rule: dropping the assertions in v2 must move the aggregate
        caseService.update(1L, zero,
                new CaseUpdateInput("零断言C", null, null, 1, 2, "web", null,
                        List.of(new CaseStepInput(1, "操作5", 0), new CaseStepInput(2, "操作6", 0),
                                new CaseStepInput(3, "操作7", 0)),
                        null, null, null, null, null),
                1);
        AssertionDensityView after = sufficiencyAnalysisService.assertionDensity(pid, null);
        assertEquals(8, after.totalSteps(), "v1 steps must not stay in the denominator");
        assertEquals(3, after.assertionSteps());

        AssertionDensityView multiOnly = sufficiencyAnalysisService.assertionDensity(pid, "multi");
        assertEquals(0, multiOnly.caseCount(), "end-type filter narrows the same active scope");
    }

    // ---------- helpers ----------

    private long newProject(String suffix) {
        // code column is varchar(20): short stable prefix + per-run random tail
        String tail = Long.toString(System.nanoTime() % 100000000L, 36);
        long pid = projectService.create(1L, new ProjectCreateInput(
                "分析切片项目" + suffix, "SA-" + suffix + "-" + tail, "zh-CN", "P1", null));
        projectIds.add(pid);
        return pid;
    }

    private long newRequirement(long pid, String title, String chapter) {
        return requirementService.create(1L, new RequirementCreateInput(
                pid, 3, null, title, "验收标准", chapter)).id();
    }

    /** Creates a case with steps; rows shape = {description, isAssert}. */
    private long newCase(long pid, String title, String endType, Object[][] steps) {
        List<CaseStepInput> inputs = new ArrayList<>();
        for (int i = 0; i < steps.length; i++) {
            inputs.add(new CaseStepInput(i + 1, (String) steps[i][0], (Integer) steps[i][1]));
        }
        CaseInfo info = caseService.create(1L, new CaseCreateInput(pid, title, "前置", "预期",
                1, 2, endType, 1, null, 2, null, inputs));
        return info.id();
    }

    private void linkCase(long caseId, List<Long> requirementIds) {
        jdbcTemplate.update("INSERT INTO tp_case_requirement (case_id, requirement_id) VALUES (?, ?)",
                caseId, requirementIds.get(0));
        for (int i = 1; i < requirementIds.size(); i++) {
            jdbcTemplate.update("INSERT INTO tp_case_requirement (case_id, requirement_id) VALUES (?, ?)",
                    caseId, requirementIds.get(i));
        }
    }

    private long insertTool(int execKind) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO tp_tool (project_id, name, type, exec_kind, exec_spec) "
                        + "VALUES (NULL, ?, 2, ?, '{}'::jsonb) RETURNING id",
                Long.class, "sa-tool-" + execKind + "-" + System.nanoTime(), execKind);
    }

    private void purgeFixtures() {
        if (projectIds.isEmpty()) {
            return;
        }
        String ph = String.join(",", projectIds.stream().map(x -> "?").toList());
        jdbcTemplate.update("DELETE FROM tp_case_step WHERE case_id IN (SELECT id FROM tp_test_case WHERE project_id IN (" + ph + "))",
                projectIds.toArray());
        jdbcTemplate.update("DELETE FROM tp_case_version WHERE case_id IN (SELECT id FROM tp_test_case WHERE project_id IN (" + ph + "))",
                projectIds.toArray());
        jdbcTemplate.update("DELETE FROM tp_case_requirement WHERE case_id IN (SELECT id FROM tp_test_case WHERE project_id IN (" + ph + "))",
                projectIds.toArray());
        jdbcTemplate.update("DELETE FROM tp_test_case WHERE project_id IN (" + ph + ")", projectIds.toArray());
        jdbcTemplate.update("DELETE FROM tp_requirement WHERE project_id IN (" + ph + ")", projectIds.toArray());
        jdbcTemplate.update("DELETE FROM tp_project WHERE id IN (" + ph + ")", projectIds.toArray());
        projectIds.clear();
    }
}
