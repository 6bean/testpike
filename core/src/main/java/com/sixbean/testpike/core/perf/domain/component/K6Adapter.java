package com.sixbean.testpike.core.perf.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;


@Component
public class K6Adapter implements EngineAdapter {

    
    static final String K6_BINARY = "k6";

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final PerfStepActionGate stepActionGate;
    private final PerfSlaJudge slaJudge;
    private final PerfDatasetRows datasetRows;
    private final PerfConfigReader perfConfigReader;
    private final PerfArtifactBundler artifactBundler;

    public K6Adapter(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper,
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
        // A-6 engine boundary (DSL v2 : k6 VUs are memory-isolated by
        // design (each VU owns its own module instance), so SHARED (one login
        // for the whole run) and PER_SESSION_N (N users share one login) have
        // no truthful compilation — silently degrading them to per-VUSER would
        // fake a traffic shape that contradicts the declared strategy, so the
        // compile fails fast naming the engine that carries the strategy
        // (Gatling sessions support cross-iteration sharing; same honest
        // fail-closed posture as the missing-binary path in launch()).
        PerfScenarioReadSupport.rejectSharedSessionStrategy(scenario,
                " 需要跨虚拟用户共享会话，k6 引擎按 VU 内存隔离设计无法真实编译该形态"
                        + "（静默降级为每用户登录会伪造流量口径）——请改用支持共享会话的 "
                        + "Gatling 引擎执行该场景");
        // B-3 mix : the save gate accepts a structurally
        // complete transaction/weight declaration; the compile stage answers
        // per engine what it can actually render. The k6 weighted multi-block
        // mapping landed in stage c-2β (one executor block per link, weight
        // folded into its arrival rate, exec pointer at the link's own step
        // function) — this engine now answers mixSupported=true. Gatling's
        // exec-split legs land in c-2γ and stay fail-closed until then (a flat
        // compile of a declared 80/20 mix would report uniform pressure as a
        // mixed-link run = fake mix, the same fail-closed family as the SHARED
        // strategy refusal above).
        stepActionGate.rejectUiActions(steps, true, true);

        long envId = ((Number) scenario.get("env_id")).longValue();
        String baseUrl = jdbc.queryForObject("SELECT base_url FROM tp_project_env WHERE id = :e",
                new MapSqlParameterSource("e", envId), String.class);
        boolean headerMark = "HEADER_MARK".equals(PerfScenarioReadSupport.text(scenario, "isolation_mode"));

        // data-pool segment B②a: a bound dataset materializes as a JSON
        // sidecar inside the artifact directory (k6 open() resolves relative to
        // the script) and its rows flow through the single tpVars pool.
        // Allocation matrix (recon ruling, single source PerfRowAllocModes):
        // k6 carries CYCLE + VUSER_EXCLUSIVE; SEQUENTIAL_EXCLUSIVE needs a
        // cross-VU atomic lease k6 does not offer — silently sharing rows would
        // fake exclusive semantics, so it fails closed naming Gatling (same
        // honest posture as the SHARED login-strategy refusal above).
        TemplateDataset dsArg = null;
        byte[] datasetSidecarBytes = null;
        String datasetIdRaw = PerfScenarioReadSupport.text(scenario, "dataset_id");
        if (datasetIdRaw != null && !datasetIdRaw.isBlank()) {
            long datasetId = Long.parseLong(datasetIdRaw);
            // Strategy single resolution: launch pins row_alloc_mode_resolved
            // into the dispatch view; a raw view compiled elsewhere (tests,
            // recompile) carries only the persisted column, and an unexplicit
            // binding falls back to the live K8 default key.
            String allocMode = PerfRowAllocModes.firstNonBlank(
                    PerfScenarioReadSupport.text(scenario, "row_alloc_mode_resolved"), PerfScenarioReadSupport.text(scenario, "row_alloc_mode"));
            if (allocMode == null) {
                allocMode = perfConfigReader.datasetRowAllocMode();
            }
            if (!PerfDatasetAllocPolicy.supports("K6", allocMode)) {
                throw new ServiceException("行分配策略 SEQUENTIAL_EXCLUSIVE 需要跨虚拟用户的"
                        + "原子行租约，k6 引擎无此原语（静默共行会伪造独占流量口径）——"
                        + "请改用原生支持队列馈器的 Gatling 引擎执行该场景",
                        BizErrorCode.VALIDATION_ERROR);
            }
            PerfDatasetRows.Dataset dataset = datasetRows.load(
                    ((Number) scenario.get("project_id")).longValue(), datasetId);
            int vuCount = preAllocatedVus(model, config, steps, perfConfigReader.vuPerRps());
            if (PerfRowAllocModes.VUSER_EXCLUSIVE.equals(allocMode)
                    && dataset.rowCount() < vuCount) {
                throw new ServiceException("数据池独占切片行不足：数据集「" + datasetId + "」共 "
                        + dataset.rowCount() + " 行，少于压力形态分配的 " + vuCount
                        + " 虚拟用户，部分用户将分不到独占行——请扩充数据集行数、改用 CYCLE"
                        + " 轮询策略，或降低压力配置", BizErrorCode.VALIDATION_ERROR);
            }
            String fileName = "ds_" + datasetId + ".json";
            dsArg = new TemplateDataset(fileName, allocMode, vuCount);
            datasetSidecarBytes = dataset.toJsonRows(objectMapper)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }

        String scriptName = "PerfGenK6_" + scenario.get("id") + "T"
                + (System.nanoTime() % 1_000_000L);
        String source = new K6ScriptTemplate()
                .render(model, config, steps, baseUrl, headerMark, dsArg,
                        perfConfigReader.vuPerRps());
        // A-10 : the compile handle is a project-routed object key
        // into a zip bundle (script + optional dataset sidecar), not a shared-temp
        // path — the dispatch envelope carries the key so any executor node can
        // materialize the bundle and a tmp sweep can never stale-out a
        // queued run.
        java.util.Map<String, byte[]> entries = new java.util.LinkedHashMap<>();
        entries.put(scriptName + ".js", source.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if (datasetSidecarBytes != null) {
            entries.put(dsArg.fileName(), datasetSidecarBytes);
        }
        return artifactBundler.bundleEntries(
                ((Number) scenario.get("project_id")).longValue(), scriptName, entries);
    }

    @Override
    public String launch(String artifact, Map<String, Object> runContext) {
        if (!k6BinaryPresent()) {
            throw new ServiceException("k6 引擎二进制缺失（AGPL 进程边界处置=用户自装，"
                    + "本系统不随包分发）：请在执行机 PATH 安装 k6 后重试，禁止静默切换"
                    + " Gatling 顶替备选引擎", BizErrorCode.VALIDATION_ERROR);
        }
        if (!artifactBundler.handleReadable(artifact)) {
            throw new ServiceException("k6 压测无法启动：编译产物不存在或不可读「"
                    + artifact + "」，请先重新编译场景", BizErrorCode.VALIDATION_ERROR);
        }
        // A-10 dual form: artifact is an object key (or a legacy local path for
        // in-flight orders) — the session id keeps only the terminal name segment.
        return "k6://" + runContext.get("runId") + "/"
                + Path.of(artifact.replace('\\', '/')).getFileName();
    }

    @Override
    public List<PerfMetricFrame> collect(String engineSession) {
        if (engineSession == null || !engineSession.startsWith("k6://")) {
            return List.of();
        }
        return List.of();
    }

    @Override
    public PerfAttribution attribute(Map<String, Object> run, List<PerfMetricFrame> frames) {
        return slaJudge.judge(run, frames);
    }


    
    private boolean k6BinaryPresent() {
        for (String dir : System.getenv("PATH").split(java.io.File.pathSeparator)) {
            Path candidate = Path.of(dir, K6_BINARY);
            if (Files.isExecutable(candidate)) {
                return true;
            }
        }
        return false;
    }

    private JsonNode readJson(String raw, String what) {
        return PerfScenarioReadSupport.readJson(objectMapper, raw, what);
    }

    /**
 * Virtual users the compiled scenario will allocate (the pinned VUSER_EXCLUSIVE
 * segment divisor): every arrival-rate executor targets a rate that PerfVuBudget
 * scales by the K8 coefficient (constant=target RPS, ramp=peak RPS, spike=max of
 * the two) — the same call the script template makes, so the divisor and the
 * emitted preAllocatedVUs can never drift apart .
 * <p>B-3 mix (stage c-2β): a mix scenario emits one block set PER LINK with the
 * weight-scaled config, so the divisor is the SUM of every link's block budget —
 * k6 numbers __VU globally across executors and the stride segment must cover the
 * whole pool (an under-count would share rows between users and fake the
 * exclusive semantics; a ceil-driven over-count only rejects early, honestly).
 */
    private static int preAllocatedVus(String model, JsonNode config, JsonNode steps, int vuPerRps) {
        PerfMixGroups groups = PerfMixGroups.derive(steps);
        if (groups.isFlat()) {
            return preAllocatedVusForModel(model, config, vuPerRps);
        }
        int total = 0;
        for (PerfMixGroups.Link link : groups.links()) {
            total += preAllocatedVusForModel(model,
                    K6ScriptTemplate.scaledConfigForLink(config, groups, link), vuPerRps);
        }
        return total;
    }

    private static int preAllocatedVusForModel(String model, JsonNode config, int vuPerRps) {
        int peak = config.path("peakRps").asInt(0);
        return switch (model) {
            case "CONSTANT_RPS", "SOAK" ->
                    PerfVuBudget.scale(config.path("targetRps").asInt(0), vuPerRps);
            case "RAMP" -> PerfVuBudget.scale(peak, vuPerRps);
            case "SPIKE" -> PerfVuBudget.scale(Math.max(peak, config.path("baseRps").asInt(0)), vuPerRps);
            // B-2 : FIXED_VU is a closed pool — the declared VU count is literal,
            // not rate-scaled (thinking time makes RPS a derived, not a driving, number).
            case "FIXED_VU" -> config.path("vu").asInt(0);
            // LADDER compiles to arrival-rate stages up to the search top; BUSINESS_CURVE
            // rides hourly constant-rate segments, its ceiling is peakRps.
            case "LADDER" -> PerfVuBudget.scale(config.path("startRps").asInt(0)
                    + config.path("stepRps").asInt(0) * config.path("steps").asInt(0), vuPerRps);
            case "BUSINESS_CURVE" -> PerfVuBudget.scale(peak, vuPerRps);
            default -> 0;
        };
    }
}
