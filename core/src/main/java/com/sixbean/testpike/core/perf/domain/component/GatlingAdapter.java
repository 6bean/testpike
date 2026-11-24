package com.sixbean.testpike.core.perf.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Gatling default engine adapter (D1 ruling: Gatling default engine, caliber b
 * "external open source must pass the adapter layer" named component).
 * <p>Four-stage contract (frozen EngineAdapter surface):
 * ①compile=saved scenario view → {@link GatlingSimulationTemplate} generated
 * Java DSL source → javac in-process compile (javax.tools, shipped with the JDK;
 * gatling-core-java/http-java already on the classpath via gatling-app = zero new
 * dependencies) → manifest.json handle {fqcn, classesDir, sourceFile} under a
 * per-run directory (route re-settled by live probes: no Scala compiler is
 * distributed and the 3.x CLI has no source-folder option, so the launcher runs
 * the compiled classes through `java -cp classes:lib/* io.gatling.app.Gatling`).
 * Second D2 gate at compile stage: a view built from dirty data still gets
 * rejected with human-worded naming through NonUiActionContract single source.
 * ②launch=manifest presence + engine runtime self-check, missing = human-worded
 * ServiceException naming "Gatling", never a silent engine substitution (same
 * law as the missing-browser-kernel precedent) — the real press runs on the
 * executor side (perf_worker subprocess), this stage freezes the handle contract;
 * ③collect=normalized metric frames (perf_worker return shape);
 * ④attribute=three gates by O10 registry caliber (D6 single source: perf-p95 /
 * perf-error-rate / perf-throughput come from CaliberDefinition, no second head).
 * <p>Dependency note: gatling-core/http/app 3.13.5 written directly (no
 * gatling-bom 3.13.5 exists); coexistence with Boot 3.5.3 arbitrated via
 * dependency:tree inspection.
 */
@Component
public class GatlingAdapter implements EngineAdapter {

    /** Engine runtime self-check anchor (missing on classpath = dependency not settled/packaging gap, named at startup stage). */
    private static final String ENGINE_PROBE_CLASS = "io.gatling.core.Predef$";

    /** Java package the generated simulations live in (manifest fqcn prefix, launcher side same word). */
    static final String GENERATED_PACKAGE = "com.sixbean.testpike.perf.gen";

    /**
     * javac worker stack (16MB): sized for the 500-deep .exec recursion at the
     * step-count ceiling with margin; a fresh thread gets this regardless of the
     * caller's remaining stack (compileWithJavac javadoc = live-proven rationale).
     */
    private static final long JAVAC_STACK_BYTES = 16L * 1024 * 1024;

    /** Upper bound on one compile wait: a hung javac must fail loudly, never block the caller forever. */
    private static final long JAVAC_JOIN_TIMEOUT_MILLIS = 120_000L;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final PerfStepActionGate stepActionGate;
    private final PerfSlaJudge slaJudge;
    private final PerfDatasetRows datasetRows;
    private final PerfConfigReader perfConfigReader;
    private final PerfArtifactBundler artifactBundler;

