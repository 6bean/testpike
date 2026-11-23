package com.sixbean.testpike.core.linkage.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.linkage.api.dto.OrphanEdgeScanReport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ledger contract for the plan downstream fan and the skill-family design
 * exemptions of the linkage bus : a plan save arms exactly the
 * edges registered as rows in tp_change_linkage, and skill binding/snapshot
 * tables are covered by written exemptions instead of propagating (R34).
 * <p>Shapes are pinned from the batch design adjudication: not-yet-fired
 * batches and plan rows are reversible marks (risk 1, zero clicks), defects,
 * issued batches and reports are already-issued records disclosed only
 * (risk 3). Each SQL detector must also run against the live schema today, so
 * a rotting edge fails here instead of silently shrinking the loop — the same
 * posture as the contract/mock/perf ledger test.
 */
@SpringBootTest(classes = CoreApplication.class)
class PlanSkillEdgeLedgerTest {

    /** Plan downstream pairs the ledger must hold once this segment lands. */
    private static final String[] PLAN_EDGE_KEYS = {
            "plan->exec_batch_pending",
            "plan->exec_batch_issued",
            "plan->defect",
            "plan->plan_case",
            "plan->plan_deviation",
            "plan->test_report",
    };

    /** Skill-family pairs registered as design-time exemptions. */
    private static final String[] SKILL_EXEMPT_KEYS = {
            "skill->project_skill",
            "skill->skill_tag",
            "skill->skill_usage",
            "skill->skill_version",
    };

    @Autowired
    private NamedParameterJdbcTemplate named;

    @Autowired
    private OrphanEdgeScanService orphanEdgeScanService;

    private Map<String, Object> edgeRow(String edgeKey) {
        List<Map<String, Object>> rows = named.queryForList(
                "SELECT edge_key, upstream_object_type, downstream_object_type,"
                        + " detector_kind, detector_ref, disposition_strategy, risk_level,"
                        + " exempt, exempt_reason"
                        + " FROM tp_change_linkage"
                        + " WHERE enabled = 1 AND edge_key = :edgeKey",
                new MapSqlParameterSource("edgeKey", edgeKey));
        assertEquals(1, rows.size(), "exactly one enabled edge row must exist for " + edgeKey);
        return rows.get(0);
    }

    @Test
    void planFanOutIsRegisteredAsDataRows() {
        for (String edgeKey : PLAN_EDGE_KEYS) {
            edgeRow(edgeKey); // fails loudly when the row is missing
        }
    }

    @Test
    void planDispositionShapesMatchTheDesignAdjudication() {
        assertShape("plan->exec_batch_pending", "mark_only", 1);
        assertShape("plan->exec_batch_issued", "disclose_only", 3);
        assertShape("plan->defect", "disclose_only", 3);
        assertShape("plan->plan_case", "mark_only", 1);
        assertShape("plan->plan_deviation", "disclose_only", 3);
        assertShape("plan->test_report", "disclose_only", 3);
    }

    private void assertShape(String edgeKey, String strategy, int risk) {
        Map<String, Object> row = edgeRow(edgeKey);
        assertEquals(strategy, row.get("disposition_strategy"),
                edgeKey + " must surface as " + strategy);
        // int4 columns come back as Integer from the PostgreSQL JDBC driver,
        // so numeric ledger shapes compare as Number.intValue().
        assertEquals(risk, ((Number) row.get("risk_level")).intValue(),
                edgeKey + " risk shape");
        assertEquals(0, ((Number) row.get("exempt")).intValue(),
                edgeKey + " is an armed edge, not an exemption");
    }

    /**
     * TC-LNK-05 first half: every skill-family exemption row is in place and
     * carries its written reason, and a detector-less row is only legal as an
     * exemption (kind 3, strategy exempt) per the registry constraint.
     */
    @Test
    void skillFamilyExemptionsAreRegisteredWithWrittenReasons() {
        for (String edgeKey : SKILL_EXEMPT_KEYS) {
            Map<String, Object> row = edgeRow(edgeKey);
            assertEquals(1, ((Number) row.get("exempt")).intValue(),
                    edgeKey + " must be a registered exemption");
            assertEquals("exempt", row.get("disposition_strategy"),
                    edgeKey + " disposition shape");
            assertEquals(3, ((Number) row.get("detector_kind")).intValue(),
                    edgeKey + " exemption carries no detector");
            String reason = (String) row.get("exempt_reason");
            assertTrue(reason != null && !reason.isBlank(),
                    edgeKey + " exemption must state its reason in the row");
        }
    }

    /**
     * Plan SQL detectors must run against the live schema; a renamed column or
     * typo fails here, never as a silent no-impact at dispatch time.
     */
    @Test
    void planSqlDetectorsAreExecutableAgainstTheLiveSchema() {
        for (String edgeKey : PLAN_EDGE_KEYS) {
            Map<String, Object> row = edgeRow(edgeKey);
            assertEquals(2, ((Number) row.get("detector_kind")).intValue(),
                    edgeKey + " is a SQL-predicate edge");
            String sql = (String) row.get("detector_ref");
            List<Map<String, Object>> probe = named.queryForList(sql,
                    new MapSqlParameterSource("objectId", 0L));
            assertTrue(probe.isEmpty(), "probe id must match no live row");
        }
    }

    /**
 * The orphan-edge gate reads the exemption rows and stays silent: skill is
 * covered (absent from withoutEdges), its binding tables are not reported
 * orphans, and none of this segment's rows is a dead edge.
 */
    @Test
    void orphanGateReadsExemptionsWithoutFalsePositives() {
        OrphanEdgeScanReport report = orphanEdgeScanService.scan();
        assertFalse(report.busMembersWithoutEdges().contains("skill"),
                "skill binding surface is covered by registered exemptions");
        assertFalse(report.busMembersWithoutEdges().contains("plan"),
                "plan fan is registered");
        for (String edgeKey : PLAN_EDGE_KEYS) {
            assertFalse(report.deadEdges().contains(edgeKey),
                    edgeKey + " upstream is a live bus member");
        }
        for (String edgeKey : SKILL_EXEMPT_KEYS) {
            assertFalse(report.deadEdges().contains(edgeKey),
                    edgeKey + " upstream is a live bus member");
        }
        for (var orphan : report.orphans()) {
            assertFalse(orphan.referencingTable().startsWith("tp_skill")
                            || orphan.referencingTable().equals("tp_project_skill"),
                    "skill binding table " + orphan.referencingTable()
                            + " must not surface as an orphan edge");
        }
    }
}
