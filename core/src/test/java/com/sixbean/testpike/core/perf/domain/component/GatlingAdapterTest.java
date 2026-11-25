package com.sixbean.testpike.core.perf.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.report.CaliberDefinition;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.perf.api.dto.PerfScenarioInput;
import com.sixbean.testpike.core.perf.domain.service.PerfScenarioService;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


@SpringBootTest(classes = CoreApplication.class)
@org.springframework.test.context.TestPropertySource(properties = "testpike.scheduling.enabled=false")
class GatlingAdapterTest {

    @Autowired
    private GatlingAdapter gatlingAdapter;

    @Autowired
    private PerfScenarioService perfScenarioService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private com.sixbean.testpike.core.executor.domain.component.ArtifactStorage artifactStorage;

    private long projectId;
    private long envId;

    @BeforeEach
    void seedFixtures() {
        SharedDbFixtureSweep.sweepPerfFixtureProject(jdbcTemplate, "PERFADP");
        // Single-source seed (R40): RETURNING-anchored ids so a foreign fixture
        // row can never re-parent these inserts on the shared dev database.
        long[] ids = SharedDbFixtureSweep.seedPerfFixtureProject(jdbcTemplate,
                "Gatling适配测试项目_", "PERFADP", "性能-适配测", 1,
                "http://perf-target.local/api", 0, null);
        projectId = ids[0];
        envId = ids[1];
    }

