package com.sixbean.testpike.core.perf.domain.component;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.executor.api.dto.ExecResultInput;
import com.sixbean.testpike.core.perf.api.dto.PerfScenarioInput;
import com.sixbean.testpike.core.perf.domain.service.PerfMetricService;
import com.sixbean.testpike.core.perf.domain.service.PerfRunService;
import com.sixbean.testpike.core.perf.domain.service.PerfScenarioService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;


@SpringBootTest(classes = CoreApplication.class)
@org.springframework.test.context.TestPropertySource(properties = "testpike.scheduling.enabled=false")
class PerfRunResultFinalizerTest {

    @Autowired
    private PerfRunResultFinalizer perfRunResultFinalizer;

    @Autowired
    private PerfMetricService perfMetricService;

    @Autowired
    private PerfScenarioService perfScenarioService;

    @Autowired
    private PerfRunService perfRunService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;
    private long envId;

    @BeforeEach
    void seedFixtures() {
        cleanupRows();
        long uniq = System.nanoTime() % 1_000_000_000L;
        jdbcTemplate.update("INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "压测回传测试项目_" + uniq, "PERFRSLT" + uniq);
        projectId = lastId("tp_project");
        jdbcTemplate.update("INSERT INTO tp_project_env (project_id, name, env_type, base_url) "
                        + "VALUES (?, '性能-回传测', 1, 'http://perf-target.local/api')",
                projectId);
        envId = lastId("tp_project_env");
        jdbcTemplate.update("INSERT INTO tp_perf_whitelist (project_id, env_id, max_concurrency, allowed_modes) VALUES (?, ?, 1000, '[]'::jsonb)", projectId, envId);
    }

    @AfterEach
    void cleanup() {
        cleanupRows();
    }

    private void cleanupRows() {
        jdbcTemplate.update("DELETE FROM tp_perf_sample WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PERFRSLT%')");
        jdbcTemplate.update("DELETE FROM tp_perf_metric WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PERFRSLT%')");
        jdbcTemplate.update("DELETE FROM tp_perf_run WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PERFRSLT%')");
        jdbcTemplate.update("DELETE FROM tp_perf_scenario WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PERFRSLT%')");
        jdbcTemplate.update("DELETE FROM tp_perf_whitelist WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PERFRSLT%')");
        jdbcTemplate.update("DELETE FROM tp_project_env WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PERFRSLT%')");
        jdbcTemplate.update("DELETE FROM tp_project WHERE code LIKE 'PERFRSLT%'");
    }

    private long lastId(String table) {
        return jdbcTemplate.queryForObject("SELECT max(id) FROM " + table, Long.class);
    }

    private long newRun() {
        long scenarioId = perfScenarioService.create(projectId, new PerfScenarioInput(
                "回传契约场景", envId, "RAMP",
                Map.of("startRps", 10, "peakRps", 100, "rampSeconds", 60,
                        "holdSeconds", 120, "durationSeconds", 300),
                null, null,
                List.of(Map.of("action", "http.request", "method", "GET",
                        "url", "http://perf-target.local/api/orders")), null));
        return perfRunService.launch(projectId, scenarioId, null);
    }

    private String envelope(long runId) {
        return "{\"kind\":\"perf-run\",\"runId\":" + runId + ",\"engine\":\"K6\"}";
    }

    private ExecResultInput input(String result, String errorDetail,
                                  List<Map<String, Object>> frames,
                                  List<Map<String, Object>> events,
                                  Integer perfStatus) {
        return new ExecResultInput(7000L, "tok-perf", result, null, errorDetail,
                null, null, 300000, null, null, null, null, null, null,
                frames, events, perfStatus);
    }

    
    @Test
    void result_认证分桶键_透传入列且缺键不顶零() {
        long runId = newRun();

        perfRunResultFinalizer.onResultSubmitted(5, projectId, envelope(runId),
                input("pass", null,
                        List.of(
                                Map.of("secondOffset", 0, "rps", 10.5, "p95Ms", 88,
                                        "errorRate", 0.0, "authMs", 240.5, "authCount", 3.0),
                                Map.of("secondOffset", 1, "rps", 55.2, "p95Ms", 120,
                                        "errorRate", 0.01)),
                        null, null));

        List<Map<String, Object>> rows = perfMetricService.listFrames(projectId, runId);
        assertThat(rows).hasSize(2);
        assertThat((Number) rows.get(0).get("auth_ms")).isNotNull();
        assertThat(((Number) rows.get(0).get("auth_ms")).doubleValue()).isEqualTo(240.5);
        assertThat(((Number) rows.get(0).get("auth_count")).doubleValue()).isEqualTo(3.0);
        assertThat(rows).element(1).extracting(r -> r.get("auth_ms")).isNull();
        assertThat(rows).element(1).extracting(r -> r.get("auth_count")).isNull();
    }

    /** stage-b: bucket columns (reqCount/failCount/durationSumMs/hist/errorClass/stepKey)
 * ride the result channel and must land in the first-class columns — the old nine-key
 * mapping silently dropped them, so full-run recomputation never saw executor buckets.
 * Missing keys stay NULL (legacy executor shape, fallback caliber). */
    @Test
    void result_桶列宽键回传_透传入一等列且缺键不顶零() {
        long runId = newRun();

        perfRunResultFinalizer.onResultSubmitted(5, projectId, envelope(runId),
                input("pass", null,
                        List.of(
                                Map.of("secondOffset", 0, "rps", 10.0, "p95Ms", 88,
                                        "errorRate", 0.1,
                                        "reqCount", 10L, "failCount", 1L,
                                        "durationSumMs", 640.0,
                                        "hist", List.of(0, 0, 0, 0, 0, 3, 0, 0, 0, 7,
                                                0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                                                0, 0, 0, 0, 0, 0, 0),
                                        "errorClass", Map.of("timeout", 0, "refused", 0,
                                                "http4xx", 0, "http5xx", 1, "biz", 0),
                                        "stepKey", ""),
                                Map.of("secondOffset", 1, "rps", 5.0, "p95Ms", 120,
                                        "errorRate", 0.0)),
                        null, null));

        List<Map<String, Object>> rows = perfMetricService.listFrames(projectId, runId);
        assertThat(rows).hasSize(2);
        Map<String, Object> wide = rows.get(0);
        assertThat(((Number) wide.get("req_count")).longValue()).isEqualTo(10L);
        assertThat(((Number) wide.get("fail_count")).longValue()).isEqualTo(1L);
        assertThat(((Number) wide.get("duration_sum_ms")).doubleValue()).isEqualTo(640.0);
        assertThat(String.valueOf(wide.get("hist"))).contains("3");
        assertThat(String.valueOf(wide.get("error_class"))).contains("http5xx");
        // Second frame without bucket keys: first-class columns stay NULL (legacy
        // executor shape — the prerequisite of the fallback caliber, never 0-filled).
        Map<String, Object> legacy = rows.get(1);
        assertThat(legacy.get("req_count")).isNull();
        assertThat(legacy.get("hist")).isNull();
        assertThat(legacy.get("error_class")).isNull();
        assertThat(String.valueOf(legacy.get("step_key"))).isEmpty();
    }

    /** stage-b: step-split frames share a second with the aggregate frame; the
 * report/live caliber reads aggregate frames only (step_key='') so the weighted
 * error rate and RPS never double-count the same requests. */
    @Test
    void result_分步帧与聚合帧同秒_聚合口径读面只出聚合帧() {
        long runId = newRun();

        perfRunResultFinalizer.onResultSubmitted(5, projectId, envelope(runId),
                input("pass", null,
                        List.of(
                                Map.of("secondOffset", 0, "rps", 5.0, "p95Ms", 50,
                                        "errorRate", 0.0, "reqCount", 5L,
                                        "failCount", 0L, "durationSumMs", 250.0, "stepKey", ""),
                                Map.of("secondOffset", 0, "rps", 2.0, "p95Ms", 40,
                                        "errorRate", 0.0, "reqCount", 2L,
                                        "failCount", 0L, "durationSumMs", 80.0, "stepKey", "step1"),
                                Map.of("secondOffset", 0, "rps", 3.0, "p95Ms", 60,
                                        "errorRate", 0.0, "reqCount", 3L,
                                        "failCount", 0L, "durationSumMs", 170.0, "stepKey", "step2")),
                        null, null));

        // Full reader sees all three frames (the run x second x step anchor holds).
        assertThat(perfMetricService.listFrames(projectId, runId)).hasSize(3);
        // Aggregate reader yields only the step_key='' frame (single curve/verdict caliber).
        List<Map<String, Object>> agg = perfMetricService.listAggregateFrames(projectId, runId);
        assertThat(agg).hasSize(1);
        assertThat(((Number) agg.get(0).get("req_count")).longValue()).isEqualTo(5L);
    }

 
    @Test
    void result_pass回传_帧事件入库且运行正常结束() {
        long runId = newRun();

        perfRunResultFinalizer.onResultSubmitted(5, projectId, envelope(runId),
                input("pass", null,
                        List.of(
                                Map.of("secondOffset", 0, "rps", 10.5, "p95Ms", 88,
                                        "errorRate", 0.0),
                                Map.of("secondOffset", 1, "rps", 55.2, "p95Ms", 120,
                                        "errorRate", 0.01),
                                Map.of("secondOffset", 2, "rps", 100.1, "p95Ms", 210,
                                        "errorRate", 0.02, "ttftMs", 310.0)),
                        List.of(
                                Map.of("secondOffset", 0, "eventKind", "ENGINE_LAUNCH",
                                        "detail", "k6 已起压"),
                                Map.of("secondOffset", 2, "eventKind", "SLA_BREACH",
                                        "detail", "P95 超门禁")),
                        null));

        assertThat(perfMetricService.listFrames(projectId, runId)).hasSize(3);
        assertThat(perfMetricService.listFrames(projectId, runId))
                .element(2).extracting(r -> r.get("second_offset")).isEqualTo(2);
        assertThat(perfMetricService.listFrames(projectId, runId))
                .element(0).extracting(r -> r.get("ttft_ms")).isNull();
        assertThat(perfMetricService.listEvents(projectId, runId)).hasSize(2);
        assertThat(runStatus(runId)).isEqualTo(2);
    }

 
    @Test
    void result_熔断回传_运行置熔断停() {
        long runId = newRun();

        perfRunResultFinalizer.onResultSubmitted(5, projectId, envelope(runId),
                input("fail", "错误率超阈值自动停压",
                        List.of(Map.of("secondOffset", 0, "rps", 20.0,
                                "p95Ms", 500, "errorRate", 0.35)), null, 3));

        assertThat(runStatus(runId)).isEqualTo(3);
        assertThat(runStopReason(runId)).contains("错误率超阈值自动停压");
    }

    /**
     * U-10 ingest-point masking on the stop reason: executor errorDetail is
     * free text and lands verbatim in tp_perf_run.stop_reason. It runs through
     * the shared phone/e-mail spectrum at persistence time, so a run stopped
     * by an error quoting an account phone number never stores it in the raw.
     */
    @Test
    void result_停因自由文本_入库时按共享谱打码() {
        long runId = newRun();

        perfRunResultFinalizer.onResultSubmitted(5, projectId, envelope(runId),
                input("fail", "operator 13912345678 aborted via li@corp.cn", null, null, null));

        String reason = runStopReason(runId);
        assertThat(reason).doesNotContain("13912345678");
        assertThat(reason).doesNotContain("li@corp.cn");
        assertThat(reason).contains("139****5678");
    }

    
    @Test
    void result_失败无明示态_映射人工停透传停因() {
        long runId = newRun();

        perfRunResultFinalizer.onResultSubmitted(5, projectId, envelope(runId),
                input("fail", "k6 二进制缺失，未起压", null, null, null));

        assertThat(runStatus(runId)).isEqualTo(4);
        assertThat(runStopReason(runId)).contains("k6 二进制缺失");
    }

    
    @Test
    void result_信封缺runId_静默跳过不炸主链() {
        long runId = newRun();

        perfRunResultFinalizer.onResultSubmitted(5, projectId,
                "{\"kind\":\"perf-run\",\"engine\":\"K6\"}",
                input("pass", null,
                        List.of(Map.of("secondOffset", 0, "rps", 1.0, "p95Ms", 1,
                                "errorRate", 0.0)), null, null));

        assertThat(runStatus(runId)).isEqualTo(1);
        assertThat(framesOf(runId)).isZero();
    }

    
    @Test
    void result_重复回传竞态_终态不被改写不炸主链() {
        long runId = newRun();
        perfRunResultFinalizer.onResultSubmitted(5, projectId, envelope(runId),
                input("pass", null, List.of(Map.of(
                        "secondOffset", 0, "rps", 5.0, "p95Ms", 40, "errorRate", 0.0)),
                        null, null));

        perfRunResultFinalizer.onResultSubmitted(5, projectId, envelope(runId),
                input("pass", null, List.of(Map.of(
                        "secondOffset", 0, "rps", 99.0, "p95Ms", 999, "errorRate", 0.9)),
                        null, null));

        assertThat(framesOf(runId)).isEqualTo(1);
        assertThat(runStatus(runId)).isEqualTo(2);
    }

    
    @Test
    void result_非压测任务_零副作用() {
        long runId = newRun();

        perfRunResultFinalizer.onResultSubmitted(4, projectId,
                "{\"kind\":\"api-test-run\",\"runId\":1}", input("pass", null, null, null, null));

        assertThat(runStatus(runId)).isEqualTo(1);
        assertThat(framesOf(runId)).isZero();
    }

    /** A-9 dataLoss truth annotation: the run lasted past its declared duration
 * but the result channel shipped only the first seconds — the tail samples are
 * gone and the curve must say so (a truth event + stop-reason note), never old
 * values silently impersonating a complete run. */
    @Test
    void result_尾秒覆盖缺口_落dataLoss事件并标注停因() {
        long runId = newRun(); // RAMP ramp 60 + hold 120 = 180s declared duration
        // Simulate a run that actually pressed for 300s (declared ceiling 180s) while
        // only seconds 0..2 reached core (crashed tail flush).
        backdateStartedAt(runId, 300);

        perfRunResultFinalizer.onResultSubmitted(5, projectId, envelope(runId),
                input("pass", null,
                        List.of(
                                Map.of("secondOffset", 0, "rps", 10.0, "p95Ms", 80,
                                        "errorRate", 0.0),
                                Map.of("secondOffset", 1, "rps", 10.0, "p95Ms", 80,
                                        "errorRate", 0.0),
                                Map.of("secondOffset", 2, "rps", 10.0, "p95Ms", 80,
                                        "errorRate", 0.0)),
                        null, null));

        assertThat(runStatus(runId)).isEqualTo(2);
        List<String> kinds = perfMetricService.listEvents(projectId, runId).stream()
                .map(r -> String.valueOf(r.get("event_kind"))).toList();
        assertThat(kinds).contains("data-loss");
        assertThat(runStopReason(runId)).contains("尾部采样缺失");
    }

    /** Healthy counterpart: tail coverage complete (frames reach the declared duration)
     *  adds no data-loss event and keeps the default stop reason untouched. */
    @Test
    void result_尾秒覆盖完整_不落dataLoss事件() {
        long runId = newRun();
        backdateStartedAt(runId, 200); // elapsed 200s > declared 180s, run finished its span

        List<Map<String, Object>> full = new java.util.ArrayList<>();
        for (int sec = 0; sec < 180; sec++) {
            full.add(Map.of("secondOffset", sec, "rps", 10.0, "p95Ms", 80, "errorRate", 0.0));
        }
        perfRunResultFinalizer.onResultSubmitted(5, projectId, envelope(runId),
                input("pass", null, full, null, null));

        assertThat(runStatus(runId)).isEqualTo(2);
        assertThat(perfMetricService.listEvents(projectId, runId)).isEmpty();
        assertThat(runStopReason(runId)).isEqualTo("压测运行正常结束");
    }

    private void backdateStartedAt(long runId, int secondsAgo) {
        jdbcTemplate.update("UPDATE tp_perf_run SET started_at = now() - make_interval(secs => ?) "
                + "WHERE id = ?", secondsAgo, runId);
    }

    private int runStatus(long runId) {
        return jdbcTemplate.queryForObject("SELECT status FROM tp_perf_run WHERE id = ?",
                Integer.class, runId);
    }

    private String runStopReason(long runId) {
        return jdbcTemplate.queryForObject(
                "SELECT COALESCE(stop_reason,'') FROM tp_perf_run WHERE id = ?",
                String.class, runId);
    }

    private int framesOf(long runId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_perf_metric WHERE run_id = ?", Integer.class, runId);
    }
}
