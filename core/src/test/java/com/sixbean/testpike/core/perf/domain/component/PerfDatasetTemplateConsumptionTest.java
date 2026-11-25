package com.sixbean.testpike.core.perf.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.perf.api.dto.PerfScenarioInput;
import com.sixbean.testpike.core.perf.domain.service.PerfDatasetService;
import com.sixbean.testpike.core.perf.domain.service.PerfScenarioService;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * data-pool segment B②a: the engine templates consume the bound CSV dataset.
 * <p>Contract authority = docs/16 row + the recon ruling
 * (docs/task-archives/PROGRESS_card_t375b2_recon_tick1491.md): supply channel =
 * compile-time materialization next to the artifact (zero new dispatch channel);
 * row values ride the single {{VAR:}} pool (no second parser); allocation matrix =
 * k6 carries CYCLE + VUSER_EXCLUSIVE (per-VU segment, honest row-shortage refusal,
 * SEQUENTIAL_EXCLUSIVE rejected naming Gatling), Gatling carries
 * SEQUENTIAL_EXCLUSIVE (queue) + CYCLE (circular) (VUSER_EXCLUSIVE rejected naming
 * k6 — javap-proven the 3.13.5 Java DSL has no threadLocal feeder form).
 * <p>Real dev DB (SpringBootTest, non-mock); fixture posture mirrors
 * K6AdapterTest (nanoTime-uniq project prefix + cascade cleanup).
 */
@SpringBootTest(classes = CoreApplication.class)
@org.springframework.test.context.TestPropertySource(properties = "testpike.scheduling.enabled=false")
class PerfDatasetTemplateConsumptionTest {

    @Autowired
    private K6Adapter k6Adapter;

    @Autowired
    private GatlingAdapter gatlingAdapter;

    @Autowired
    private PerfScenarioService perfScenarioService;

    @Autowired
    private PerfDatasetService perfDatasetService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    /** Compiled artifacts are project-routed object-storage bundles ( segment
 * 2): contract assertions read them through the same storage route the
 * production write used, so a broken route fails here, not silently. */
    @Autowired
    private com.sixbean.testpike.core.executor.domain.component.ArtifactStorage artifactStorage;

    private long projectId;
    private long envId;

    @BeforeEach
    void seedFixtures() {
        SharedDbFixtureSweep.sweepPerfFixtureProject(jdbcTemplate, "PERFDS", "tp_perf_dataset");
        // Single-source seed (R40): RETURNING-anchored ids so a foreign fixture
        // row can never re-parent these inserts on the shared dev database.
        long[] ids = SharedDbFixtureSweep.seedPerfFixtureProject(jdbcTemplate,
                "数据池模板消费项目_", "PERFDS", "性能-数据池模板测", 1,
                "http://perf-target.local/api", 0, null);
        projectId = ids[0];
        envId = ids[1];
    }

    @AfterEach
    void cleanup() {
        SharedDbFixtureSweep.sweepPerfFixtureProject(jdbcTemplate, "PERFDS", "tp_perf_dataset");
    }

    /** Upload one pool; column names double as {{VAR:}} names in step texts. */
    private long seedDataset(String csvBody) {
        return perfDatasetService.upload(projectId,
                "池_" + (System.nanoTime() % 1_000_000_000L), "pool.csv",
                csvBody.getBytes(StandardCharsets.UTF_8));
    }

    /** Saved scenario view bound to the pool with the given allocation strategy. */
    private Map<String, Object> boundView(long datasetId, String allocMode,
                                          Map<String, Integer> config) {
        long id = perfScenarioService.create(projectId, new PerfScenarioInput(
                "数据池场景_" + (System.nanoTime() % 1_000_000_000L), envId, "CONSTANT_RPS",
                config, null, null,
                List.of(Map.<String, Object>of("action", "http.request", "method", "GET",
                        "url", "/orders?u={{VAR:username}}")),
                null, null, datasetId, allocMode));
        return perfScenarioService.view(projectId, id);
    }

    /** Bundle entries of a compile handle via the write route (never a temp path). */
    private Map<String, byte[]> bundleEntries(String handle) throws Exception {
        byte[] bundle = artifactStorage.readBytesQuietly(handle);
        assertThat(bundle).as("bundle readable through the write route: %s", handle)
                .isNotNull();
        return PerfArtifactBundleProbe.entries(bundle);
    }

    /** k6 compile handle = object key of the script(+sidecar) zip; read the .js text. */
    private String k6ScriptOf(String handle) throws Exception {
        return PerfArtifactBundleProbe.textOf(bundleEntries(handle), ".js");
    }

    /** Gatling compile handle = zip of the run directory; the manifest names the
     *  sourceFile entry sitting beside it inside the bundle. */
    private String gatlingSourceOf(String handle) throws Exception {
        var entries = bundleEntries(handle);
        String manifest = PerfArtifactBundleProbe.textOf(entries, "manifest.json");
        String sourceFile = String.valueOf(
                objectMapper.readValue(manifest, Map.class).get("sourceFile"));
        return PerfArtifactBundleProbe.textOf(entries, sourceFile);
    }

    // ---------- k6 side ----------

