package com.sixbean.testpike.core.analysis.domain.service.impl;

import com.sixbean.testpike.core.analysis.api.dto.AssertionDensityView;
import com.sixbean.testpike.core.analysis.api.dto.CaseAssertionRow;
import com.sixbean.testpike.core.analysis.api.dto.CoverageSummaryView;
import com.sixbean.testpike.core.analysis.domain.component.CaseHead;
import com.sixbean.testpike.core.analysis.domain.component.CoverageFloorParser;
import com.sixbean.testpike.core.analysis.domain.service.SufficiencyAnalysisService;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import com.sixbean.testpike.core.coverage.api.dto.CoverageMatrixView;
import com.sixbean.testpike.core.coverage.api.dto.MatrixChapter;
import com.sixbean.testpike.core.coverage.api.dto.MatrixRequirementRow;
import com.sixbean.testpike.core.coverage.domain.service.CoverageMatrixService;
import com.sixbean.testpike.core.spec.api.dto.InventoryCoverageSummary;
import com.sixbean.testpike.core.spec.domain.service.ApiInventoryQueryService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only coverage-sufficiency projections for the AI conversation analysis
 * channel . Every number is delegated to an existing single
 * source: requirement counts to the J4 matrix, contract coverage to the K8
 * exit-floor key plus the inventory read path, assertion density to the
 * latest-version step rows of active cases. No writes, no audit noise.
 */
@Service
public class SufficiencyAnalysisServiceImpl implements SufficiencyAnalysisService {

    /** Uncovered-title cap for conversation rendering (worst-scale rule). */
    private static final int UNCOVERED_TITLE_CAP = 50;

    /** Same K8 key the plan exit check reads — never a second gate definition. */
    private static final String CFG_APITEST_MIN_COVERAGE = "plan.exit.apitest.min-coverage";

    private final CoverageMatrixService coverageMatrixService;
    private final ApiInventoryQueryService inventoryQueryService;
    private final ConfigCacheHolder configCacheHolder;
    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate namedJdbcTemplate;

    public SufficiencyAnalysisServiceImpl(CoverageMatrixService coverageMatrixService,
                                          ApiInventoryQueryService inventoryQueryService,
                                          ConfigCacheHolder configCacheHolder,
                                          JdbcTemplate jdbcTemplate,
                                          NamedParameterJdbcTemplate namedJdbcTemplate) {
        this.coverageMatrixService = coverageMatrixService;
        this.inventoryQueryService = inventoryQueryService;
        this.configCacheHolder = configCacheHolder;
        this.jdbcTemplate = jdbcTemplate;
        this.namedJdbcTemplate = namedJdbcTemplate;
    }

    @Override
    public CoverageSummaryView coverageSummary(long projectId, String chapter, String endType) {
        CoverageMatrixView matrix = coverageMatrixService.matrix(projectId, chapter, null, endType);
        Map<Long, String> titles = new LinkedHashMap<>();
        for (MatrixChapter group : matrix.chapters()) {
            for (MatrixRequirementRow row : group.requirements()) {
                titles.put(row.id(), row.title());
            }
        }
        List<String> uncoveredTitles = new ArrayList<>();
        boolean truncated = false;
        for (Long id : matrix.uncoveredRequirementIds()) {
            if (uncoveredTitles.size() >= UNCOVERED_TITLE_CAP) {
                truncated = true;
                break;
            }
            String title = titles.get(id);
            uncoveredTitles.add(title == null ? ("需求#" + id) : title);
        }

        int floor = CoverageFloorParser.parse(configCacheHolder.get(CFG_APITEST_MIN_COVERAGE));
        int apitestCoveragePercent = -1;
        String gateMessage = null;
        if (floor > 0) {
            InventoryCoverageSummary cov = inventoryQueryService.projectCoverageSummary(projectId);
            if (cov.total() == 0) {
                gateMessage = "契约覆盖率门禁已开启（下限 " + floor + "%），但本项目尚无接口契约，无法实测覆盖率";
            } else {
                apitestCoveragePercent = cov.coveragePercent();
                if (apitestCoveragePercent < floor) {
                    gateMessage = "契约端点覆盖率 " + apitestCoveragePercent + "% 低于准出下限 "
                            + floor + "%（" + cov.total() + " 个契约端点中 "
                            + cov.endpointsWithRequests() + " 个已建接口请求）";
                }
            }
        }
        return new CoverageSummaryView(projectId, chapter, endType,
                matrix.totalRequirements(), matrix.coveredRequirements(),
                uncoveredTitles, truncated, floor, floor > 0, apitestCoveragePercent, gateMessage);
    }

