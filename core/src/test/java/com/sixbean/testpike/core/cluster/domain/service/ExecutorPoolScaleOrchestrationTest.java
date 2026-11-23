package com.sixbean.testpike.core.cluster.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cluster.api.dto.ExecutorPoolScalePlan;
import com.sixbean.testpike.core.cluster.api.dto.ExecutorScaleDecision;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T294c wiring (third segment): the autoscaler orchestration channel composes the three
 * already-delivered pieces — guardrail reader, demand spectrum, pure rules — into one
 * per-pool decision snapshot list, the "read-only application face" the fleet page and any
 * future scale driver consume. The channel itself decides nothing new: every bound comes
 * from cluster.* config keys and every number from the live demand snapshot.
 *
 * <p>Contracts pinned here:
 * 1) exactly one plan per online pool, with user-facing wording naming the pool;
 * 2) the elasticity-paused switch short-circuits every pool to its current line;
 * 3) a deep claimable backlog produces SCALE_OUT jumping to the rounded-up desired line;
 * 4) the pool ceiling clamps growth and the reason says so honestly;
 * 5) a budget waterline at or above the threshold blocks growth (BUDGET_PAUSED);
 * 6) an idle over-provisioned pool steps in by exactly one replica with drain first.
 *
 * <p>Real dev database via SpringBootTest (no mocks; fixtures on a private high project-id
 * band and unique executor names, deleted in the teardown; guardrail keys are overridden
 * through the production write shape — database plus shared config cache together — and
 * restored by deleting both so the shipped fallback equals the factory seed again).
 */
@SpringBootTest(classes = CoreApplication.class)
class ExecutorPoolScaleOrchestrationTest {

    private static final long MARKER_PROJECT = 93100002L;
    private static final String K_CAPACITY = "cluster.autoscale-capacity-per-executor";
    private static final String K_MAX = "cluster.autoscale-max-replicas";
    private static final String K_PAUSED = "cluster.autoscale-paused";
    private static final String K_BUDGET_UTIL = "cluster.budget-max-utilization";

    private static final List<String> OVERIDDEN_KEYS =
            List.of(K_CAPACITY, K_MAX, K_PAUSED, K_BUDGET_UTIL);

    @Autowired
    private ExecutorPoolScaleOrchestrationService orchestrationService;

    @Autowired
    private ConfigCacheHolder configCacheHolder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Factory seed rows captured once, before the first test drops them (R37 baseline). */
    private static java.util.List<String[]> seedSnapshot;

    @BeforeEach
    void snapshotThenDropGuardrailRows() {
        if (seedSnapshot == null) {
            seedSnapshot = jdbcTemplate.query(
                    "SELECT config_key, config_value, group_name, value_type, encrypt_flag, description"
                            + " FROM tp_sys_config WHERE config_key = ANY(?)",
                    new Object[]{OVERIDDEN_KEYS.toArray(String[]::new)},
                    (org.springframework.jdbc.core.RowMapper<String[]>)
                            (rs, i) -> new String[]{
                                    rs.getString(1), rs.getString(2), rs.getString(3),
                                    rs.getString(4), rs.getString(5), rs.getString(6)});
        }
        cleanFixtures();
    }

    @AfterEach
    void cleanFixturesAndRestoreBaseline() {
        cleanFixtures();
        // Re-insert the factory rows the drop above removed, field-for-field: the shipped
        // baseline must survive this class untouched (R37).
        for (String[] row : seedSnapshot) {
            jdbcTemplate.update(
                    "INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)"
                            + " VALUES (?, ?, ?, ?, ?, ?)"
                            + " ON CONFLICT (config_key) DO UPDATE SET config_value = EXCLUDED.config_value",
                    row[0], row[1], row[2], row[3], Integer.parseInt(row[4]), row[5]);
        }
        OVERIDDEN_KEYS.forEach(configCacheHolder::remove);
    }

    private void cleanFixtures() {
        jdbcTemplate.update("DELETE FROM tp_exec_task WHERE project_id = ?", MARKER_PROJECT);
        jdbcTemplate.update("DELETE FROM tp_executor WHERE name LIKE 't294-orch-%'");
        jdbcTemplate.update("DELETE FROM tp_sys_config WHERE config_key = ANY(?)",
                (Object) OVERIDDEN_KEYS.toArray(String[]::new));
        OVERIDDEN_KEYS.forEach(configCacheHolder::remove);
    }

    private void put(String key, String value) {
        jdbcTemplate.update(
                "INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)"
                        + " VALUES (?, ?, 'APP自动化', 'int', 0, 'fixture row for autoscaler orchestration test')"
                        + " ON CONFLICT (config_key) DO UPDATE SET config_value = EXCLUDED.config_value",
                key, value);
        configCacheHolder.put(key, value);
    }