    /**
     * CYCLE on k6: rows materialize at compile time next to the script, the
     * init-context loads them and every iteration folds one row into the single
     * tpVars pool (the step text keeps resolving {{VAR:username}} through the
     * one resolver — no second substitution point in the script).
     */
    @Test
    void k6CycleBindsRowsIntoSingleVarPool() throws Exception {
        long dsId = seedDataset("username,password\nalice,a1\nbob,b2\n");
        String script = k6ScriptOf(k6Adapter.compile(
                boundView(dsId, "CYCLE", Map.of("targetRps", 5, "durationSeconds", 60))));
        // Row file is opened from the script directory (k6 resolves relative to it).
        assertThat(script).contains("open(\"ds_" + dsId + ".json\")");
        // CYCLE walk: iteration index with a per-VU start offset (no lockstep rows).
        assertThat(script).contains("__ITER");
        assertThat(script).contains("__VU");
        // Row values ride the existing pool before the request legs run.
        assertThat(script).contains("Object.assign(tpVars, tpDatasetRow())");
        assertThat(script).contains("tpVarResolve(\"/orders?u={{VAR:username}}\")");
    }

    /**
 * VUSER_EXCLUSIVE on k6: rows segment per virtual user (stride = preAllocated
 * VUs, known at compile time) and each user cycles only inside its own segment.
 */
    @Test
    void k6VuserExclusiveCompilesPerVuRowSlice() throws Exception {
        long dsId = seedDataset("username,password\na,1\nb,2\nc,3\nd,4\n");
        String script = k6ScriptOf(k6Adapter.compile(
                boundView(dsId, "VUSER_EXCLUSIVE", Map.of("targetRps", 2, "durationSeconds", 60))));
        assertThat(script).contains("tpDatasetRow");
        assertThat(script).contains("__VU - 1");
        assertThat(script).contains("i += 2");
        // Honest runtime refusal for an empty segment (compile guard covers VUs>rows).
        assertThat(script).contains("独占");
    }

    /**
     * VUSER_EXCLUSIVE with fewer rows than virtual users is a compile-time refusal
     * naming both counts — silently sharing rows would fake exclusive semantics.
     */
    @Test
    void k6VuserExclusiveRefusesWhenRowsFewerThanVus() {
        long dsId = seedDataset("username,password\nalice,a1\nbob,b2\n");
        assertThatThrownBy(() -> k6Adapter.compile(
                boundView(dsId, "VUSER_EXCLUSIVE", Map.of("targetRps", 50, "durationSeconds", 60))))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("2")
                .hasMessageContaining("50");
    }

    /**
     * SEQUENTIAL_EXCLUSIVE (one lease per row across users) needs a cross-VU
     * atomic pointer k6 does not offer — compile refuses in plain words and
     * points at the engine that carries it natively (Gatling queue feeder),
     * same fail-closed posture as the SHARED login strategy refusal.
     */
    @Test
    void k6RejectsSequentialExclusiveNamingGatling() {
        long dsId = seedDataset("username,password\nalice,a1\n");
        assertThatThrownBy(() -> k6Adapter.compile(
                boundView(dsId, "SEQUENTIAL_EXCLUSIVE", Map.of("targetRps", 5, "durationSeconds", 60))))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("SEQUENTIAL_EXCLUSIVE")
                .hasMessageContaining("Gatling");
    }

    // ---------- Gatling side ----------

    /**
     * SEQUENTIAL_EXCLUSIVE maps onto the native queue feeder (single copy, in
     * file order, drained = honest session failure) — the manifest's sibling
     * source must compile through the real javac segment (signature proven).
     */
    @Test
    void gatlingSequentialExclusiveCompilesQueueFeeder() throws Exception {
        long dsId = seedDataset("username,password\nalice,a1\nbob,b2\n");
        String source = gatlingSourceOf(gatlingAdapter.compile(
                boundView(dsId, "SEQUENTIAL_EXCLUSIVE", Map.of("targetRps", 5, "durationSeconds", 60))));
        assertThat(source).contains(".feed(csv(").contains(".queue())");
        assertThat(source).contains("import static io.gatling.javaapi.core.CoreDsl.csv;");
    }

    /** CYCLE maps onto the circular feeder (never starves a long run). */
    @Test
    void gatlingCycleCompilesCircularFeeder() throws Exception {
        long dsId = seedDataset("username,password\nalice,a1\nbob,b2\n");
        String source = gatlingSourceOf(gatlingAdapter.compile(
                boundView(dsId, "CYCLE", Map.of("targetRps", 5, "durationSeconds", 60))));
        assertThat(source).contains(".feed(csv(").contains(".circular())");
    }

    /**
     * VUSER_EXCLUSIVE on Gatling: no per-user private-slice feeder exists in the
     * 3.13.5 Java DSL (javap-proven: FeederBuilder$Batchable exposes
     * queue/random/shuffle/circular/shard but no threadLocal), so the honest
     * behavior is a compile-time refusal naming k6 (recon candidate B, candidate
     * A rejected by the same evidence) — never a silent circular downgrade.
     */
    @Test
    void gatlingRejectsVuserExclusiveNamingK6() {
        long dsId = seedDataset("username,password\nalice,a1\nbob,b2\n");
        assertThatThrownBy(() -> gatlingAdapter.compile(
                boundView(dsId, "VUSER_EXCLUSIVE", Map.of("targetRps", 5, "durationSeconds", 60))))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("VUSER_EXCLUSIVE")
                .hasMessageContaining("k6");
    }
}
