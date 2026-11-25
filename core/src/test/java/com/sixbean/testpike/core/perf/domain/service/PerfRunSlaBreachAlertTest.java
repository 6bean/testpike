package com.sixbean.testpike.core.perf.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.executor.api.dto.ExecResultInput;
import com.sixbean.testpike.core.perf.api.dto.PerfScenarioInput;
import com.sixbean.testpike.core.perf.domain.component.PerfMetricFrame;
import com.sixbean.testpike.core.perf.domain.component.PerfRunResultFinalizer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import com.sixbean.testpike.core.support.FixtureInserts;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;

/**
 * Contract tests for the automatic SLA red-light alert (GB-3, K2 subscription push).
 * <p>When a finished perf run fails its pinned SLA gates, the platform pushes a
 * {@code PERF_SLA_BREACH} in-site card to the run operator ∪ project test owner ∪
 * project creator (same recipient set as the manual conclusion card). The verdict is
 * consumed exclusively from {@code PerfReportService.build} (single source of truth —
 * no second judgment spectrum here).
 * <p>Asserted spectrum:
 * ① red run → card lands, body names the breached gates in human terms (no JSON dump);
 * ② green run → zero cards (honest silence — a passing run alerts nobody);
 * ③ run without SLA gates → zero cards (judge skips gates and reports passed=true;
 * the platform never fabricates a red light from missing thresholds);
 * ④ replay of the same run → dedup merges, no notification storm (dedup prefix
 * {@code PERF_SLA_BREACH:{runId}} is separate from the manual card prefix
 * {@code PERF_REPORT_CARD:{runId}} so neither channel swallows the other);
 * ⑤ unsubscribed recipient → filtered at the generation single point (D3 pref matrix);
 * ⑥ breaker stop (status=3) reaching core through the result finalizer → alert fires
 * from the automatic terminal channel as well.
 * <p>Real dev database (SpringBootTest, no mocks); fixture posture follows
 * {@link PerfReportNotifyTest} (PERFSLA prefix, LIKE cleanup).
 */
@SpringBootTest(classes = CoreApplication.class)
@org.springframework.test.context.TestPropertySource(properties = "testpike.scheduling.enabled=false")
class PerfRunSlaBreachAlertTest {

    @Autowired
    private PerfReportNotifyService perfReportNotifyService;

    @Autowired
    private PerfScenarioService perfScenarioService;

    @Autowired
    private PerfRunService perfRunService;

    @Autowired
    private PerfMetricService perfMetricService;

    @Autowired
    private PerfRunResultFinalizer perfRunResultFinalizer;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;
    private long envId;
    private long operatorId;
    private long ownerId;

    @BeforeEach
    void seedFixtures() {
        cleanupRows();
        // Fresh-install CI has no runner process online with an engine face,
        // so the perf launch gate would reject every run: seed one capable seat.
        SharedDbFixtureSweep.seedCapableExecutor(jdbcTemplate, "perf-sla-ex-",
                "GATLING", "K6");
        long uniq = System.nanoTime() % 1_000_000_000L;
        operatorId = seedUser("perfsop_" + uniq);
        ownerId = seedUser("perfspo_" + uniq);
        projectId = FixtureInserts.insertReturningId(jdbcTemplate, "INSERT INTO tp_project (name, code, status, test_owner_id, created_by) "
                        + "VALUES (?, ?, 1, ?, ?)", "SLA红灯测试项目_" + uniq, "PERFSLA" + uniq, ownerId, operatorId);
        envId = FixtureInserts.insertReturningId(jdbcTemplate, "INSERT INTO tp_project_env (project_id, name, env_type, base_url) "
                + "VALUES (?, '性能-SLA红灯测', 1, 'http://perf-target.local/api')", projectId);
        jdbcTemplate.update("INSERT INTO tp_perf_whitelist (project_id, env_id, max_concurrency, allowed_modes) VALUES (?, ?, 1000, '[]'::jsonb)", projectId, envId);
    }

