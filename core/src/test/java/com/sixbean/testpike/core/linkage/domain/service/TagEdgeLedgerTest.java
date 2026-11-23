package com.sixbean.testpike.core.linkage.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.linkage.api.dto.OrphanEdgeScanReport;
import com.sixbean.testpike.core.linkage.domain.service.OrphanEdgeScanService;
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
 * Ledger contract for the tag downstream fan of the linkage bus
 * : every table that cites tp_tag_dict must be named
 * by a registered edge row, and the propagation itself is handled inside the
 * merge/delete transactions (bindings are re-pointed, conflicts are refused)
 * rather than by bus fan-out. The rows are therefore design-time exemptions
 * with written reasons, the same shape the skill family uses (TC-LNK-05):
 * they make the FK reverse-lookup census of the final batch reconciliation
 * close without the orphan-edge gate reporting the relations as uncovered.
 */
@SpringBootTest(classes = CoreApplication.class)
class TagEdgeLedgerTest {

    /** Tag downstream pairs the ledger must hold once this segment lands. */
    private static final String[] TAG_EDGE_KEYS = {
            "tag->case_tag",
            "tag->skill_tag",
            "tag->exec_batch_pool",
    };

    @Autowired
    private NamedParameterJdbcTemplate named;

    @Autowired
    private OrphanEdgeScanService orphanEdgeScanService;

    private Map<String, Object> edgeRow(String edgeKey) {
        List<Map<String, Object>> rows = named.queryForList(
                "SELECT edge_key, upstream_object_type, downstream_object_type,"
                        + " detector_kind, disposition_strategy, risk_level,"
                        + " exempt, exempt_reason"
                        + " FROM tp_change_linkage"
                        + " WHERE enabled = 1 AND edge_key = :edgeKey",
                new MapSqlParameterSource("edgeKey", edgeKey));
        assertEquals(1, rows.size(), "exactly one enabled edge row must exist for " + edgeKey);
        return rows.get(0);
    }

    /**
     * TC-LNK-05 second half: the tag fan is registered as exemptions with
     * written reasons (detector-less rows are only legal as exemptions per
     * the registry constraint), so the relation is auditable instead of
     * silently covered by code.
     */
    @Test
    void tagFanIsRegisteredAsWrittenExemptions() {
        for (String edgeKey : TAG_EDGE_KEYS) {
            Map<String, Object> row = edgeRow(edgeKey);
            assertEquals("tag", row.get("upstream_object_type"), edgeKey + " upstream");
            assertEquals(1, ((Number) row.get("exempt")).intValue(),
                    edgeKey + " must be a registered exemption");
            assertEquals("exempt", row.get("disposition_strategy"),
                    edgeKey + " disposition shape");
            assertEquals(3, ((Number) row.get("detector_kind")).intValue(),
                    edgeKey + " exemption carries no detector");
            String reason = (String) row.get("exempt_reason");
            assertTrue(reason != null && reason.toLowerCase().contains("merge"),
                    edgeKey + " reason must name the in-transaction chain that keeps bindings alive");
        }
    }

    /**
     * The orphan-edge gate reads the tag exemptions and stays silent: tag is
     * covered (absent from busMembersWithoutEdges) and none of these rows is
     * a dead edge, the same posture as the plan and skill-family test.
     */
    @Test
    void orphanGateReadsTagExemptionsWithoutFalsePositives() {
        OrphanEdgeScanReport report = orphanEdgeScanService.scan();
        assertFalse(report.busMembersWithoutEdges().contains("tag"),
                "the tag binding surface is covered by registered exemptions");
        for (String edgeKey : TAG_EDGE_KEYS) {
            assertFalse(report.deadEdges().contains(edgeKey),
                    edgeKey + " upstream is a live bus member");
        }
    }
}