    @AfterEach
    void cleanup() {
        SharedDbFixtureSweep.sweepPerfFixtureProject(jdbcTemplate, "PERFADP");
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
                "适配_" + model + "_" + (System.nanoTime() % 1_000_000L), envId, model, config,
                null, null, steps, null));
        return perfScenarioService.view(projectId, id);
    }

    // ---------- B-3 : Gatling weighted multi-link mix mapping ----------

    /**
     * A fully declared 80/20 mix compiles into ONE population per link: the
     * scenario total rate is split by weight (ceil keeps every leg positive),
     * each population rides its own scenario (PerfMixGroups.scenarioKey) with
     * only its member steps, so the engine reports the two links as separate
     * request series. This is the capability the compile gate answers with
     * mixSupported=true (mirrors the k6 c-2β weighted executor blocks).
     */
    @Test
    void weightedMixCompilesOnePopulationPerLink() throws Exception {
        Map<String, Object> view = savedView("CONSTANT_RPS",
                Map.of("targetRps", 10, "durationSeconds", 60), List.of(
                        Map.of("action", "http.request", "method", "POST",
                                "url", "{{ENV:BASE}}/orders", "transaction", "下单链路", "weight", 80),
                        Map.of("action", "http.request", "method", "GET",
                                "url", "{{ENV:BASE}}/search", "transaction", "查询链路", "weight", 20)));
        String source = gatlingSource(gatlingAdapter.compile(view));
        // 80/20 of 10 RPS: the two legs press 8 and 2 — never a uniform 10/10.
        assertThat(source).contains("constantUsersPerSec(8)").contains("constantUsersPerSec(2)");
        assertThat(source).contains("scenario(\"link1_").contains("scenario(\"link2_");
        assertThat(source).contains("/orders").contains("/search");
        // Two populations inside the one setUp(...) call = concurrent injection.
        assertThat(source.indexOf("injectOpen")).isLessThan(source.lastIndexOf("injectOpen"));
    }

    /**
     * Grouped links without weights split the declared pressure evenly
     * (ceil(5/2)=3 per leg) — same PerfMixGroups single source as the k6 side.
     */
    @Test
    void unweightedMixSplitsPressureEvenlyAcrossLinks() throws Exception {
        Map<String, Object> view = savedView("CONSTANT_RPS",
                Map.of("targetRps", 5, "durationSeconds", 60), List.of(
                        Map.of("action", "http.request", "method", "GET",
                                "url", "{{ENV:BASE}}/a", "transaction", "链路A"),
                        Map.of("action", "http.request", "method", "GET",
                                "url", "{{ENV:BASE}}/b", "transaction", "链路B")));
        String source = gatlingSource(gatlingAdapter.compile(view));
        int legs = source.split("constantUsersPerSec\\(3\\)", -1).length - 1;
        assertThat(legs).as("expected two 3-RPS legs, source was:%n%s", source)
                .isEqualTo(2);
    }

    /**
     * Steps sharing one transaction ride the SAME scenario in declared chain
     * order (one user = that link's whole journey); request names keep the
     * GLOBAL step number so two links never collapse into one report series.
     * The compile call itself is the live javac proof (a wrong DSL form throws
     * there); the manifest class file must exist.
     */
    @Test
    void multiStepLinkCompilesOneScenarioInDeclaredOrder() throws Exception {
        String manifest = gatlingAdapter.compile(savedView("CONSTANT_RPS",
                Map.of("targetRps", 6, "durationSeconds", 60), List.of(
                        Map.of("action", "http.request", "method", "POST", "url", "{{ENV:BASE}}/o1",
                                "transaction", "下单", "weight", 50),
                        Map.of("action", "http.request", "method", "GET", "url", "{{ENV:BASE}}/o2",
                                "transaction", "下单", "weight", 50),
                        Map.of("action", "http.request", "method", "GET", "url", "{{ENV:BASE}}/s",
                                "transaction", "查询", "weight", 50))));
        String source = gatlingSource(manifest);
        int order = source.indexOf("/o1");
        int second = source.indexOf("/o2");
        int other = source.indexOf("/s\"");
        assertThat(order).as("link steps keep chain order").isLessThan(second);
        assertThat(second).isLessThan(other);
        assertThat(source).contains("step1-POST").contains("step2-GET").contains("step3-GET");
        JsonNode node = manifestNode(manifest);
        assertThat(bundleEntries(manifest))
                .as("javac output .class landed inside the bundle")
                .containsKey(node.get("classesDir").asText() + "/"
                        + node.get("fqcn").asText().replace('.', '/') + ".class");
    }

    /**
     * FIXED_VU (closed pool) mix: the VU count itself splits by weight —
     * 40 users at 80/20 = constantConcurrentUsers(32)+(8), still injectClosed.
     * A uniform 40/40 would fake double the declared pool.
     */
    @Test
    void fixedVuMixSplitsPoolSizeByWeight() throws Exception {
        String source = gatlingSource(gatlingAdapter.compile(savedView("FIXED_VU",
                Map.of("vu", 40, "durationSeconds", 120), List.of(
                        Map.of("action", "http.request", "method", "GET",
                                "url", "{{ENV:BASE}}/a", "transaction", "链路A", "weight", 80),
                        Map.of("action", "http.request", "method", "GET",
                                "url", "{{ENV:BASE}}/b", "transaction", "链路B", "weight", 20)))));
        assertThat(source).contains("constantConcurrentUsers(32)")
                .contains("constantConcurrentUsers(8)")
                .contains("injectClosed");
    }

    /**
     * Mix + dataset pool = honest compile refusal: under several populations
     * each Gatling scenario holds its OWN feeder, so the queue/circular row
     * lease would restart per link instead of across the run = a fake row
     * allocation (fail-closed family, same as the VUSER_EXCLUSIVE reject).
     */
    @Test
    void mixWithDatasetRefusesInPlainWords() {
        Map<String, Object> view = new java.util.HashMap<>(savedView("CONSTANT_RPS",
                Map.of("targetRps", 10, "durationSeconds", 60), List.of(
                        Map.of("action", "http.request", "method", "GET",
                                "url", "{{ENV:BASE}}/a", "transaction", "链路A", "weight", 80),
                        Map.of("action", "http.request", "method", "GET",
                                "url", "{{ENV:BASE}}/b", "transaction", "链路B", "weight", 20))));
        view.put("dataset_id", "7");
        assertThatThrownBy(() -> gatlingAdapter.compile(view))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("数据池")
                .hasMessageContaining("k6");
    }

    /**
 * Worst-scale chain : a scenario at the perf.scenario.max-steps
 * ceiling (500 steps) renders one nested .exec(...) expression 500 links deep.
 * javac's parser recurses over that expression. Live-proven 2026-10-24: the
 * compile running on the caller's stack dies with a StackOverflowError inside a
 * web request thread (Tomcat honors -Xss=1MB) while the identical source
 * compiles on a fresh JVM main thread (OS default 8MB) — the error is swallowed
 * by the compiler task and surfaces as a fake "compile failed, no diagnostics"
 * refusal. Fix contract: compile must not depend on the caller's remaining
 * stack — it runs javac on a dedicated generous-stack thread. This test invokes
 * compile from a deliberately thin-stack thread (256KB, reproducing the failure
 * while 512KB stayed green on a bare caller — the request-thread frame budget
 * pushes the real-world boundary higher) and requires the .class to land on disk.
 */
    @Test
    void fiveHundredStepChainCompilesThroughDedicatedStack() throws Exception {
        List<Map<String, Object>> steps = new java.util.ArrayList<>();
        for (int i = 1; i <= 500; i++) {
            steps.add(Map.of("action", "http.request", "method", "GET",
                    "url", "{{ENV:BASE}}/cfirst/" + i));
        }
        Map<String, Object> view = savedView("CONSTANT_RPS",
                Map.of("targetRps", 1, "durationSeconds", 1), steps);
        // DB seeding above ran on the wide JUnit thread; only the compile call is
        // dispatched into the small-stack caller, mirroring the request-thread shape.
        final String[] manifest = new String[1];
        final Throwable[] failure = new Throwable[1];
        Thread smallStackCaller = new Thread(null, () -> {
            try {
                manifest[0] = gatlingAdapter.compile(view);
            } catch (Throwable t) {
                failure[0] = t;
            }
        }, "t443-small-stack-caller", 256_000L);
        smallStackCaller.start();
        smallStackCaller.join(120_000);
        assertThat(failure[0]).as("compile must survive a thin-caller stack: "
                + (failure[0] == null ? "" : failure[0].toString())).isNull();
        JsonNode node = manifestNode(manifest[0]);
        assertThat(bundleEntries(manifest[0]))
                .as("500-step simulation .class compiled into the bundle")
                .containsKey(node.get("classesDir").asText() + "/"
                        + node.get("fqcn").asText().replace('.', '/') + ".class");
    }

    // ---------- Golden samples for compile stage (Java DSL route, signatures javap-proven) ----------

    /**
     * The compile handle is a project-routed object key of the run-directory zip
     * (manifest fqcn/classesDir/sourceFile + javac output + source): the launcher
     * side (perf_worker) materializes the bundle and runs
     * `java -cp classes:lib/* io.gatling.app.Gatling` — the 3.x CLI has no
     * source-folder option, proven live (tick 1480 probe). The source inside is
     * Java DSL (io.gatling.javaapi), and javac must have produced the .class.
     */
    @Test
    void constantRpsCompilesGoldenSimulationArtifact() throws Exception {
        Map<String, Object> view = savedView("CONSTANT_RPS",
                Map.of("targetRps", 50, "durationSeconds", 600));
        String handle = gatlingAdapter.compile(view);
        assertThat(handle).endsWith(".zip");
        assertThat(handle).contains("/" + projectId + "/perf-artifact/");
        String manifest = PerfArtifactBundleProbe.textOf(bundleEntries(handle), "manifest.json");
        assertThat(manifest).contains("\"fqcn\"").contains("com.sixbean.testpike.perf.gen.PerfGen");
        JsonNode node = new ObjectMapper().readTree(manifest);
        String source = PerfArtifactBundleProbe.textOf(bundleEntries(handle),
                node.get("sourceFile").asText());
        // Injection block = template artifact: injectOpen with the Java DSL rate signature
        assertThat(source).contains("injectOpen");
        assertThat(source).contains("constantUsersPerSec(50");
        assertThat(source).contains("600");
        // Step request path flows into the simulation (template slot fill, not runtime concatenation)
        assertThat(source).contains("orders");
        // Compile stage = javac output .class really landed inside the bundle
        String fqcn = node.get("fqcn").asText();
        assertThat(bundleEntries(handle))
                .containsKey(node.get("classesDir").asText() + "/"
                        + fqcn.replace('.', '/') + ".class");
    }

    /**
 * B-1 (Gatling half): saveAs now compiles into a session-pool capture —
 * {@code bodyString.saveAs("tp_resp_<name>")} inside the leg, then an
 * {@code exec(s -> ...)} line expanding scalar JSON leaves under the saveAs
 * name (key shape resp.field / resp[0], mirroring the k6 side and the executor
 * kernel). The javac stage in compile is the real proof the emitted shapes
 * exist in gatling-core-java 3.13.5 (live probe, not guesswork).
 */
    @Test
    void compileConsumesSaveAsIntoSessionVarPool() throws Exception {
        Map<String, Object> view = new HashMap<>(savedView("CONSTANT_RPS",
                Map.of("targetRps", 5, "durationSeconds", 60)));
        view.put("steps_json", "[{\"action\":\"http.request\",\"method\":\"GET\","
                + "\"url\":\"/orders\",\"saveAs\":\"resp1\"}]");
        String source = gatlingSource(gatlingAdapter.compile(view));
        assertThat(source).contains(".check(bodyString().saveAs(\"tp_resp_resp1\"))");
        assertThat(source).contains("tpExpandJsonLeaves(s, \"resp1\", s.getString(\"tp_resp_resp1\"))");
        // The non-JSON response must fail the iteration honestly (mirror of the
        // k6 JSON.parse throw), never fake a green capture.
        assertThat(source).contains("不是合法 JSON");
    }

    /**
 * B-1 : {{VAR:}} references in url/header/body slots resolve through
 * the single in-session resolver (undefined reference fails fast naming the
 * variable, wording mirrors the k6 side); static strings stay plain literals
 * with no wrapper (zero regression for capture-free scenarios).
 */
    @Test
    void compileResolvesVarReferencesAndKeepsStaticSlotsPlain() throws Exception {
        Map<String, Object> view = new HashMap<>(savedView("CONSTANT_RPS",
                Map.of("targetRps", 5, "durationSeconds", 60)));
        view.put("steps_json", "[{\"action\":\"http.request\",\"method\":\"POST\","
                + "\"url\":\"/orders\",\"headers\":{\"X-Order\":\"{{VAR:resp1.id}}\"},"
                + "\"body\":{\"id\":\"{{VAR:resp1.id}}\"}},{\"action\":\"http.request\","
                + "\"method\":\"GET\",\"url\":\"/plain\"}]");
        String source = gatlingSource(gatlingAdapter.compile(view));
        // Dynamic slots ride the resolver; the lambda form is javap-proven for
        // get/header and StringBody in 3.13.5 (live compile probe).
        assertThat(source).contains(".header(\"X-Order\", s -> tpVar(s, \"{{VAR:resp1.id}}\"))");
        assertThat(source).contains("StringBody(s -> tpVar(s, ");
        // Static url keeps the plain-literal overload (no wrapper = zero regression).
        assertThat(source).contains(".get(\"/plain\")");
        assertThat(source).doesNotContain("tpVar(s, \"/plain\")");
        // Resolver wording is the single corpus shared with the k6 side.
        assertThat(source).contains("未定义（检查前序 saveAs）");
    }

    /**
 * A-6 login-strategy compilation lands in the next segment (setup-section vs
 * in-iteration shapes across both engines) — registered, not faked here.
 */

    @Test
    void allThreePressureFormsCompileDistinctInjection() throws Exception {
        String constant = gatlingSource(gatlingAdapter.compile(savedView("CONSTANT_RPS",
                Map.of("targetRps", 20, "durationSeconds", 120))));
        String ramp = gatlingSource(gatlingAdapter.compile(savedView("RAMP",
                Map.of("startRps", 10, "peakRps", 300,
                        "rampSeconds", 120, "holdSeconds", 60))));
        String spike = gatlingSource(gatlingAdapter.compile(savedView("SPIKE",
                Map.of("baseRps", 20, "peakRps", 500, "durationSeconds", 300))));
        assertThat(constant).contains("constantUsersPerSec").contains("io.gatling.javaapi.core");
        assertThat(ramp).contains("rampUsersPerSec(10).to(300).during(120)").contains("300");
        // A-12 fix : SPIKE = sequential rise/hold/fall/tail legs inside the
        // declared duration. The old shape (base constant + stressPeakUsers, both
        // full-length) stacked two injections of durationSeconds each — a staircase
        // twice as long as requested, not a pulse.
        assertThat(spike)
                .contains("rampUsersPerSec(20).to(500).during(60)")
                .contains("constantUsersPerSec(500).during(60)")
                .contains("rampUsersPerSec(500).to(20).during(60)")
                .contains("constantUsersPerSec(20).during(120)")
                .doesNotContain("stressPeakUsers");
    }

    /**
     * A-6 login-strategy compilation (Gatling half): the strategy compiles into
     * a doIf gate over user buckets (TP_SHARE_N divisor: MAX_VALUE = SHARED =
     * one bucket for the whole run, only the bucket head logs in; N =
     * PER_SESSION_N). Gatling-core 3.13.5 has no setIfUnset (javap-proven), so
     * bucket membership rides Session.userId() — 1-based, (userId-1)/N. Captured
     * leaves publish to the run-wide share bus (TP_SHARED_VARS) so bucket
     * members that skipped the login resolve {{VAR:}} through the bus fallback
     * in tpVar (undefined still fails fast — same corpus).
     */
    @Test
    void sharedLoginStrategyCompilesBucketGate() throws Exception {
        Map<String, Object> view = new HashMap<>(savedView("CONSTANT_RPS",
                Map.of("targetRps", 5, "durationSeconds", 60)));
        view.put("login_strategy", "SHARED");
        view.put("steps_json", "[{\"action\":\"http.request\",\"method\":\"POST\","
                + "\"url\":\"/login\",\"auth\":true,\"saveAs\":\"resp1\"},"
                + "{\"action\":\"http.request\",\"method\":\"GET\",\"url\":\"/orders\"}]");
        String source = gatlingSource(gatlingAdapter.compile(view));
        // SHARED = one bucket for the whole run (divisor MAX_VALUE → every user
        // is slot b0) and each auth step is claimed exactly once per bucket
        // (putIfAbsent winner logs in, the rest skip — a bare modulo predicate
        // would re-login every iteration = fake traffic shape).
        assertThat(source).contains("TP_SHARE_N = Integer.MAX_VALUE");
        assertThat(source).contains(".doIf(s -> TP_BUCKET_CLAIM.putIfAbsent(tpBucket(s)"
                + " + \"step1\", Boolean.TRUE) == null)");
        assertThat(source).contains(".then(");
        // The gated capture publishes leaves to the share bus.
        assertThat(source).contains("tpShareJsonLeaves(s, \"resp1\"");
        // Bucket members read the bus through the resolver fallback.
        assertThat(source).contains("TP_SHARED_VARS.get(tpBucket(s) + name)");
        // Business leg keeps its unwrapped shape.
        assertThat(source).contains(".exec(http(\"step2-GET\")");
    }

    /**
     * A-6 PER_SESSION_N (Gatling half): users group into blocks of N, the block
     * head (userId-1 % N == 0) logs in and publishes; the rest consume via bus.
     */
    @Test
    void perSessionNLoginStrategyCompilesBucketGate() throws Exception {
        Map<String, Object> view = new HashMap<>(savedView("CONSTANT_RPS",
                Map.of("targetRps", 5, "durationSeconds", 60)));
        view.put("login_strategy", "PER_SESSION_5");
        view.put("steps_json", "[{\"action\":\"http.request\",\"method\":\"POST\","
                + "\"url\":\"/login\",\"auth\":true,\"saveAs\":\"resp1\"}]");
        String source = gatlingSource(gatlingAdapter.compile(view));
        assertThat(source).contains("TP_SHARE_N = 5");
        // Per-bucket + once-per-step claim (the bucket head at first arrival
        // logs in; repeating every iteration would fake the traffic shape,
        // same discipline as SHARED).
        assertThat(source).contains(".doIf(s -> TP_BUCKET_CLAIM.putIfAbsent(tpBucket(s)"
                + " + \"step1\", Boolean.TRUE) == null)");
        assertThat(source).contains("(s.userId() - 1) / TP_SHARE_N");
    }

    /**
     * A-6 PER_VUSER (default) keeps the previous minimal shape: no gate at all,
     * divisor 1 so every user owns its bucket (bus never read — session always
     * hits first), auth steps run every iteration (zero regression on golden).
     */
    @Test
    void perVuserLoginStrategyCompilesWithoutGateHelpers() throws Exception {
        Map<String, Object> view = new HashMap<>(savedView("CONSTANT_RPS",
                Map.of("targetRps", 5, "durationSeconds", 60)));
        view.put("login_strategy", "PER_VUSER");
        view.put("steps_json", "[{\"action\":\"http.request\",\"method\":\"POST\","
                + "\"url\":\"/login\",\"auth\":true}]");
        String source = gatlingSource(gatlingAdapter.compile(view));
        assertThat(source).doesNotContain("tpLoginDue");
        assertThat(source).doesNotContain(".doIf(");
    }


    @Test
    void compileRejectsUiActionStepNamingTheAction() {
        Map<String, Object> dirty = savedView("CONSTANT_RPS",
                Map.of("targetRps", 5, "durationSeconds", 60));
        Map<String, Object> tampered = new java.util.HashMap<>(dirty);
        tampered.put("steps_json", dirty.get("steps_json").toString()
                .replace("http.request", "click"));
        assertThatThrownBy(() -> gatlingAdapter.compile(tampered))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("click");
    }

    /**
     * A-11 compile-stage depth: sql.query passed the old six-action whitelist but
     * the template only fills action/method/url slots, so it would compile into an
     * empty-URL exec (fake traffic). The narrowed gate rejects it naming the
     * offender and the only allowed action.
     */
    @Test
    void compileRejectsNonHttpActionStepNamingActionAndAllowedSet() {
        Map<String, Object> dirty = savedView("CONSTANT_RPS",
                Map.of("targetRps", 50, "durationSeconds", 600));
        Map<String, Object> tampered = new java.util.HashMap<>(dirty);
        tampered.put("steps_json", dirty.get("steps_json").toString()
                .replace("http.request", "sql.exec"));
        assertThatThrownBy(() -> gatlingAdapter.compile(tampered))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("sql.exec")
                .hasMessageContaining("http.request");
    }

    // ---------- B-2 : the four new pressure models map to Gatling injections ----------

    /**
     * FIXED_VU closed pool: a wall-clock window maps to injectClosed +
     * constantConcurrentUsers(vu).during(seconds) (the closed-injection family the
     * javap probe proved), loops-only maps to atOnceUsers + repeat(loops).on(legs)
     * (the loop combinator returns the scenario builder, ChainBuilder implements
     * Executable). thinkTimeMs rides a pause between legs — seconds when the value
     * is a whole-second multiple, an explicit Duration otherwise (never dropped:
     * a closed pool without think time presses harder than declared).
     */
    @Test
    void fixedVuClosedWindowAndLoopBoundCompile() throws Exception {
        Map<String, Object> view = savedView("FIXED_VU",
                Map.of("vu", 40, "durationSeconds", 180, "thinkTimeMs", 3000));
        String source = gatlingSource(gatlingAdapter.compile(view));
        assertThat(source).contains("injectClosed")
                .contains("constantConcurrentUsers(40)")
                .contains("during(180)")
                .contains("pause(3000)");
        Map<String, Object> looped = savedView("FIXED_VU", Map.of("vu", 40, "loops", 50));
        String loopSource = gatlingSource(gatlingAdapter.compile(looped));
        assertThat(loopSource).contains("atOnceUsers(40)")
                .contains(".repeat(50).on(");
    }

    /**
     * LADDER search → the staircase open-injection: incrementUsersPerSec(stepRps)
     * .times(steps).eachLevelLasting(stepSeconds).startingFrom(startRps) (all four
     * forms javap-proven on gatling-core-java 3.13.5).
     */
    @Test
    void ladderCompilesStaircaseSearch() throws Exception {
        String source = gatlingSource(gatlingAdapter.compile(savedView("LADDER",
                Map.of("startRps", 20, "stepRps", 10, "stepSeconds", 30, "steps", 4))));
        assertThat(source).contains("incrementUsersPerSec(10)")
                .contains("times(4)")
                .contains("eachLevelLasting(30)")
                .contains("startingFrom(20)");
    }

    /**
     * BUSINESS_CURVE → sequential constantUsersPerSec legs (one per active hour,
     * rate = peakRps x weight/100, during the hour span); idle hours are skipped,
     * never emitted as a 0-rate leg.
     */
    @Test
    void businessCurveCompilesHourLegsAndSkipsIdleHours() throws Exception {
        Map<String, Integer> config = new java.util.HashMap<>();
        config.put("peakRps", 100);
        config.put("durationSeconds", 86400);
        config.put("curve8", 60);
        config.put("curve12", 100);
        String source = gatlingSource(gatlingAdapter.compile(savedView("BUSINESS_CURVE", config)));
        assertThat(source).contains("constantUsersPerSec(60)")
                .contains("constantUsersPerSec(100)");
    }

    /** SOAK = one long constant-rate leg spelled in hours (durationHours x 3600). */
    @Test
    void soakCompilesLongConstantLeg() throws Exception {
        String source = gatlingSource(gatlingAdapter.compile(savedView("SOAK",
                Map.of("targetRps", 25, "durationHours", 4))));
        assertThat(source).contains("constantUsersPerSec(25)")
                .contains("during(14400)");
    }

    // ---------- CF-9 load-test marker propagation (Gatling half, mirrors the k6 pair) ----------

    /**
     * A scenario declaring HEADER_MARK isolation compiles a simulation whose
     * outbound requests carry the shared X-Load-Test marker header, so the
     * system under pressure can filter shadow traffic on the same contract the
     * k6 side already honours (single marker vocabulary across engines).
     */
    @Test
    void headerMarkIsolationPropagatesLoadTestHeaderIntoSimulation() throws Exception {
        Map<String, Object> view = new java.util.HashMap<>(savedView("CONSTANT_RPS",
                Map.of("targetRps", 5, "durationSeconds", 60)));
        view.put("isolation_mode", "HEADER_MARK");
        String source = gatlingSource(gatlingAdapter.compile(view));
        assertThat(source).contains("X-Load-Test");
    }

    /** Without an isolation declaration the generated simulation stays marker-free. */
    @Test
    void scenarioWithoutIsolationDeclarationEmitsNoLoadTestHeader() throws Exception {
        String source = gatlingSource(gatlingAdapter.compile(savedView("CONSTANT_RPS",
                Map.of("targetRps", 5, "durationSeconds", 60))));
        assertThat(source).doesNotContain("X-Load-Test");
    }

    /** Dirty spellings still hit the default branch with a named plain-words reject. */
    @Test
    void injectionBlockRejectsUnknownModelNamingIt() {
        assertThatThrownBy(() -> GatlingSimulationTemplate.injectionBlock("NO_SUCH_MODEL",
                new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NO_SUCH_MODEL");
    }

    /**
 * read path: the compile handle is a project-routed object key
 * of the run-directory zip bundle — pull bytes through the SAME
 * ArtifactStorage route production wrote with (read/write same route) and
 * unzip into zip-relative entries.
 */
    private Map<String, byte[]> bundleEntries(String handle) throws Exception {
        byte[] bundle = artifactStorage.readBytesQuietly(handle);
        assertThat(bundle).as("bundle readable through the write route: %s", handle)
                .isNotNull();
        return PerfArtifactBundleProbe.entries(bundle);
    }

    /** Manifest JSON of a bundle handle (fqcn/classesDir/sourceFile, zip-relative pointers). */
    private JsonNode manifestNode(String handle) throws Exception {
        return new ObjectMapper().readTree(
                PerfArtifactBundleProbe.textOf(bundleEntries(handle), "manifest.json"));
    }

    /** Read the generated simulation source out of the compile bundle. */
    private String gatlingSource(String handle) throws Exception {
        JsonNode node = manifestNode(handle);
        return PerfArtifactBundleProbe.textOf(bundleEntries(handle),
                node.get("sourceFile").asText());
    }


    @Test
    void launchWithoutEngineRuntimeFailsInPlainWords() {
        assertThatThrownBy(() -> gatlingAdapter.launch("/nonexistent/gatling-sim.scala",
                Map.of("runId", 1L, "baseUrl", "http://perf-target.local/api")))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("Gatling");
    }


    @Test
    void perfCalibersRegisteredInSingleSourceRegistry() {
        List<String> codes = Arrays.stream(CaliberDefinition.values())
                .map(CaliberDefinition::code).toList();
        assertThat(codes).contains("perf-p95", "perf-error-rate", "perf-throughput");
    }

    @Test
    void attributeJudgesThreeGatesViaRegistryCalibers() {
        Map<String, Object> run = Map.of("id", 99L,
                "sla", Map.of("p95Ms", 300, "errorRate", 0.02, "minRps", 40));
        List<PerfMetricFrame> good = List.of(
                new PerfMetricFrame(1, 50, 120, 0.001, null, null, null, null, null),
                new PerfMetricFrame(2, 52, 150, 0.002, null, null, null, null, null));
        PerfAttribution pass = gatlingAdapter.attribute(run, good);
        assertThat(pass.passed()).isTrue();
        assertThat(pass.breachedGates()).isEmpty();

        List<PerfMetricFrame> bad = List.of(
                new PerfMetricFrame(1, 50, 800, 0.05, null, null, null, null, null),
                new PerfMetricFrame(2, 55, 900, 0.06, null, null, null, null, null));
        PerfAttribution fail = gatlingAdapter.attribute(run, bad);
        assertThat(fail.passed()).isFalse();
        // Breach names come from the registry display name (single source; screen text
        // must never leak raw caliber codes — the report renders the same strings).
        assertThat(fail.breachedGates()).containsExactlyInAnyOrder("压测响应时间 P95", "压测错误率");
        assertThat(fail.conclusion()).contains("压测响应时间 P95");
    }
}
