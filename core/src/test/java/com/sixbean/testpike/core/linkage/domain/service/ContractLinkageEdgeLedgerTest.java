package com.sixbean.testpike.core.linkage.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ledger contract for the contract/mock/performance downstream fan-out of the
 * linkage bus: api_spec, mock and perf_scenario saves must arm exactly the
 * edges registered as rows in tp_change_linkage (an edge is data, not code).
 * <p>The shapes are pinned from the batch design adjudication: a contract edit
 * surfaces grouped drafts over the folded endpoint key (the Java match-key
 * rules stay the single source, so these two edges are bean detectors), a stub
 * edit re-marks the stateful scenario it belongs to, and a scenario edit
 * surfaces a re-approval grouped draft while already-issued runs are only
 * disclosed. Each row must also be executable today: SQL predicates run
 * against the live schema and bean names resolve in the Spring context, so a
 * rotting edge fails here instead of silently shrinking the propagation loop.
 */
@SpringBootTest(classes = CoreApplication.class)
class ContractLinkageEdgeLedgerTest {

    /** upstream -> downstream pairs the ledger must hold once this segment lands. */
    private static final String[] EDGE_KEYS = {
            "api_spec->apitest_request",
            "api_spec->mock_stub",
            "mock->mock_scenario",
            "perf_scenario->perf_approval",
            "perf_scenario->perf_run",
    };

    @Autowired
    private NamedParameterJdbcTemplate named;

    @Autowired
    private ApplicationContext context;

    private Map<String, Object> edgeRow(String edgeKey) {
        List<Map<String, Object>> rows = named.queryForList(
                "SELECT edge_key, upstream_object_type, downstream_object_type,"
                        + " detector_kind, detector_ref, disposition_strategy, risk_level, exempt"
                        + " FROM tp_change_linkage"
                        + " WHERE enabled = 1 AND edge_key = :edgeKey",
                new MapSqlParameterSource("edgeKey", edgeKey));
        assertEquals(1, rows.size(), "exactly one enabled edge row must exist for " + edgeKey);
        return rows.get(0);
    }

    @Test
    void contractFanOutIsRegisteredAsDataRows() {
        for (String edgeKey : EDGE_KEYS) {
            edgeRow(edgeKey); // fails loudly when the row is missing
        }
    }

    @Test
    void dispositionShapesMatchTheDesignAdjudication() {
        assertShape("api_spec->apitest_request", "grouped_draft", 2);
        assertShape("api_spec->mock_stub", "grouped_draft", 2);
        assertShape("mock->mock_scenario", "mark_only", 1);
        assertShape("perf_scenario->perf_approval", "grouped_draft", 2);
        assertShape("perf_scenario->perf_run", "disclose_only", 3);
    }

    private void assertShape(String edgeKey, String strategy, int risk) {
        Map<String, Object> row = edgeRow(edgeKey);
        assertEquals(strategy, row.get("disposition_strategy"),
                edgeKey + " must surface as " + strategy);
        // int4 columns come back as Integer from the PostgreSQL JDBC driver,
        // so numeric ledger shapes compare as Number.intValue(): pinning a
        // boxed Short here would assert the driver's boxing, not the data.
        assertEquals(risk, ((Number) row.get("risk_level")).intValue(),
                edgeKey + " risk shape");
        assertEquals(0, ((Number) row.get("exempt")).intValue(),
                edgeKey + " is an armed edge, not an exemption");
    }

    /**
     * Bean-detector edges (kind 1) must name a live Spring bean implementing
     * the linkage edge detector interface. The interface is looked up by name
     * so this test stays about wiring, not compile-time coupling.
     */
    @Test
    void beanDetectorRowsResolveToLiveDetectorBeans() {
        for (String edgeKey : new String[] {"api_spec->apitest_request", "api_spec->mock_stub"}) {
            Map<String, Object> row = edgeRow(edgeKey);
            assertEquals(1, ((Number) row.get("detector_kind")).intValue(),
                    edgeKey + " needs the Java folded-key rules as single source (kind 1)");
            String beanName = (String) row.get("detector_ref");
            assertTrue(context.containsBean(beanName),
                    edgeKey + " names detector bean " + beanName + " which is absent");
            Object bean = context.getBean(beanName);
            assertTrue(implementsDetector(bean.getClass()),
                    edgeKey + " bean " + beanName + " must implement LinkageEdgeDetector");
        }
    }

    private boolean implementsDetector(Class<?> type) {
        if (type == null) {
            return false;
        }
        for (Class<?> iface : type.getInterfaces()) {
            if (iface.getSimpleName().equals("LinkageEdgeDetector")
                    || implementsDetector(iface)) {
                return true;
            }
        }
        return implementsDetector(type.getSuperclass());
    }

    /**
     * SQL-detector edges (kind 2) must run against the live schema. A missing
     * table, a renamed column or a typo in the predicate fails here, never as
     * a silent no-impact at dispatch time.
     */
    @Test
    void sqlDetectorsAreExecutableAgainstTheLiveSchema() {
        for (String edgeKey : new String[] {"mock->mock_scenario",
                "perf_scenario->perf_approval", "perf_scenario->perf_run"}) {
            Map<String, Object> row = edgeRow(edgeKey);
            assertEquals(2, ((Number) row.get("detector_kind")).intValue(),
                    edgeKey + " is a plain foreign-key/text-reference fan (kind 2)");
            String sql = (String) row.get("detector_ref");
            List<Long> ids = named.queryForList(sql,
                    new MapSqlParameterSource("objectId", -1L), Long.class);
            assertTrue(ids.isEmpty(),
                    edgeKey + " detector must yield nothing for an unknown object id");
        }
    }

    /**
     * The folded-key equality join is why the contract fan uses bean detectors:
     * pin that the request side really stores the folded match key the detector
     * compares against, so a silent drop of that column is caught as data drift.
     */
    @Test
    void requestFoldedMatchKeyColumnIsPresent() {
        Integer count = named.queryForObject(
                "SELECT count(*) FROM information_schema.columns"
                        + " WHERE table_name = 'tp_apitest_request'"
                        + " AND column_name = 'contract_endpoint_key'",
                new MapSqlParameterSource(), Integer.class);
        assertEquals(1, count, "contract fan depends on the stored folded key");
    }
}