    private void insertOnlineExecutor(String name, String endTypes) {
        jdbcTemplate.update("""
                INSERT INTO tp_executor (name, end_types, browsers, capacity, running_cnt,
                                         status, version, last_heartbeat, capabilities)
                VALUES (?, ?, 'chromium', 2, 0, 1, '1.0.0', ?::timestamp, '{}'::jsonb)
                """, name, endTypes, java.time.LocalDateTime.now());
    }

    private void insertPendingWebTask() {
        jdbcTemplate.update(
                "INSERT INTO tp_exec_task (project_id, case_id, batch_id, action_json, status)"
                        + " VALUES (?, 1, NULL, '{\"kind\":\"web\",\"steps\":[]}', 1)",
                MARKER_PROJECT);
    }

    private Map<String, ExecutorPoolScalePlan> planMap() {
        return orchestrationService.evaluateAll(0.0).stream()
                .collect(Collectors.toMap(ExecutorPoolScalePlan::executorType, Function.identity()));
    }

    @Test
    void onePlanPerOnlinePool_withUserFacingReason() {
        insertOnlineExecutor("t294-orch-web", "web");
        insertOnlineExecutor("t294-orch-mob", "mobile");

        Map<String, ExecutorPoolScalePlan> plans = planMap();
        assertTrue(plans.containsKey("web") && plans.containsKey("mobile"),
                "every online pool must carry exactly one plan, got " + plans.keySet());
        assertTrue(plans.get("web").decision().reason().contains("web"),
                "reason line must name the pool for the fleet page");
        assertEquals("web", plans.get("web").demand().executorType());
    }

    @Test
    void elasticPaused_shortCircuitsEveryPoolToCurrentLine() {
        insertOnlineExecutor("t294-orch-web", "web");
        insertPendingWebTask();
        insertPendingWebTask();
        put(K_CAPACITY, "1");
        put(K_PAUSED, "1");

        ExecutorPoolScalePlan web = planMap().get("web");
        assertEquals(ExecutorScaleDecision.ACTION_PAUSED, web.decision().action());
        assertEquals(1, web.decision().targetReplicas());
        assertEquals(1, web.demand().currentReplicas());
    }

    @Test
    void deepBacklog_scalesOutToRoundedUpDesiredLine() {
        insertOnlineExecutor("t294-orch-web", "web");
        for (int i = 0; i < 3; i++) {
            insertPendingWebTask();
        }
        put(K_CAPACITY, "1");

        ExecutorPoolScalePlan web = planMap().get("web");
        assertEquals(3, web.demand().pendingDispatchDepth());
        assertEquals(ExecutorScaleDecision.ACTION_SCALE_OUT, web.decision().action());
        assertEquals(3, web.decision().desiredReplicas());
        assertEquals(3, web.decision().targetReplicas());
        assertFalse(web.decision().drainFirst(), "growth never drains");
    }

    @Test
    void backlogAboveCeiling_clampsAndSaysSo() {
        insertOnlineExecutor("t294-orch-web", "web");
        for (int i = 0; i < 4; i++) {
            insertPendingWebTask();
        }
        put(K_CAPACITY, "1");
        put(K_MAX, "2");

        ExecutorPoolScalePlan web = planMap().get("web");
        assertEquals(2, web.decision().desiredReplicas(), "hard ceiling wins over demand");
        assertEquals(ExecutorScaleDecision.ACTION_SCALE_OUT, web.decision().action());
        assertTrue(web.decision().reason().contains("上限"),
                "clamped growth must state the ceiling in the reason, got " + web.decision().reason());
    }

    @Test
    void budgetWaterlineAtThreshold_blocksGrowthButKeepsPool() {
        insertOnlineExecutor("t294-orch-web", "web");
        for (int i = 0; i < 3; i++) {
            insertPendingWebTask();
        }
        put(K_CAPACITY, "1");

        List<ExecutorPoolScalePlan> plans = orchestrationService.evaluateAll(0.9);
        ExecutorPoolScalePlan web = plans.stream()
                .filter(p -> "web".equals(p.executorType())).findFirst().orElseThrow();
        assertEquals(ExecutorScaleDecision.ACTION_BUDGET_PAUSED, web.decision().action());
        assertEquals(1, web.decision().targetReplicas(), "growth stops at the current line");
    }

    @Test
    void idleOverProvisionedPool_stepsInOneReplicaWithDrain() {
        insertOnlineExecutor("t294-orch-web1", "web");
        insertOnlineExecutor("t294-orch-web2", "web");
        put(K_CAPACITY, "1");

        ExecutorPoolScalePlan web = planMap().get("web");
        assertEquals(0, web.demand().pendingDispatchDepth());
        assertEquals(2, web.demand().currentReplicas());
        assertEquals(ExecutorScaleDecision.ACTION_SCALE_IN, web.decision().action());
        assertEquals(1, web.decision().targetReplicas(), "scale-in steps one replica per round");
        assertTrue(web.decision().drainFirst(), "scale-in must drain before removing a replica");
        assertTrue(web.decision().reason().contains("排空"),
                "reason must promise the drain-before-offline contract");
    }
}