    public GatlingAdapter(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper,
                          PerfStepActionGate stepActionGate, PerfSlaJudge slaJudge,
                          PerfDatasetRows datasetRows, PerfConfigReader perfConfigReader,
                          PerfArtifactBundler artifactBundler) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.stepActionGate = stepActionGate;
        this.slaJudge = slaJudge;
        this.datasetRows = datasetRows;
        this.perfConfigReader = perfConfigReader;
        this.artifactBundler = artifactBundler;
    }

    @Override
    public String compile(Map<String, Object> scenario) {
        String model = PerfScenarioReadSupport.text(scenario, "pressure_model");
        JsonNode config = readJson(PerfScenarioReadSupport.text(scenario, "pressure_config"), "压力参数");
        JsonNode steps = readJson(PerfScenarioReadSupport.text(scenario, "steps_json"), "请求步骤");
        // B-1 landed: the Gatling template now captures saveAs into the
        // session pool and resolves {{VAR:}} slots, so the compile gate consumes
        // the chain instead of rejecting it (mirrors the k6 side).
        // B-3 mix : the template now renders one
        // population per transaction link with weight-scaled injection
        // (PerfMixGroups single source shared with the k6 side), so this
        // engine answers mixSupported=true. The dataset pool is the remaining
        // gap — see the rejection below (multi-population feeder lease semantics
        // unproven; silently dropping or splitting the pool would fake the row
        // allocation contract, same fail-closed family as VUSER_EXCLUSIVE).
        stepActionGate.rejectUiActions(steps, true, true);

        long envId = ((Number) scenario.get("env_id")).longValue();
        String baseUrl = jdbc.queryForObject("SELECT base_url FROM tp_project_env WHERE id = :e",
                new MapSqlParameterSource("e", envId), String.class);

        String simulationName = "PerfGen" + scenario.get("id") + "T"
                + (System.nanoTime() % 1_000_000L);
        // A-6 (DSL v2 : the declared login strategy decides the gate
        // shape (PER_VUSER = unwrapped per-user pool; SHARED/PER_SESSION_N =
        // once-per-bucket doIf claim + share bus).
        // data-pool segment B②a: bound dataset → csv feeder sidecar placed in
        // the compiled classes directory (the engine classpath Gatling launches
        // with — its file feeder resolves classpath resources first, so cwd
        // independence is structural, unlike the k6 script-directory contract).
        // Allocation matrix: Gatling natively carries SEQUENTIAL_EXCLUSIVE
        // (queue) + CYCLE (circular); VUSER_EXCLUSIVE has no per-user private
        // segment feeder in the 3.13.5 Java DSL (javap-proven: FeederBuilder
        // exposes queue/random/shuffle/circular/shard, no threadLocal form) —
        // a silent circular downgrade would fake exclusive semantics, so it
        // fails closed naming k6 (recon candidate B; candidate A rejected by
        // the same evidence).
        TemplateDataset dsArg = null;
        PerfDatasetRows.Dataset dataset = null;
        String datasetIdRaw = PerfScenarioReadSupport.text(scenario, "dataset_id");
        if (datasetIdRaw != null && !datasetIdRaw.isBlank()) {
            // B-3 (c-2γ) capability gap, fail-closed: a mix presses through
            // several populations and the 3.13.5 Java DSL gives each scenario
            // its OWN feeder instance — the queue/circular rows would restart
            // per link instead of leasing across the whole run, faking the
            // declared row-allocation contract (same discipline as the
            // VUSER_EXCLUSIVE reject below; k6 covers the combination via its
            // global __VU index, so the honest advice names the alternative).
            if (!PerfMixGroups.derive(steps).isFlat()) {
                throw new ServiceException("Gatling 引擎暂不支持「多链路配比 + 数据池」组合："
                        + "多总体下每条链路各自持有一份馈器，行租约会按链路重新起算="
                        + "伪造申报的行分配口径（k6 引擎支持该组合，或把混合链路拆成"
                        + "各自绑定数据池的独立场景）", BizErrorCode.VALIDATION_ERROR);
            }
            long datasetId = Long.parseLong(datasetIdRaw);
            String allocMode = PerfRowAllocModes.firstNonBlank(
                    PerfScenarioReadSupport.text(scenario, "row_alloc_mode_resolved"), PerfScenarioReadSupport.text(scenario, "row_alloc_mode"));
            if (allocMode == null) {
                allocMode = perfConfigReader.datasetRowAllocMode();
            }
            if (!PerfDatasetAllocPolicy.supports("GATLING", allocMode)) {
                throw new ServiceException("行分配策略 VUSER_EXCLUSIVE 需要每虚拟用户私有行切片，"
                        + "Gatling 3.13.5 不具备每用户私有数据行的读取形态（多用户共用数据行会谎报独占流量口径）——"
                        + "请改用支持按用户切片的 k6 引擎，或在该场景「行分配策略」改用 "
                        + "SEQUENTIAL_EXCLUSIVE/CYCLE 策略", BizErrorCode.VALIDATION_ERROR);
            }
            dataset = datasetRows.load(
                    ((Number) scenario.get("project_id")).longValue(), datasetId);
            dsArg = new TemplateDataset("ds_" + datasetId + ".csv", allocMode, 0);
        }
        String source = new GatlingSimulationTemplate()
                .render(simulationName, model, config, steps, baseUrl,
                        PerfScenarioReadSupport.text(scenario, "login_strategy"), dsArg,
                        "HEADER_MARK".equals(PerfScenarioReadSupport.text(scenario, "isolation_mode")));
        // A-10 : javac still needs an on-disk working tree, but it
        // is now pure compile scratch — the dispatch handle is the object key of
        // a zip bundle carrying the whole run directory (manifest + source +
        // classes/** + dataset sidecar). Manifest pointers become zip-relative
        // names so the executor can materialize and rebase them.
        Path runDir = null;
        try {
            runDir = Files.createTempDirectory("tp-perf-gatling-");
            Path classesDir = runDir.resolve("classes");
            Files.createDirectories(classesDir);
            if (dataset != null) {
                Files.writeString(classesDir.resolve(dsArg.fileName()), dataset.toCsvText());
            }
            Path sourceFile = runDir.resolve(simulationName + ".java");
            Files.writeString(sourceFile, source);
            compileWithJavac(sourceFile, classesDir);
            Files.writeString(runDir.resolve("manifest.json"),
                    objectMapper.writeValueAsString(Map.of(
                            "fqcn", GENERATED_PACKAGE + "." + simulationName,
                            "classesDir", "classes",
                            "sourceFile", simulationName + ".java")));
            return artifactBundler.bundleEntries(
                    ((Number) scenario.get("project_id")).longValue(), simulationName,
                    flattenRunDir(runDir));
        } catch (IOException ioFailure) {
            throw new ServiceException("压测仿真产物落盘失败：" + ioFailure.getMessage(),
                    BizErrorCode.VALIDATION_ERROR);
        } finally {
            deleteTreeQuietly(runDir);
        }
    }

    /** Collect every file under the run directory into zip-relative entries. */
    private static Map<String, byte[]> flattenRunDir(Path runDir) throws IOException {
        Map<String, byte[]> entries = new java.util.LinkedHashMap<>();
        try (var walk = Files.walk(runDir)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                entries.put(runDir.relativize(file).toString().replace('\\', '/'),
                        Files.readAllBytes(file));
            }
        }
        return entries;
    }

    /** Scratch-tree cleanup: compile artifacts live in object storage, not on disk. */
    private static void deleteTreeQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // Scratch leftover — the bundle is already in storage.
                }
            });
        } catch (IOException ignored) {
            // Directory vanished mid-walk (temp sweep) — nothing to reclaim.
        }
    }

    /**
 * In-process javac compile of the generated source (JDK-tool, no extra
 * dependency). Classpath rides the running JVM's own java.class.path — the
 * Gatling jars are already there via gatling-app, so anything that fails to
 * compile here is a real packaging gap, named with the compiler diagnostics.
 * <p>Stack contract : a 500-step scenario renders
 * one nested {@code .exec(...)} expression 500 links deep and javac's parser
 * recurses per link — on a thin caller stack (web request thread) that
 * recursion overflows INSIDE the compiler task, which swallows the error and
 * reports a fake "failed, no diagnostics". The compile therefore runs on a
 * dedicated generous-stack thread instead of borrowing the caller's remaining
 * stack, so the result never depends on who invoked it.
 */
    private void compileWithJavac(Path sourceFile, Path classesDir) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new ServiceException("Gatling 压测无法编译：当前 JVM 非 JDK 运行（无 javac 编译器），"
                    + "请确认 core 服务以 JDK 21 启动，禁止静默跳过编译段",
                    BizErrorCode.VALIDATION_ERROR);
        }
        final Throwable[] workerFailure = new Throwable[1];
        final boolean[] okBox = new boolean[1];
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        Thread compileThread = new Thread(null, () -> {
            try (StandardJavaFileManager fm =
                         compiler.getStandardFileManager(diagnostics, null, null)) {
                Iterable<? extends JavaFileObject> units = fm.getJavaFileObjects(sourceFile.toFile());
                List<String> options = List.of(
                        "-classpath", System.getProperty("java.class.path"),
                        "-d", classesDir.toString());
                okBox[0] = compiler.getTask(null, fm, diagnostics, options, null, units).call();
            } catch (Throwable t) {
                workerFailure[0] = t;
            }
        }, "gatling-javac-" + classesDir.getFileName(), JAVAC_STACK_BYTES);
        compileThread.start();
        try {
            compileThread.join(JAVAC_JOIN_TIMEOUT_MILLIS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ServiceException("Gatling 仿真编译被中断：等待编译线程时被取消，"
                    + "请重新发起压测运行", BizErrorCode.VALIDATION_ERROR);
        }
        if (compileThread.isAlive()) {
            compileThread.interrupt();
            throw new ServiceException("Gatling 仿真编译超时（" + JAVAC_JOIN_TIMEOUT_MILLIS
                    / 1000 + " 秒）：拒绝无限等待，请检查步骤规模或引擎依赖状态",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (workerFailure[0] != null) {
            throw new ServiceException("压测仿真编译失败：" + workerFailure[0],
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (!okBox[0]) {
            String first = diagnostics.getDiagnostics().stream()
                    .map(d -> d.getMessage(null)).findFirst().orElse("（无诊断输出）");
            throw new ServiceException("Gatling 仿真编译失败：" + first,
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    @Override
    public String launch(String artifact, Map<String, Object> runContext) {
        if (!engineRuntimePresent()) {
            throw new ServiceException("Gatling 引擎运行件缺失（依赖未随包就位），无法启动压测；"
                    + "请检查 gatling-core/http/app 3.13.5 打包，禁止静默切换其它引擎顶替",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (!artifactBundler.handleReadable(artifact)) {
            throw new ServiceException("Gatling 压测无法启动：编译产物（manifest）不存在或不可读「"
                    + artifact + "」，请先重新编译场景", BizErrorCode.VALIDATION_ERROR);
        }
        // The real press runs on the executor side: perf_worker materializes this
        // object-key bundle , reads the manifest and launches
        // `java -cp classes:lib/* io.gatling.app.Gatling` (process boundary, AGPL
        // body never enters the repo). A legacy local-path handle (in-flight
        // orders) keeps reading directly. Handle = bundle + runId combination.
        return "gatling://" + runContext.get("runId") + "/"
                + Path.of(artifact.replace('\\', '/')).getFileName();
    }

    @Override
    public List<PerfMetricFrame> collect(String engineSession) {
        if (engineSession == null || !engineSession.startsWith("gatling://")) {
            return List.of();
        }
        return List.of();
    }

    @Override
    public PerfAttribution attribute(Map<String, Object> run, List<PerfMetricFrame> frames) {
        return slaJudge.judge(run, frames);
    }


    private boolean engineRuntimePresent() {
        try {
            Class.forName(ENGINE_PROBE_CLASS);
            return true;
        } catch (ClassNotFoundException missing) {
            return false;
        }
    }

    private JsonNode readJson(String raw, String what) {
        return PerfScenarioReadSupport.readJson(objectMapper, raw, what);
    }
}