    @Override
    public AssertionDensityView assertionDensity(long projectId, String endType) {
        // Active scope mirrors the matrix single source (deleted = 0 AND
        // status <> 9); tp_test_case.version is the live-version pointer and
        // step rows carry case_version, so historical snapshots never inflate
        // the density (tp_case_version holds the lagging audit snapshot).
        List<CaseHead> heads = jdbcTemplate.query(
                "SELECT id, title, version AS counted_version "
                        + "FROM tp_test_case "
                        + "WHERE project_id = ? AND deleted = 0 AND status <> 9 "
                        + "  AND (?::varchar IS NULL OR end_type = ?) "
                        + "ORDER BY case_no",
                (rs, i) -> new CaseHead(rs.getLong("id"), rs.getString("title"), rs.getInt("counted_version")),
                projectId, endType, endType);
        Map<Long, long[]> stepCounts = new HashMap<>();
        if (!heads.isEmpty()) {
            // steps aggregate on the live version inside the SAME active scope
            // (deleted = 0 AND status <> 9) — archived rows can never leak in
            // through a stale case_version written by the create snapshot path.
            namedJdbcTemplate.query(
                    "SELECT s.case_id, c.version AS counted_version, "
                            + "COUNT(*) AS steps, "
                            + "SUM(CASE WHEN s.is_assert = 1 THEN 1 ELSE 0 END) AS asserts "
                            + "FROM tp_case_step s "
                            + "JOIN tp_test_case c ON c.id = s.case_id "
                            + "WHERE c.project_id = :projectId AND c.deleted = 0 AND c.status <> 9 "
                            + "  AND (:endType::varchar IS NULL OR c.end_type = :endType) "
                            + "  AND s.case_version = c.version "
                            + "GROUP BY s.case_id, c.version",
                    new MapSqlParameterSource("projectId", projectId).addValue("endType", endType),
                    (ResultSetExtractor<Void>) rs -> {
                        while (rs.next()) {
                            stepCounts.put(rs.getLong("case_id"), new long[]{
                                    rs.getLong("case_id"), rs.getLong("counted_version"),
                                    rs.getLong("steps"), rs.getLong("asserts")});
                        }
                        return null;
                    });
        }
        long totalSteps = 0;
        long assertionSteps = 0;
        long zeroAssertCases = 0;
        List<CaseAssertionRow> perCase = new ArrayList<>();
        for (CaseHead head : heads) {
            long[] counts = stepCounts.get(head.caseId());
            long steps = counts == null ? 0 : counts[2];
            long asserts = counts == null ? 0 : counts[3];
            // the counted row must agree with the head's latest version
            long countedVersion = counts == null ? -1 : counts[1];
            if (counts != null && countedVersion != head.version()) {
                steps = 0;
                asserts = 0;
            }
            totalSteps += steps;
            assertionSteps += asserts;
            if (asserts == 0) {
                zeroAssertCases++;
            }
            perCase.add(new CaseAssertionRow(head.caseId(), head.title(), head.version(), steps, asserts));
        }
        double density = totalSteps == 0 ? 0.0 : Math.round((double) assertionSteps / totalSteps * 1e6) / 1e6;
        return new AssertionDensityView(projectId, endType, heads.size(), totalSteps, assertionSteps,
                density, zeroAssertCases, perCase);
    }
}
