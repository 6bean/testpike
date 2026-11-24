package com.sixbean.testpike.core.perf.domain.component;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.perf.api.dto.PerfScenarioInput;
import com.sixbean.testpike.core.perf.domain.service.PerfScenarioService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


@SpringBootTest(classes = CoreApplication.class)
@org.springframework.test.context.TestPropertySource(properties = "testpike.scheduling.enabled=false")
class K6AdapterTest {

    @Autowired
    private K6Adapter k6Adapter;

    @Autowired
    private GatlingAdapter gatlingAdapter;

    @Autowired
    private PerfScenarioService perfScenarioService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder configCacheHolder;

    @Autowired
    private com.sixbean.testpike.core.executor.domain.component.ArtifactStorage artifactStorage;

    private long projectId;
    private long envId;

    @BeforeEach
    void seedFixtures() {
        cleanupRows();
        long uniq = System.nanoTime() % 1_000_000_000L;
        String projectCode = "K6ADP" + uniq;
        // Ids are read back through RETURNING so a foreign fixture row with an
        // overridden id can never out-rank max(id) and re-parent these rows.
        projectId = com.sixbean.testpike.core.support.FixtureInserts.insertReturningId(jdbcTemplate,
                "INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "k6适配测试项目_" + uniq, projectCode);
        envId = com.sixbean.testpike.core.support.FixtureInserts.insertReturningId(jdbcTemplate,
                "INSERT INTO tp_project_env (project_id, name, env_type, base_url) "
                        + "VALUES (?, '性能-k6适配测', 1, 'http://perf-target.local/api')",
                projectId);
    }

    @AfterEach
    void cleanup() {
        cleanupRows();
    }

    private void cleanupRows() {
        jdbcTemplate.update("DELETE FROM tp_perf_run WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'K6ADP%')");
        jdbcTemplate.update("DELETE FROM tp_perf_scenario WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'K6ADP%')");
        jdbcTemplate.update("DELETE FROM tp_project_env WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'K6ADP%')");
        jdbcTemplate.update("DELETE FROM tp_project WHERE code LIKE 'K6ADP%'");
    }

    private static List<Map<String, Object>> requestSteps() {
        return List.of(Map.of("action", "http.request", "method", "POST", "url", "{{ENV:BASE}}/orders"));
    }

    private Map<String, Object> savedView(String model, Map<String, Integer> config) {
        return savedView(model, config, requestSteps());
    }

    private Map<String, Object> savedView(String model, Map<String, Integer> config,
                                          List<Map<String, Object>> steps) {
        long id = perfScenarioService.create(projectId, new PerfScenarioInput(
                "k6适配_" + model + "_" + (System.nanoTime() % 1_000_000L), envId, model, config,
                null, null, steps, null));
        return perfScenarioService.view(projectId, id);
    }

    /**
 * read path (single source for every golden-script assertion):
 * the compile handle is a project-routed object key — pull the bundle bytes
 * through the SAME ArtifactStorage route the production write used (read/
 * write same route: a broken route fails here, not silently) and return the
 * k6 script text.
 */
    private String k6ScriptOf(String handle) throws Exception {
        byte[] bundle = artifactStorage.readBytesQuietly(handle);
        assertThat(bundle).as("bundle readable through the write route: %s", handle)
                .isNotNull();
        return PerfArtifactBundleProbe.textOf(
                PerfArtifactBundleProbe.entries(bundle), ".js");
    }

    /**
 * Materialize the bundle next to its dataset sidecar and hand back the
 * on-disk script path for the real-engine probe (`k6 inspect` needs a file
 * path; open of the sidecar resolves relative to it — mirrors what the
 * executor does in .
 */
    private java.nio.file.Path k6ScriptPathOf(String handle) throws Exception {
        byte[] bundle = artifactStorage.readBytesQuietly(handle);
        assertThat(bundle).as("bundle readable through the write route: %s", handle)
                .isNotNull();
        var entries = PerfArtifactBundleProbe.entries(bundle);
        String scriptName = entries.keySet().stream()
                .filter(n -> n.endsWith(".js")).findFirst()
                .orElseThrow(() -> new AssertionError("bundle carries no .js entry"));
        return PerfArtifactBundleProbe.materialize("k6", bundle).resolve(scriptName);
    }

    // ---------- (A-10): compiled artifact = project-routed object-storage bundle ----------

    /**
 * RED anchor : compile must hand back an object-storage key
 * under the perf-artifact scene of the OWNING project (not a shared temp
 * path), and the bundle bytes must be readable through the same route —
 * a zip carrying the k6 script (and the dataset sidecar when bound).
 * The dispatch envelope then carries this key, so a stale local path can
 * never be signed into a run order.
 */
    @Test
    void compileProducesProjectRoutedObjectStorageBundle() throws Exception {
        String artifact = k6Adapter.compile(savedView("CONSTANT_RPS",
                Map.of("targetRps", 5, "durationSeconds", 60)));
        assertThat(artifact).contains("/" + projectId + "/perf-artifact/").endsWith(".zip");
        byte[] bundle = artifactStorage.readBytesQuietly(artifact);
        assertThat(bundle).as("bundle readable through the write route").isNotNull();
        var entries = PerfArtifactBundleProbe.entries(bundle);
        assertThat(PerfArtifactBundleProbe.textOf(entries, ".js"))
                .contains("constant-arrival-rate");
    }

    // ---------- B-3 : k6 weighted multi-link mix mapping ----------

    /**
     * A fully declared 80/20 mix compiles into ONE executor block per link with
     * the weight folded into that block's arrival rate (ceil keeps every leg
     * positive), each block pointing at its own step function via exec. This is
     * the capability the compile gate answers with mixSupported=true.
     */
    @Test
    void weightedMixCompilesOneExecutorBlockPerLink() throws Exception {
        String script = k6ScriptOf(k6Adapter.compile(savedView("CONSTANT_RPS",
                Map.of("targetRps", 10, "durationSeconds", 60), List.of(
                        Map.of("action", "http.request", "method", "POST",
                                "url", "{{ENV:BASE}}/orders", "transaction", "下单链路", "weight", 80),
                        Map.of("action", "http.request", "method", "GET",
                                "url", "{{ENV:BASE}}/search", "transaction", "查询链路", "weight", 20)))));
        // 80/20 of 10 RPS: the two legs press 8 and 2 — never a uniform 10/10.
        assertThat(script).contains("rate: 8").contains("rate: 2");
        assertThat(script).contains("exec: '");
        assertThat(script).contains("/orders").contains("/search");
    }

    /**
     * Grouped links without weights split the declared pressure evenly
     * (ceil(5/2)=3 per leg) — a well-defined shape the save gate already
     * stores, answered here by the compiler.
     */
    @Test
    void unweightedMixSplitsPressureEvenlyAcrossLinks() throws Exception {
        String script = k6ScriptOf(k6Adapter.compile(savedView("CONSTANT_RPS",
                Map.of("targetRps", 5, "durationSeconds", 60), List.of(
                        Map.of("action", "http.request", "method", "GET",
                                "url", "{{ENV:BASE}}/a", "transaction", "链路A"),
                        Map.of("action", "http.request", "method", "GET",
                                "url", "{{ENV:BASE}}/b", "transaction", "链路B")))));
        assertThat(script.split("rate: 3", -1).length - 1).isEqualTo(2);
    }

    /**
     * Steps sharing one transaction ride the SAME link function in declared
     * chain order (one iteration = the whole business journey); tag numbering
     * stays globally unique across links so the two legs never share a series.
     */
    @Test
    void multiStepLinkCompilesOneNamedFunctionInStepOrder() throws Exception {
        String script = k6ScriptOf(k6Adapter.compile(savedView("CONSTANT_RPS",
                Map.of("targetRps", 6, "durationSeconds", 60), List.of(
                        Map.of("action", "http.request", "method", "POST", "url", "{{ENV:BASE}}/o1",
                                "transaction", "下单", "weight", 50),
                        Map.of("action", "http.request", "method", "GET", "url", "{{ENV:BASE}}/o2",
                                "transaction", "下单", "weight", 50),
                        Map.of("action", "http.request", "method", "GET", "url", "{{ENV:BASE}}/s",
                                "transaction", "查询", "weight", 50)))));
        assertThat(script.indexOf("/o1")).isLessThan(script.indexOf("/o2"));
        assertThat(script.indexOf("/o2")).isLessThan(script.indexOf("/s"));
        assertThat(script).contains("name: \"step1\"").contains("name: \"step2\"")
                .contains("name: \"step3\"");
        // Flat legacy shape untouched: a mix NEVER emits a default export.
        assertThat(script).doesNotContain("export default function");
    }

    /** Real-engine load anchor for the mix shape (same posture as the model grid). */
    @Test
    void weightedMixScriptLoadsWithRealK6Inspect() throws Exception {
        String artifact = k6Adapter.compile(savedView("CONSTANT_RPS",
                Map.of("targetRps", 6, "durationSeconds", 30), List.of(
                        Map.of("action", "http.request", "method", "POST", "url", "{{ENV:BASE}}/orders",
                                "transaction", "下单", "weight", 60),
                        Map.of("action", "http.request", "method", "GET", "url", "{{ENV:BASE}}/search",
                                "transaction", "查询", "weight", 40))));
        Process probe = new ProcessBuilder("k6", "inspect", k6ScriptPathOf(artifact).toString())
                .redirectErrorStream(true).start();
        String out = new String(probe.getInputStream().readAllBytes());
        boolean loaded = probe.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
                && probe.exitValue() == 0;
        probe.destroyForcibly();
        assertThat(loaded).as("k6 inspect must load the mix script: %s", out).isTrue();
    }


    @Test
    void constantRpsCompilesGoldenK6Script() throws Exception {
        Map<String, Object> view = savedView("CONSTANT_RPS",
                Map.of("targetRps", 50, "durationSeconds", 600));
        String artifact = k6Adapter.compile(view);
        assertThat(artifact).endsWith(".zip");
        String script = k6ScriptOf(artifact);
        assertThat(script).contains("constant-arrival-rate");
        assertThat(script).contains("rate: 50");
        assertThat(script).contains("600s");
        assertThat(script).contains("orders");
    }

    @Test
    void allThreePressureFormsCompileDistinctExecutors() throws Exception {
        String constant = k6ScriptOf(k6Adapter.compile(
                savedView("CONSTANT_RPS", Map.of("targetRps", 20, "durationSeconds", 120))));
        String ramp = k6ScriptOf(k6Adapter.compile(
                savedView("RAMP", Map.of("startRps", 10, "peakRps", 300,
                        "rampSeconds", 120, "holdSeconds", 60))));
        String spike = k6ScriptOf(k6Adapter.compile(
                savedView("SPIKE", Map.of("baseRps", 20, "peakRps", 500, "durationSeconds", 300))));
        assertThat(constant).contains("constant-arrival-rate");
        assertThat(ramp).contains("ramping-arrival-rate")
                .contains("target: 300")
                .contains("hold");
        // A-12 fix : the pulse no longer maps RPS values onto ramping-vus stage
        // targets (unit confusion) — it compiles into ramping-arrival-rate sequential
        // rise/hold/fall/tail stages inside one durationSeconds window.
        assertThat(spike).doesNotContain("ramping-vus").doesNotContain("duration: '300s'")
                .contains("startRate: 20")
                .contains("{ target: 500, duration: '60s' }")
                .contains("{ target: 20, duration: '60s' }")
                .contains("{ target: 20, duration: '120s' }");
    }

    // ---------- B-2 : the four new pressure models map to k6 executors ----------

    /**
 * Real-engine load anchor : string-only
 * golden asserts once hid a field-name bug (ramping stages spell the field
 * `target`, not `targetRate` — k6 refuses to load the script otherwise), so
 * every model's compiled script must pass `k6 inspect` on the real binary.
 * The k6 binary is an optional user-installed runtime (AGPL process-boundary
 * ruling); an absent binary fails honestly here — the local press box and CI
 * images install it.
 */
    @Test
    void everyCompiledScriptLoadsInRealK6Engine() throws Exception {
        Map<String, Map<String, Integer>> models = new java.util.LinkedHashMap<>();
        models.put("CONSTANT_RPS", Map.of("targetRps", 5, "durationSeconds", 30));
        models.put("RAMP", Map.of("startRps", 2, "peakRps", 10, "rampSeconds", 30, "holdSeconds", 20));
        models.put("SPIKE", Map.of("baseRps", 2, "peakRps", 50, "durationSeconds", 120));
        models.put("FIXED_VU", Map.of("vu", 3, "durationSeconds", 30, "thinkTimeMs", 500));
        models.put("LADDER", Map.of("startRps", 5, "stepRps", 5, "stepSeconds", 30, "steps", 2));
        models.put("BUSINESS_CURVE", Map.of("peakRps", 40, "durationSeconds", 86400, "curve9", 80));
        models.put("SOAK", Map.of("targetRps", 3, "durationHours", 1));
        for (Map.Entry<String, Map<String, Integer>> entry : models.entrySet()) {
            String artifact = k6Adapter.compile(savedView(entry.getKey(), entry.getValue()));
            Process probe = new ProcessBuilder("k6", "inspect", k6ScriptPathOf(artifact).toString())
                    .redirectErrorStream(true).start();
            String out = new String(probe.getInputStream().readAllBytes());
            boolean loaded = probe.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
                    && probe.exitValue() == 0;
            probe.destroyForcibly();
            assertThat(loaded)
                    .as("k6 inspect must load the %s script (engine refuses unknown fields): %s",
                            entry.getKey(), out)
                    .isTrue();
        }
    }

    /**
     * FIXED_VU closed pool: with a wall-clock window it maps to constant-vus
     * (vus stay pinned, each user loops its iteration until the window closes);
     * with loops only it maps to per-vu-iterations (the iterations field is the
     * per-user loop bound — the only k6 executor whose termination is a count).
     * thinkTimeMs becomes an in-iteration sleep between steps.
     */
    @Test
    void fixedVuCompilesClosedPoolWindowAndLoopBoundedForms() throws Exception {
        String window = k6ScriptOf(k6Adapter.compile(savedView("FIXED_VU",
                Map.of("vu", 50, "durationSeconds", 300, "thinkTimeMs", 2000))));
        assertThat(window).contains("constant-vus")
                .contains("vus: 50")
                .contains("duration: '300s'")
                .contains("sleep(2)");
        String looped = k6ScriptOf(k6Adapter.compile(savedView("FIXED_VU",
                Map.of("vu", 30, "loops", 100))));
        assertThat(looped).contains("per-vu-iterations")
                .contains("vus: 30")
                .contains("iterations: 100");
    }

    /**
 * LADDER search: ramping-arrival-rate whose stages walk startRps + i x stepRps
 * for i in 1..steps, each stepSeconds long — the same top the budget and the
 * segment divisor already claim (startRps + stepRps x steps).
 */
    @Test
    void ladderStagesStepUpToTheSearchTop() throws Exception {
        String script = k6ScriptOf(k6Adapter.compile(savedView("LADDER",
                Map.of("startRps", 50, "stepRps", 20, "stepSeconds", 60, "steps", 3))));
        assertThat(script).contains("ramping-arrival-rate")
                .contains("startRate: 50")
                .contains("{ target: 70, duration: '60s' }")
                .contains("{ target: 90, duration: '60s' }")
                .contains("{ target: 110, duration: '60s' }")
                .contains("preAllocatedVUs: 110");
    }

    /**
     * BUSINESS_CURVE: hourly constant-arrival-rate segments, rate = peakRps x
     * weight/100 (weights are percentages of the peak); a zero/absent weight is
     * an idle hour — the segment is skipped, never emitted as rate 0 (a 0-rate
     * executor would fake a paused-but-loaded stage).
     */
    @Test
    void businessCurveMapsHourWeightsToSegmentsAndSkipsIdleHours() throws Exception {
        Map<String, Integer> config = new HashMap<>();
        config.put("peakRps", 200);
        config.put("durationSeconds", 86400);
        config.put("curve9", 100);
        config.put("curve13", 50);
        String script = k6ScriptOf(k6Adapter.compile(savedView("BUSINESS_CURVE", config)));
        assertThat(script).contains("rate: 200").contains("rate: 100");
        // Idle hours never become segments; the active ones still ride the hourly grid.
        assertThat(script).doesNotContain("rate: 0").contains("duration: '3600s'");
    }

    /**
     * SOAK: one long constant-rate leg — durationHours is the only spelling of the
     * horizon (hours x 3600 seconds), the executor stays constant-arrival-rate.
     */
    @Test
    void soakCompilesConstantRateOverDeclaredHours() throws Exception {
        String script = k6ScriptOf(k6Adapter.compile(savedView("SOAK",
                Map.of("targetRps", 30, "durationHours", 12))));
        assertThat(script).contains("constant-arrival-rate")
                .contains("rate: 30")
                .contains("duration: '43200s'");
    }

    /**
     * A-12 VU budget coefficient (K8 key perf.vu-per-rps, default 1, range 1..10):
     * every arrival-rate executor pre-allocates ceil(peak RPS x coefficient) virtual
     * users; a dirty or out-of-range value falls back to 1 so a mistyped ceiling can
     * never explode the VU count on the press box.
     */
    @Test
    void vuPerRpsKeyScalesPreAllocatedVusAndDirtyValueFallsBack() throws Exception {
        Map<String, Object> view = savedView("SPIKE",
                Map.of("baseRps", 20, "peakRps", 500, "durationSeconds", 300));
        try {
            upsertVusPerRps("2");
            String script = k6ScriptOf(k6Adapter.compile(view));
            assertThat(script).contains("preAllocatedVUs: 1000");
            upsertVusPerRps("99");
            assertThat(k6ScriptOf(k6Adapter.compile(view)))
                    .contains("preAllocatedVUs: 500");
        } finally {
            // V278 seeded this key for real — restore the default instead of deleting
            // the migration-owned row.
            upsertVusPerRps("1");
        }
    }

    /** Transient config fixture (row deleted in the test's finally block). The
     *  cache reload mirrors the live hot-update path — a raw JDBC write never
     *  flows through ConfigService's cache.put, so the reader would otherwise
     *  keep seeing the boot-time value. */
    private void upsertVusPerRps(String value) {
        jdbcTemplate.update("""
                INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
                VALUES ('perf.vu-per-rps', ?, '系统', 'int', 0, '压测虚拟用户系数（测试期临时值）')
                ON CONFLICT (config_key) DO UPDATE SET config_value = EXCLUDED.config_value
                """, value);
        configCacheHolder.loadAll(jdbcTemplate);
    }

    @Test
    void urlWithQuotesAndBackslashesIsEscapedNotInjected() throws Exception {
        Map<String, Object> dirty = new HashMap<>(savedView("CONSTANT_RPS",
                Map.of("targetRps", 5, "durationSeconds", 60)));
        dirty.put("steps_json", "[{\"action\":\"http.request\",\"method\":\"GET\","
                + "\"url\":\"/a\\\";evil()//\"}]");
        String script = k6ScriptOf(k6Adapter.compile(dirty));
        assertThat(script).contains("\\\"");
        assertThat(script).doesNotContain(";\nevil");
    }


    @Test
    void headerMarkIsolationPropagatesXLoadTestHeaderIntoScript() throws Exception {
        Map<String, Object> view = new HashMap<>(savedView("CONSTANT_RPS",
                Map.of("targetRps", 5, "durationSeconds", 60)));
        view.put("isolation_mode", "HEADER_MARK");
        String script = k6ScriptOf(k6Adapter.compile(view));
        assertThat(script).contains("X-Load-Test");
    }

    @Test
    void scenarioWithoutIsolationDeclarationEmitsNoLoadTestHeader() throws Exception {
        String script = k6ScriptOf(k6Adapter.compile(savedView("CONSTANT_RPS",
                Map.of("targetRps", 5, "durationSeconds", 60))));
        assertThat(script).doesNotContain("X-Load-Test");
    }


    @Test
    void compileRejectsUiActionStepNamingTheAction() {
        Map<String, Object> dirty = new HashMap<>(savedView("CONSTANT_RPS",
                Map.of("targetRps", 5, "durationSeconds", 60)));
        dirty.put("steps_json", dirty.get("steps_json").toString().replace("http.request", "click"));
        assertThatThrownBy(() -> k6Adapter.compile(dirty))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("click");
    }

    /**
     * A-11 compile-stage depth: a non-HTTP non-UI action (sql.query) that used to
     * slip through the six-action whitelist would compile into an empty-URL http
     * call (fake traffic). The narrowed gate rejects it naming the offender and
     * the only allowed action.
     */
    @Test
    void compileRejectsNonHttpActionStepNamingActionAndAllowedSet() {
        Map<String, Object> dirty = new HashMap<>(savedView("CONSTANT_RPS",
                Map.of("targetRps", 5, "durationSeconds", 60)));
        dirty.put("steps_json", dirty.get("steps_json").toString()
                .replace("http.request", "sql.query"));
        assertThatThrownBy(() -> k6Adapter.compile(dirty))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("sql.query")
                .hasMessageContaining("http.request");
    }


    
    @Test
    void authStepCompilesIntoAuthNamedTagBusinessStepsKeepIndexNames() throws Exception {
        long id = perfScenarioService.create(projectId, new PerfScenarioInput(
                "k6适配_auth分桶_" + (System.nanoTime() % 1_000_000L), envId, "CONSTANT_RPS",
                Map.of("targetRps", 5, "durationSeconds", 60), null, null,
                List.of(Map.<String, Object>of("action", "http.request", "method", "POST",
                                "url", "/login", "auth", true),
                        Map.<String, Object>of("action", "http.request", "method", "GET",
                                "url", "/orders")),
                null));
        String script = k6ScriptOf(
                k6Adapter.compile(perfScenarioService.view(projectId, id)));
        assertThat(script).contains("name: \"auth\"");
        assertThat(script).contains("name: \"step2\"");
    }

    // ---------- Step slots (headers/body/expect) + fail-closed on inexpressible constructs ----------

    /**
     * Template consumption of the three step slots: step-declared request headers
     * flow into per-request options, a JSON body becomes the request payload
     * (Content-Type defaulted), expect.status maps to expectedStatuses (the
     * engine-native http_req_failed counting puts a business failure on the error
     * line — no control-plane second judgement), and expect.body rows compile
     * into k6 checks.
     */
    @Test
    void stepHeadersBodyAndExpectationsCompileIntoSlots() throws Exception {
        long id = perfScenarioService.create(projectId, new PerfScenarioInput(
                "k6适配_A3槽位_" + (System.nanoTime() % 1_000_000L), envId, "CONSTANT_RPS",
                Map.of("targetRps", 5, "durationSeconds", 60), null, null,
                List.of(Map.<String, Object>of("action", "http.request", "method", "POST",
                        "url", "/orders",
                        "headers", Map.of("X-Biz", "v1"),
                        "body", Map.of("sku", "a1"),
                        "expect", Map.of("status", 201,
                                "body", List.of(Map.of("path", "$.code",
                                        "matchType", "exact", "text", "OK"))))),
                null));
        String script = k6ScriptOf(
                k6Adapter.compile(perfScenarioService.view(projectId, id)));
        // Step-declared headers flow into per-request options (merged with isolation header).
        assertThat(script).contains("X-Biz");
        // Body slot: JSON payload serialized into the request, Content-Type defaulted.
        assertThat(script).contains("\\\"sku\\\"").contains("application/json");
        // expect.status -> expectedStatuses (engine-native http_req_failed counts the
        // business failure into the error line — A-4 single source, no second judgement).
        assertThat(script).contains("expectedStatuses: [201]");
        // expect.body rows -> k6 checks (jsonpath subset + step-qualified name).
        assertThat(script).contains("$.code").contains("check(res1");
    }

    /**
     * A-3 fail-closed: constructs the load template cannot express (poll waiting)
     * must be rejected at compile time naming the offender — a silently dropped
     * expectation would fake a green run under load.
     */
    @Test
    void compileRejectsPollWaitingNamingUnsupportedConstructs() {
        Map<String, Object> dirty = new HashMap<>(savedView("CONSTANT_RPS",
                Map.of("targetRps", 5, "durationSeconds", 60)));
        dirty.put("steps_json", "[{\"action\":\"http.request\",\"method\":\"GET\","
                + "\"url\":\"/orders\",\"poll\":{\"intervalMs\":1000,\"timeoutMs\":5000,"
                + "\"until\":\"match\"}}]");
        assertThatThrownBy(() -> k6Adapter.compile(dirty))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("poll");
    }

    /**
 * B-1 variable chain (DSL v2 : saveAs capture and {{VAR:}} references
 * compile into the k6 template — per-VU pool seeded by scalar-leaf expansion
 * (same key shape resp.field / resp[0] as the executor mirror), consumption
 * through the single in-script resolver, undefined reference fails fast in
 * plain words. A silently dropped capture would fake traffic that does not
 * match the declared step.
 */
    @Test
    void saveAsAndVarReferencesCompileIntoPerVuVarChain() throws Exception {
        long id = perfScenarioService.create(projectId, new PerfScenarioInput(
                "变量链_k6_" + (System.nanoTime() % 1_000_000L), envId, "CONSTANT_RPS",
                Map.of("targetRps", 5, "durationSeconds", 60), null, null,
                List.of(
                        Map.of("action", "http.request", "method", "POST", "url", "/login",
                                "body", Map.of("user", "u1"), "saveAs", "resp1"),
                        Map.of("action", "http.request", "method", "GET",
                                "url", "/orders?id={{VAR:resp1.data.id}}",
                                "headers", Map.of("Authorization", "Bearer {{VAR:resp1.token}}"))),
                null));
        String script = k6ScriptOf(
                k6Adapter.compile(perfScenarioService.view(projectId, id)));
        // Capture slot: scalar-leaf expansion merged into the per-VU pool.
        assertThat(script).contains("tpScalarLeaves").contains("Object.assign(tpVars,");
        // Consumption slots: url and header placeholders resolve at runtime only
        // when the text carries {{VAR: (static strings stay plain literals).
        assertThat(script).contains("tpVarResolve(\"/orders?id={{VAR:resp1.data.id}}\")");
        assertThat(script).contains("tpVarResolve(\"Bearer {{VAR:resp1.token}}\")");
        // Fail-fast wording mirrors the executor kernel (undefined reference names
        // the variable and points at the previous saveAs; no resolved value leaks).
        assertThat(script).contains("未定义（检查前序 saveAs）");
        // The static login url is not wrapped (resolver only where placeholders exist).
        assertThat(script).contains("http.request(\"POST\", \"/login\"");
        // A capture-only step carries no expect.body rows: no check line at all
        // (an empty check object would be a fake judgement slot).
        assertThat(script).doesNotContain("check(res");
    }

    /**
 * A-6 login strategy (DSL v2 , k6 half): SHARED (one login for the
 * whole run) cannot be compiled by k6 — every VU gets its own module
 * instance with isolated memory, so there is no cross-VU session to share.
 * Silently compiling SHARED as per-VUSER would fake traffic that does not
 * match the declared strategy, so the honest behavior is a compile-time
 * rejection naming the boundary and the engine that supports it.
 */
    @Test
    void sharedLoginStrategyIsRejectedOnK6NamingTheEngineBoundary() {
        long id = perfScenarioService.create(projectId, new PerfScenarioInput(
                "登录策略_shared_k6_" + (System.nanoTime() % 1_000_000L), envId, "CONSTANT_RPS",
                Map.of("targetRps", 5, "durationSeconds", 60), "SHARED", null,
                List.of(Map.of("action", "http.request", "method", "POST", "url", "/login",
                        "auth", true, "saveAs", "resp1")), null));
        assertThatThrownBy(() -> k6Adapter.compile(perfScenarioService.view(projectId, id)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Gatling");
    }

    /**
 * A-6 PER_SESSION_N on k6 (DSL v2 : the authoritative strategy
 * semantics (N virtual users share one login) need a cross-VU session, and
 * k6 VUs are memory-isolated by design — approximating it with a per-VU
 * iteration window would change the login traffic shape and misreport the
 * declared strategy, so it is rejected at compile time naming the engine
 * that supports the strategy (same honest fail-closed posture as the missing
 * k6 binary path). Save-time validation stays engine-agnostic — the scenario
 * profile is valid, only the k6 compile is.
 */
    @Test
    void perSessionNLoginStrategyIsRejectedOnK6NamingTheEngineBoundary() {
        long id = perfScenarioService.create(projectId, new PerfScenarioInput(
                "登录策略_psn_k6_" + (System.nanoTime() % 1_000_000L), envId, "CONSTANT_RPS",
                Map.of("targetRps", 5, "durationSeconds", 60), "PER_SESSION_5", null,
                List.of(Map.of("action", "http.request", "method", "POST", "url", "/login",
                        "auth", true, "saveAs", "resp1")), null));
        assertThatThrownBy(() -> k6Adapter.compile(perfScenarioService.view(projectId, id)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Gatling");
    }

    /**
     * PER_VUSER (the default) keeps the previous minimal shape: no gate helper,
     * no wrapper — auth steps run every iteration (zero regression on golden).
     */
    @Test
    void perVuserLoginStrategyCompilesWithoutAnyGate() throws Exception {
        long id = perfScenarioService.create(projectId, new PerfScenarioInput(
                "登录策略_pv_k6_" + (System.nanoTime() % 1_000_000L), envId, "CONSTANT_RPS",
                Map.of("targetRps", 5, "durationSeconds", 60), "PER_VUSER", null,
                List.of(Map.of("action", "http.request", "method", "POST", "url", "/login",
                        "auth", true)), null));
        String script = k6ScriptOf(
                k6Adapter.compile(perfScenarioService.view(projectId, id)));
        assertThat(script).doesNotContain("tpAuthDue");
    }


    @Test
    void launchWithoutK6BinaryFailsInPlainWords() {
        assertThatThrownBy(() -> k6Adapter.launch("/nonexistent/perf.js",
                Map.of("runId", 1L)))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("k6");
    }


    @Test
    void attributeIsSingleSourceIdenticalToDefaultAdapter() {
        Map<String, Object> run = Map.of("id", 99L,
                "sla", Map.of("p95Ms", 300, "errorRate", 0.02, "minRps", 40));
        List<PerfMetricFrame> good = List.of(
                new PerfMetricFrame(1, 50, 120, 0.001, null, null, null, null, null),
                new PerfMetricFrame(2, 52, 150, 0.002, null, null, null, null, null));
        List<PerfMetricFrame> bad = List.of(
                new PerfMetricFrame(1, 50, 800, 0.05, null, null, null, null, null),
                new PerfMetricFrame(2, 55, 900, 0.06, null, null, null, null, null));
        assertThat(k6Adapter.attribute(run, good)).isEqualTo(gatlingAdapter.attribute(run, good));
        assertThat(k6Adapter.attribute(run, bad)).isEqualTo(gatlingAdapter.attribute(run, bad));
        assertThat(k6Adapter.attribute(run, bad).breachedGates())
                .containsExactlyInAnyOrder("压测响应时间 P95", "压测错误率");
    }
}