    @AfterEach
    void cleanup() {
        cleanupRows();
    }

    private void cleanupRows() {
        SharedDbFixtureSweep.executorsByNamePrefixes(jdbcTemplate, "perf-sla-ex-");
        jdbcTemplate.update("DELETE FROM tp_notification WHERE dedup_key LIKE 'PERF_SLA_BREACH:%'");
        jdbcTemplate.update("DELETE FROM tp_notification_pref WHERE event_type = 'PERF_SLA_BREACH'");
        // Fixture-scoped teardown through the shared sweep (R40): the FK tree
        // reaches tp_perf_* / tp_project_env AND tp_exec_task — the hand-written
        // DELETE list used to omit tp_exec_task, leaking pending task rows that
        // later suites' claim walks picked up (seen in fresh-install CI triage).
        SharedDbFixtureSweep.byProjectList(jdbcTemplate, jdbcTemplate.queryForList(
                "SELECT id FROM tp_project WHERE code LIKE 'PERFSLA%'", Long.class));
        jdbcTemplate.update("DELETE FROM tp_user WHERE username LIKE 'perfsop_%' OR username LIKE 'perfspo_%'");
    }

    private long seedUser(String username) {
        // Read the id back through the shared RETURNING helper: an
        // ORDER BY id DESC lookup can return another suite's explicit-id
        // fixture row on a fresh-install database, collapsing operator and
        // owner onto one user and shrinking the recipient set to one card.
        return FixtureInserts.insertReturningId(jdbcTemplate,
                "INSERT INTO tp_user (username, password_hash, display_name, role, status) "
                        + "VALUES (?, 'x', ?, 1, 1)", username, "红灯用户" + username);
    }

    /** Create a scenario, launch it, feed two aggregate seconds (second 1 carries an auth point). */
    private long launchRunWithFrames(Map<String, Double> sla) {
        long scenarioId = perfScenarioService.create(projectId, new PerfScenarioInput(
                "SLA红灯场景_" + System.nanoTime(), envId, "CONSTANT_RPS",
                Map.of("targetRps", 50, "durationSeconds", 600), null, null,
                List.of(Map.of("action", "http.request", "method", "GET",
                        "url", "http://perf-target.local/api/orders")), sla));
        long runId = perfRunService.launch(projectId, scenarioId, operatorId);
        perfMetricService.recordFrames(projectId, runId, List.of(
                new PerfMetricFrame(0, 40.0, 120.0, 0.02, null, null, null, 35.0, 4.0),
                new PerfMetricFrame(1, 60.0, 200.0, 0.08, null, null, null, null, null)));
        return runId;
    }

    private List<Map<String, Object>> breachCardsOf(long runId) {
        return jdbcTemplate.queryForList(
                "SELECT user_id, event_type, title, content, dedup_key FROM tp_notification "
                        + "WHERE dedup_key LIKE ? ORDER BY user_id", "PERF_SLA_BREACH:" + runId + ":%");
    }

    /** ① Red run: both recipients get a card whose body names the breached gate in plain words. */
    @Test
    void redRunPushesBreachCardNamingTheBreachedGate() {
        long runId = launchRunWithFrames(Map.of("p95Ms", 150.0));

        perfReportNotifyService.sendSlaBreachCard(projectId, runId);

        List<Map<String, Object>> cards = breachCardsOf(runId);
        assertThat(cards).hasSize(2);
        assertThat(cards).extracting(r -> ((Number) r.get("user_id")).longValue())
                .containsExactlyInAnyOrder(operatorId, ownerId);
        assertThat(cards).allSatisfy(card -> {
            assertThat((String) card.get("event_type")).isEqualTo("PERF_SLA_BREACH");
            assertThat((String) card.get("title"))
                    .contains("压测运行 #" + runId).contains("SLA 门禁越线");
            String content = (String) card.get("content");
            // Verdict conclusion (gate name) + explicit breached-gate listing
            assertThat(content).contains("压测响应时间 P95").contains("超阈项");
            // Human-terms discipline: no JSON dump in the notification body
            assertThat(content).doesNotContain("{").doesNotContain("}");
        });
    }

