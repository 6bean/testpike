package com.sixbean.testpike.core.linkage.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.linkage.api.dto.LinkageOrphanEdge;
import com.sixbean.testpike.core.linkage.api.dto.OrphanEdgeScanReport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ledger contract for the case and requirement downstream fan-out
 * . Edges are data: this test pins the
 * seeded rows of tp_change_linkage for upstream types {@code case} and
 * {@code requirement}, so a future edit that drops, disables or mislabels an
 * edge fails here instead of silently shrinking the propagation loop.
 * <p>Two layers are asserted. First the disposition shapes named in the batch
 * design adjudication (plan rows mark automatically, issued-execution rows only
 * disclose, script rows surface a grouped draft, aggregate/history rows are
 * written exemptions). Second the orphan-edge gate runs live over these armed
 * members: every foreign key onto tp_test_case or tp_requirement must be
 * covered by the ledger, which doubles as a drift tripwire — a new foreign key
 * referencing either table without a matching edge row turns this test red.
 */
@SpringBootTest(classes = CoreApplication.class)
class CaseRequirementEdgeLedgerTest {

    /** Every case-side edge the ledger must hold once this batch segment lands. */
    private static final int MIN_CASE_EDGES = 20;

    /** Requirement-side edges: link coverage plus the review-log and case pair. */
    private static final int MIN_REQUIREMENT_EDGES = 3;

    @Autowired
    private NamedParameterJdbcTemplate named;

    @Autowired
    private OrphanEdgeScanService orphanEdgeScanService;

    private List<Map<String, Object>> enabledEdges(String upstream) {
        return named.queryForList(
                "SELECT edge_key, downstream_object_type, detector_kind, detector_ref,"
                        + " disposition_strategy, risk_level, exempt"
                        + " FROM tp_change_linkage"
                        + " WHERE enabled = 1 AND upstream_object_type = :upstream",
                new MapSqlParameterSource("upstream", upstream));
    }

    private Map<String, Object> edge(String upstream, String downstream) {
        List<Map<String, Object>> rows = enabledEdges(upstream).stream()
                .filter(row -> downstream.equals(row.get("downstream_object_type")))
                .collect(Collectors.toList());
        assertEquals(1, rows.size(),
                "exactly one enabled edge must exist for " + upstream + "->" + downstream);
        return rows.get(0);
    }

    @Test
    void caseFanOutIsRegisteredAsDataRows() {
        assertTrue(enabledEdges("case").size() >= MIN_CASE_EDGES,
                "case downstream ledger must hold at least " + MIN_CASE_EDGES + " edges");
    }

    @Test
    void requirementFanOutIsRegisteredAsDataRows() {
        assertTrue(enabledEdges("requirement").size() >= MIN_REQUIREMENT_EDGES,
                "requirement downstream ledger must hold at least "
                        + MIN_REQUIREMENT_EDGES + " edges");
    }

    @Test
    void dispositionShapesFollowTheRiskAdjudication() {
        assertShape("case", "plan_case", "mark_only", 1);
        assertShape("case", "batch_case", "disclose_only", 3);
        assertShape("case", "exec_task", "disclose_only", 3);
        assertShape("case", "script", "grouped_draft", 2);
        assertShape("case", "case_step", "exempt", 1);
        assertShape("requirement", "ai_task", "mark_only", 1);
        assertShape("requirement", "requirement_review_log", "disclose_only", 3);
    }

    private void assertShape(String upstream, String downstream,
                             String strategy, int risk) {
        Map<String, Object> row = edge(upstream, downstream);
        assertEquals(strategy, row.get("disposition_strategy"),
                upstream + "->" + downstream + " disposition strategy");
        assertEquals(risk, ((Number) row.get("risk_level")).intValue(),
                upstream + "->" + downstream + " risk level");
    }

    @Test
    void nonExemptEdgesCarryAWorkingDetectorReference() {
        Predicate<Map<String, Object>> bad = row -> ((Number) row.get("exempt")).intValue() == 0
                && (row.get("detector_ref") == null
                        || String.valueOf(row.get("detector_ref")).isBlank());
        List<String> offenders = enabledEdges("case").stream()
                .filter(bad)
                .map(row -> String.valueOf(row.get("edge_key")))
                .collect(Collectors.toList());
        assertEquals(List.of(), offenders,
                "every non-exempt case edge needs a detector reference");
    }

    @Test
    void armedMembersShowZeroOrphanForeignKeys() {
        OrphanEdgeScanReport report = orphanEdgeScanService.scan();
        assertTrue(report.armedMembers().contains("case"),
                "registering case edges must arm the case member");
        assertTrue(report.armedMembers().contains("requirement"),
                "registering requirement edges must arm the requirement member");
        List<String> orphans = report.orphans().stream()
                .filter(edge -> "tp_test_case".equals(edge.referencedTable())
                        || "tp_requirement".equals(edge.referencedTable()))
                .map(CaseRequirementEdgeLedgerTest::describe)
                .collect(Collectors.toList());
        assertEquals(List.of(), orphans,
                "every foreign key onto the armed case/requirement tables must"
                        + " be covered by the ledger (new FKs need an edge row)");
    }

    private static String describe(LinkageOrphanEdge edge) {
        return edge.referencingTable() + "." + edge.fkColumn() + " (" + edge.fkName() + ")";
    }
}
