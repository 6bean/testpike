package com.sixbean.testpike.core.linkage;

import com.sixbean.testpike.core.linkage.api.dto.ChangeLinkageEdgeInput;
import com.sixbean.testpike.core.linkage.domain.service.ChangeLinkageEdgeService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;

/**
 * Shared run-owned fixtures for the change-linkage ledger tests (AN batch
 * ). Extracted because two test classes grew the identical setup/teardown
 * block — edge rows and probe tables are ledger-level state on a real slot
 * database, so their ownership cleanup must have exactly one source that all
 * linkage tests consume (a copied second copy drifts the moment one test
 * changes its probe shape). Fixtures are always own rows and own
 * run-suffixed tables; nothing here ever touches factory baseline rows.
 */
public final class LinkageLedgerFixtures {

    private LinkageLedgerFixtures() {
    }

    /** Unique run-owned probe table name; caller drops it via {@link #dropProbeTable}. */
    public static String newProbeTableName(String prefix) {
        return prefix + System.nanoTime();
    }

    /**
     * Create a run-owned probe table holding one foreign key onto
     * tp_requirement — the shape the orphan-edge scan must surface while any
     * "requirement" member edge stays armed, and stop surfacing once dropped.
     */
    public static void createRequirementForeignKey(NamedParameterJdbcTemplate jdbc, String table) {
        jdbc.update("CREATE TABLE " + table
                + " (id BIGINT PRIMARY KEY, req_id BIGINT"
                + " REFERENCES tp_requirement (id))", new MapSqlParameterSource());
    }

    /** Drop a run-owned probe table (idempotent). */
    public static void dropProbeTable(NamedParameterJdbcTemplate jdbc, String table) {
        jdbc.update("DROP TABLE IF EXISTS " + table, new MapSqlParameterSource());
    }

    /**
     * Register an enabled requirement-&gt;ai_task edge, which arms the
     * "requirement" bus member and makes its foreign-key fan-out subject to
     * the scan. The generated edge key is appended to {@code ownedEdgeKeys}
     * so teardown deletes exactly this run's rows.
     */
    public static void registerRequirementEdge(ChangeLinkageEdgeService edges,
                                               List<String> ownedEdgeKeys) {
        String key = "requirement->ai_task:t" + System.nanoTime();
        ownedEdgeKeys.add(key);
        edges.register(new ChangeLinkageEdgeInput(
                key, "requirement", "ai_task",
                2, "SELECT id FROM tp_ai_task WHERE requirement_id = :objectId", null,
                "mark_only", 1, 0, null,
                "A requirement edit re-marks generation tasks built from it"));
    }

    /** Delete exactly this run's edge rows by their unique keys and empty the list. */
    public static void removeOwnedEdgeRows(NamedParameterJdbcTemplate jdbc,
                                            List<String> ownedEdgeKeys) {
        if (!ownedEdgeKeys.isEmpty()) {
            jdbc.update("DELETE FROM tp_change_linkage WHERE edge_key IN (:keys)",
                    new MapSqlParameterSource("keys", ownedEdgeKeys));
            ownedEdgeKeys.clear();
        }
    }
}