    /** ② Green run: honest silence — nobody gets alerted about a passing run. */
    @Test
    void greenRunStaysSilent() {
        long runId = launchRunWithFrames(Map.of("p95Ms", 500.0));

        perfReportNotifyService.sendSlaBreachCard(projectId, runId);

        assertThat(breachCardsOf(runId)).isEmpty();
    }

    /** ③ No SLA gates on the pinned snapshot: judge skips gates → passed=true → zero cards.
     *  A missing threshold never becomes a fabricated red light. */
    @Test
    void runWithoutSlaGatesNeverAlerts() {
        long runId = launchRunWithFrames(null);

        perfReportNotifyService.sendSlaBreachCard(projectId, runId);

        assertThat(breachCardsOf(runId)).isEmpty();
    }

    /** ④ Replay of the same run dedups to the same recipient set (no storm), and the
     *  automatic breach prefix never collides with the manual conclusion-card prefix —
     *  a red run keeps its manual card even after the breach alert already landed. */
    @Test
    void replayDedupsAndPrefixStaysSeparateFromManualCard() {
        long runId = launchRunWithFrames(Map.of("p95Ms", 150.0));

        perfReportNotifyService.sendSlaBreachCard(projectId, runId);
        perfReportNotifyService.sendSlaBreachCard(projectId, runId);

        assertThat(breachCardsOf(runId)).hasSize(2);
        // Manual card on the same red run still lands (prefix spectrum not shared)
        perfReportNotifyService.sendCard(projectId, runId);
        List<Map<String, Object>> manual = jdbcTemplate.queryForList(
                "SELECT dedup_key FROM tp_notification WHERE dedup_key LIKE ?",
                "PERF_REPORT_CARD:" + runId + ":%");
        assertThat(manual).hasSize(2);
    }

    /** ⑤ Unsubscribed recipient is filtered at the generation single point (D3 pref
     *  matrix) — the other recipient still gets the card. */
    @Test
    void unsubscribedRecipientIsFiltered() {
        long runId = launchRunWithFrames(Map.of("p95Ms", 150.0));
        jdbcTemplate.update("INSERT INTO tp_notification_pref (user_id, event_type, enabled, updated_at) "
                        + "VALUES (?, 'PERF_SLA_BREACH', 0, now())", ownerId);

        perfReportNotifyService.sendSlaBreachCard(projectId, runId);

        List<Map<String, Object>> cards = breachCardsOf(runId);
        assertThat(cards).hasSize(1);
        assertThat(((Number) cards.get(0).get("user_id")).longValue()).isEqualTo(operatorId);
    }

    /** ⑥ Breaker stop (perfStatus=3) terminalized through the result finalizer fires the
     *  alert from the automatic channel — no user action involved. */
    @Test
    void breakerStopThroughResultFinalizerFiresAlert() {
        long scenarioId = perfScenarioService.create(projectId, new PerfScenarioInput(
                "SLA红灯熔断场景_" + System.nanoTime(), envId, "CONSTANT_RPS",
                Map.of("targetRps", 50, "durationSeconds", 600), null, null,
                List.of(Map.of("action", "http.request", "method", "GET",
                        "url", "http://perf-target.local/api/orders")), Map.of("p95Ms", 150.0)));
        long runId = perfRunService.launch(projectId, scenarioId, operatorId);

        perfRunResultFinalizer.onResultSubmitted(5, projectId,
                "{\"kind\":\"perf-run\",\"runId\":" + runId + ",\"engine\":\"K6\"}",
                new ExecResultInput(7000L, "tok-perf-sla", "fail", null, "熔断触发",
                        null, null, 300000, null, null, null, null, null, null,
                        List.of(Map.of("secondOffset", 0, "rps", 40.0, "p95Ms", 300,
                                "errorRate", 0.02)),
                        null, 3));

        assertThat(breachCardsOf(runId)).hasSize(2);
    }
}
