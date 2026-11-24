package com.sixbean.testpike.core.perf.domain.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.perf.domain.component.PerfRunRefusals;
import com.sixbean.testpike.core.audit.domain.component.Audit;
import com.sixbean.testpike.core.common.domain.component.SortColumnPolicy;
import com.sixbean.testpike.core.mock.domain.component.PageWindow;
import com.sixbean.testpike.core.perf.api.dto.PerfRunPageView;
import com.sixbean.testpike.core.perf.api.dto.PerfGateCheckInput;
import com.sixbean.testpike.core.perf.domain.component.PerfAccessGate;
import com.sixbean.testpike.core.perf.domain.component.PerfBudgetPolicy;
import com.sixbean.testpike.core.perf.domain.component.PerfCanaryPreflight;
import com.sixbean.testpike.core.perf.domain.component.PerfConfigReader;
import com.sixbean.testpike.core.perf.domain.component.PerfDslFingerprint;
import com.sixbean.testpike.core.perf.domain.component.PerfEnvExclusionGuard;
import com.sixbean.testpike.core.perf.domain.component.PerfEnvOccupant;
import com.sixbean.testpike.core.perf.domain.component.PerfEngineResolver;
import com.sixbean.testpike.core.perf.domain.component.PerfPressureTier;
import com.sixbean.testpike.core.perf.domain.component.PerfGateDecision;
import com.sixbean.testpike.core.perf.domain.component.PerfQueueNote;
import com.sixbean.testpike.core.perf.domain.component.PerfRunDispatcher;
import com.sixbean.testpike.core.perf.domain.service.PerfRunService;
import com.sixbean.testpike.core.project.domain.component.EnvTlsSupport;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;


@Service
public class PerfRunServiceImpl implements PerfRunService {

    private static final Set<Integer> STATUSES = Set.of(1, 2, 3, 4);
    private static final Set<Integer> FINAL_STATUSES = Set.of(2, 3, 4);
    private static final Set<String> MARKS = Set.of("GOLDEN", "CANDIDATE", "VOID");

    private static final String SELECT_COLUMNS = """
            id, scenario_id, project_id, status,
            pressure_snapshot::text AS pressure_snapshot, stop_reason, operator_id,
            started_at, finished_at, created_at, mark
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final PerfConfigReader perfConfigReader;
    private final PerfEngineResolver perfEngineResolver;
    private final PerfRunDispatcher perfRunDispatcher;
    private final PerfAccessGate perfAccessGate;
    private final PerfEnvExclusionGuard perfEnvExclusionGuard;
    private final PerfDslFingerprint perfDslFingerprint;
    private final PerfQueueNote perfQueueNote;
    private final PerfCanaryPreflight perfCanaryPreflight;
    private final EnvTlsSupport envTlsSupport;
    private final PerfPressureTier perfPressureTier;

    public PerfRunServiceImpl(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper,
                              PerfConfigReader perfConfigReader,
                              PerfEngineResolver perfEngineResolver,
                              PerfRunDispatcher perfRunDispatcher,
                              PerfAccessGate perfAccessGate,
                              PerfEnvExclusionGuard perfEnvExclusionGuard,
                              PerfDslFingerprint perfDslFingerprint,
                              PerfQueueNote perfQueueNote,
                              PerfCanaryPreflight perfCanaryPreflight,
                              EnvTlsSupport envTlsSupport,
                              PerfPressureTier perfPressureTier) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.perfConfigReader = perfConfigReader;
        this.perfEngineResolver = perfEngineResolver;
        this.perfRunDispatcher = perfRunDispatcher;
        this.perfAccessGate = perfAccessGate;
        this.perfEnvExclusionGuard = perfEnvExclusionGuard;
        this.perfDslFingerprint = perfDslFingerprint;
        this.perfQueueNote = perfQueueNote;
        this.perfCanaryPreflight = perfCanaryPreflight;
        this.envTlsSupport = envTlsSupport;
        this.perfPressureTier = perfPressureTier;
    }

    @Override
    @Transactional
    @Audit(objectType = "perf_run", action = "launch", objectId = "#result")
    public long launch(long projectId, long scenarioId, Long operatorId) {
        return launchInternal(projectId, scenarioId, null, operatorId);
    }

    @Override
    @Transactional
    @Audit(objectType = "perf_run", action = "launch", objectId = "#result")
    public long launch(long projectId, long scenarioId, Long envId, Long operatorId) {
        return launchInternal(projectId, scenarioId, envId, operatorId);
    }

    private long launchInternal(long projectId, long scenarioId, Long envIdOverride, Long operatorId) {
        Map<String, Object> scenario = jdbc.queryForList("SELECT id, project_id, env_id, name, pressure_model, "
                        + "pressure_config::text AS pressure_config, login_strategy, "
                        + "steps_json::text AS steps_json, sla_json::text AS sla_json, isolation_mode, "
                        + "dataset_id, row_alloc_mode, engine "
                        + "FROM tp_perf_scenario WHERE id = :s AND project_id = :p",
                new MapSqlParameterSource("s", scenarioId).addValue("p", projectId))
                .stream().findFirst()
                .orElseThrow(() -> new ServiceException(PerfRunRefusals.scenarioMissing(scenarioId),
                        BizErrorCode.NOT_FOUND));
        // GB-1 scenario × environment decoupling: an explicit launch-time environment
        // override replaces the scenario default for every downstream decision — gate
        // tier, GA-2 lock, dispatch base-url, pinned snapshot. Resolution happens once
        // here (single source, no scenario.env_id re-reads below); a non-null override
        // must exist and belong to the project, never a silent fallback (a wrong env
        // silently replaced by the default would press a target the operator did not
        // choose). null keeps the historical behaviour byte-for-byte.
        long effectiveEnvId = resolveEffectiveEnvId(projectId, scenario, envIdOverride);
        scenario.put("env_id", effectiveEnvId);
        // GA-5 target-version pinning: the deployed build recorded on the effective
        // environment (tp_project_env.version_snapshot — the same field the deploy-status
        // face maintains; no second version registry) is frozen into the snapshot so an
        // upgrade-vs-baseline comparison can self-prove which version was pressed.
        // No recorded version = honest null, never a fabricated or stale label.
        String targetVersion = resolveTargetVersion(effectiveEnvId);
        // Launch-time enforcement of the safety gate: the precheck endpoint is only a UX
        // affordance, so the same eight-step decision runs here inside the launch transaction.
        // A rejection rolls everything back — no orphan run row, no dispatch task (wizard-bypass
        // must not be a way to press production).
        gateEnforce(projectId, scenario);
        // Launch-time duration ceiling (A-9 precondition): a declared run longer
        // than the hot-updatable perf.max-duration-hours key is rejected inside this
        // transaction (zero residue) — long soak runs must be split into ceiling-sized
        // batches instead of pinning an environment and the dispatch budget for days.
        durationCeilingEnforce(scenario);
        // CF-6 canary pre-flight (K8 perf.canary.enabled, default off = today's
        // posture byte-for-byte): a low-load probe of the effective environment runs
        // inside the launch transaction BEFORE the run row exists, so a dead or
        // pathologically slow target rejects with zero residue instead of burning the
        // approval window on a run that could only fail at press time. For the
        // Gatling default engine the first javac compile is pre-warmed in the same
        // step (GB-5 cold-start compensation). The passing outcome rides the pinned
        // snapshot ("canary") so the report face attributes against the measurement.
        Map<String, Object> canary = perfCanaryPreflight.preflight(projectId, effectiveEnvId);
        // GA-2 environment execution lock (inside the same transaction, after the advisory
        // lock is taken): an environment with an active perf run or an executing regression
        // batch rejects this launch with a reason naming the occupant — rejection rolls
        // everything back, zero residue.
        PerfEnvOccupant occupant = perfEnvExclusionGuard
                .lockAndFindOccupant(((Number) scenario.get("env_id")).longValue());
        if (occupant != null) {
            throw new ServiceException(perfEnvExclusionGuard.formatRejection(occupant),
                    BizErrorCode.FORBIDDEN);
        }
        Map<String, Object> snapshot = new HashMap<>(Map.of(
                "name", String.valueOf(scenario.get("name")),
                "pressureModel", String.valueOf(scenario.get("pressure_model")),
                "pressureConfig", String.valueOf(scenario.get("pressure_config")),
                "loginStrategy", String.valueOf(scenario.get("login_strategy")),
                "steps", String.valueOf(scenario.get("steps_json"))));
        // GB-1: the effective environment (override or scenario default) is frozen into
        // the snapshot so reports and the exclusion guard's occupant wording read the
        // same truth the gate checked — a later scenario edit never rewrites history.
        snapshot.put("envId", effectiveEnvId);
        snapshot.put("targetVersion", targetVersion);
        // B-10 : the effective environment's TLS tier is pinned
        // next to targetVersion — the report face must be able to prove which
        // verification tier this run pressed (a custom-CA/insecure target that
        // only works because of tier injection is a different truth than a
        // plain strict run). Snapshot label only (strict/custom_ca/insecure);
        // CA/cert material NEVER enters a persisted snapshot — it rides the
        // claim envelope decrypted, same discipline as the apitest envelope.
        snapshot.put("tlsMode", envTlsSupport.resolvePolicyFailSafe(
                        envConfigJsonOf(effectiveEnvId)).mode().raw());
        // CF-6: the canary outcome is pinned alongside the environment it measured
        // (null when the pre-flight switch is off — the key is simply absent, the
        // historical snapshot shape). Not a DSL-bearing key (identity, same
        // exclusion roster as targetVersion).
        if (canary != null) {
            snapshot.put("canary", canary);
        }
        snapshot.put("sla", effectiveSla(scenario.get("sla_json")));
        // Isolation declaration is part of the pinned snapshot so reports and the executor
        // consume the same truth the gate checked (A-5: null = not declared, non-production).
        snapshot.put("isolationMode", scenario.get("isolation_mode"));
        // data pool (GA-5 pinning discipline): the bound dataset and the
        // resolved allocation strategy are frozen into the snapshot at launch —
        // an unbound scenario pins null/null (runs without a pool, today's
        // shape); a bound scenario with no explicit strategy resolves the K8
        // default key once here so reports and the compile stage ride the same
        // pinned truth (no in-run re-read, no drift).
        snapshot.put("datasetId", scenario.get("dataset_id"));
        Object rawAlloc = scenario.get("row_alloc_mode");
        String allocMode = rawAlloc == null
                ? (scenario.get("dataset_id") == null ? null
                        : perfConfigReader.datasetRowAllocMode())
                : String.valueOf(rawAlloc);
        snapshot.put("rowAllocMode", allocMode);
        // W-d engine pinning (A6 dual-source fix): the effective engine this run
        // presses with is frozen into the snapshot through the SAME single door
        // (PerfEngineResolver) the dispatch envelope resolves through — a blank
        // scenario column riding the K8 default key must not read as a different
        // engine on the report face after a later scenario edit (no re-derivation
        // from the live scenario row; launch truth vs display truth = one truth).
        snapshot.put("engine", perfEngineResolver.resolve(scenario.get("engine")));
        // slice-b DSL fingerprint (single write position = this launch pin):
        // sha256 over the snapshot's DSL-bearing keys lets the compare gate admit
        // cross-scenario pairs whose pinned DSL is identical (cloned scenarios).
        // Computed from the frozen snapshot itself, so the hash always describes
        // exactly what this run pressed — later scenario edits never rewrite it.
        snapshot.put("dslHash", perfDslFingerprint.hashOf(snapshot));
        // Dispatch view carries the resolved strategy alongside the raw column
        // so adapters never re-read K8 (single resolution point above).
        scenario.put("row_alloc_mode_resolved", allocMode);
        long runId = jdbc.queryForObject("INSERT INTO tp_perf_run (scenario_id, project_id, status, "
                        + "pressure_snapshot, operator_id) VALUES (:s, :p, 1, CAST(:n AS jsonb), :o) "
                        + "RETURNING id",
                new MapSqlParameterSource("s", scenarioId).addValue("p", projectId)
                        .addValue("n", writeJson(snapshot)).addValue("o", operatorId),
                Long.class);
        perfRunDispatcher.dispatch(projectId, runId, scenario);
        return runId;
    }

    /**
     * Resolve the environment this launch presses (GB-1 single resolution point).
     * null override → the scenario default (historical behaviour); non-null override
     * → validated to exist and belong to the project, in one human-readable wording
     * whether it is unknown or foreign (never a silent fallback to the default: the
     * operator chose a target, pressing a different one quietly is worse than refusing).
     */
    private long resolveEffectiveEnvId(long projectId, Map<String, Object> scenario,
                                       Long envIdOverride) {
        long scenarioDefault = ((Number) scenario.get("env_id")).longValue();
        if (envIdOverride == null) {
            return scenarioDefault;
        }
        Map<String, Object> env = jdbc.queryForList(
                        "SELECT id, project_id FROM tp_project_env WHERE id = :e",
                        new MapSqlParameterSource("e", envIdOverride))
                .stream().findFirst().orElse(null);
        if (env == null) {
            throw new ServiceException(PerfRunRefusals.envMissing(envIdOverride),
                    BizErrorCode.NOT_FOUND);
        }
        if (((Number) env.get("project_id")).longValue() != projectId) {
            throw new ServiceException(PerfRunRefusals.envNotInProject(envIdOverride, projectId),
                    BizErrorCode.FORBIDDEN);
        }
        return envIdOverride;
    }

    /**
     * Read the deployed build recorded on the effective environment (GA-5 single pin
     * position). One lightweight query on tp_project_env.version_snapshot — the field
     * the deploy-status face already maintains — so perf never grows a second version
     * registry; absent/blank = null (an honest "no version recorded" in the snapshot,
     * never a guessed label).
     */
    private String resolveTargetVersion(long effectiveEnvId) {
        return jdbc.queryForList("SELECT version_snapshot FROM tp_project_env WHERE id = :e",
                        new MapSqlParameterSource("e", effectiveEnvId))
                .stream().findFirst()
                .map(row -> row.get("version_snapshot"))
                .filter(java.util.Objects::nonNull)
                .map(String::valueOf)
                .filter(value -> !value.isBlank())
                .orElse(null);
    }

    /**
     * Read the raw config_json of the effective environment (B-10 single pin
     * position). Absent row = null (a concurrent delete between the gate check
     * and this read is already fenced by the GA-2 lock; the fail-safe resolver
     * turns null into a strict label either way, never a launch crash).
     */
    private String envConfigJsonOf(long effectiveEnvId) {
        return jdbc.queryForList("SELECT config_json::text AS cfg FROM tp_project_env WHERE id = :e",
                        new MapSqlParameterSource("e", effectiveEnvId))
                .stream().findFirst()
                .map(row -> row.get("cfg"))
                .map(String::valueOf)
                .orElse(null);
    }

    /**
     * Launch-time enforcement of the safety gate (A-1): builds the same input the precheck
     * consumes — concurrency = peak pressure of the model, isolation = the mode declared on the
     * scenario, target = null (env record address decides) — and runs the shared gate. A rejection
     * throws with the gate's human-readable reason and the whole launch transaction rolls back.
     * Single gate source: precheck endpoint and this call site share {@link PerfAccessGate}.
     */
    private void gateEnforce(long projectId, Map<String, Object> scenario) {
        Integer concurrency = peakConcurrency(String.valueOf(scenario.get("pressure_model")),
                (String) scenario.get("pressure_config"));
        PerfGateDecision decision = perfAccessGate.evaluate(new PerfGateCheckInput(projectId,
                ((Number) scenario.get("env_id")).longValue(), concurrency,
                (String) scenario.get("isolation_mode"), null));
        if (!decision.passed()) {
            throw new ServiceException(PerfRunRefusals.gateBlocked(decision.reason()),
                    BizErrorCode.FORBIDDEN);
        }
    }

    /**
     * Duration ceiling enforcement (A-9 precondition). The declared duration is
     * resolved through the shared {@link PerfBudgetPolicy#durationSecondsOf} (same
     * key dialect as the A-14 budget and the GA-2 release estimate — one source).
     * Scenarios with no resolvable duration (legacy/unknown model) are not rejected
     * here, keeping the honest no-claim posture those faces already show. The
     * rejection names the declared duration and the current ceiling in human terms.
     */
    private void durationCeilingEnforce(Map<String, Object> scenario) {
        double durationSeconds;
        try {
            com.fasterxml.jackson.databind.JsonNode config = objectMapper
                    .readTree(String.valueOf(scenario.get("pressure_config")));
            durationSeconds = PerfBudgetPolicy.durationSecondsOf(
                    (String) scenario.get("pressure_model"), config);
        } catch (Exception unparsable) {
            return;
        }
        if (durationSeconds <= 0) {
            return;
        }
        int ceilingHours = perfConfigReader.maxDurationHours();
        if (durationSeconds > ceilingHours * 3600.0) {
            throw new ServiceException(PerfRunRefusals.durationCeiling(
                    trimHours(durationSeconds / 3600.0), ceilingHours),
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    private static String trimHours(double hours) {
        return hours == Math.floor(hours) ? String.valueOf((long) hours)
                : String.format(java.util.Locale.ROOT, "%.2f", hours);
    }

    /**
 * Peak concurrency tier (precheck input mirror): CONSTANT_RPS uses targetRps, RAMP/SPIKE use
 * peakRps. B-2 models : FIXED_VU is closed-model — its user pool size IS the tier;
 * LADDER plans for the top of the search (startRps + stepRps x steps); BUSINESS_CURVE peaks
 * at peakRps by definition; SOAK is a constant-rate soak (targetRps).
 * Unparseable pressure config = null so the gate rejects fail-closed (a launch must
 * never bypass the gate on malformed data).
 */
    private Integer peakConcurrency(String model, String pressureConfigJson) {
        // Single source (red line 6): the tier derivation moved verbatim into
        // PerfPressureTier so the CF-5 pre-flight card and this launch gate
        // always speak the same number — this call site keeps ownership of the
        // fail-closed posture (null = gate rejects a malformed plan).
        return perfPressureTier.of(model, pressureConfigJson);
    }

    
    private Map<String, Object> effectiveSla(Object rawSlaJson) {
        Map<String, Object> sla = new LinkedHashMap<>();
        if (rawSlaJson != null) {
            try {
                Map<String, Object> parsed = objectMapper.readValue(String.valueOf(rawSlaJson),
                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                        });
                parsed.forEach((key, value) -> {
                    if (value instanceof Number n) {
                        sla.put(key, n.doubleValue());
                    }
                });
            } catch (Exception corrupt) {
                sla.clear();
            }
        }
        sla.putIfAbsent("breakerErrorRate", perfConfigReader.breakerErrorRate());
        return sla;
    }

    @Override
    @Audit(objectType = "perf_run", action = "finish", objectId = "#runId")
    public void finish(long projectId, long runId, int status, String stopReason) {
        if (!STATUSES.contains(status)) {
            throw new ServiceException(PerfRunRefusals.unknownStatus(status),
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (!FINAL_STATUSES.contains(status)) {
            throw new ServiceException(PerfRunRefusals.statusNotTerminal(status),
                    BizErrorCode.VALIDATION_ERROR);
        }
        int updated = jdbc.update("UPDATE tp_perf_run SET status = :st, stop_reason = :r, "
                        + "finished_at = now() WHERE id = :id AND project_id = :p AND status = 1",
                new MapSqlParameterSource("st", status).addValue("r", stopReason)
                        .addValue("id", runId).addValue("p", projectId));
        if (updated == 0) {
            throw new ServiceException(PerfRunRefusals.finishNotFound(runId, projectId), BizErrorCode.NOT_FOUND);
        }
    }

    /**
     * B-7 marking chain (single transaction so the demote-then-promote pair is
     * atomic): reject unknown marks with the accepted set named, reject active
     * runs in human wording, demote the scenario's current golden to CANDIDATE,
     * then set the requested mark on the target run. The partial unique index
     * uq_perf_run_golden_per_scenario stays the last-resort guard for callers
     * that bypass this service.
     */
    @Override
    @Transactional
    @Audit(objectType = "perf_run", action = "mark", objectId = "#runId")
    public void mark(long projectId, long runId, String mark, Long operatorId) {
        if (mark == null || !MARKS.contains(mark)) {
            throw new ServiceException(PerfRunRefusals.unknownMark(mark),
                    BizErrorCode.VALIDATION_ERROR);
        }
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, status, scenario_id "
                        + "FROM tp_perf_run WHERE id = :id AND project_id = :p",
                new MapSqlParameterSource("id", runId).addValue("p", projectId));
        if (rows.isEmpty()) {
            throw new ServiceException(PerfRunRefusals.markNotFound(runId), BizErrorCode.NOT_FOUND);
        }
        Map<String, Object> run = rows.get(0);
        int status = ((Number) run.get("status")).intValue();
        if (!FINAL_STATUSES.contains(status)) {
            throw new ServiceException(PerfRunRefusals.markWhileRunning(runId, status),
                    BizErrorCode.VALIDATION_ERROR);
        }
        // A-13 decoupled history (GB-2 ledger lists it): the scenario link is
        // NULL, so there is no same-scenario incumbent golden to demote — the
        // partial unique index also treats NULL as non-colliding. The old code
        // dereferenced the NULL link here unconditionally and 500'd.
        Object scenarioId = run.get("scenario_id");
        if ("GOLDEN".equals(mark) && scenarioId != null) {
            // Demote the incumbent golden of the same scenario first — at most one
            // golden per scenario is the contract the trend/comparison faces rely on.
            jdbc.update("UPDATE tp_perf_run SET mark = 'CANDIDATE' "
                            + "WHERE scenario_id = :s AND mark = 'GOLDEN' AND id <> :id",
                    new MapSqlParameterSource("s", ((Number) scenarioId).longValue())
                            .addValue("id", runId));
        }
        jdbc.update("UPDATE tp_perf_run SET mark = :m WHERE id = :id AND project_id = :p",
                new MapSqlParameterSource("m", mark).addValue("id", runId).addValue("p", projectId));
    }

    @Override
    public Map<String, Object> view(long projectId, long runId) {
        Map<String, Object> row = jdbc.queryForList("SELECT " + SELECT_COLUMNS + " FROM tp_perf_run "
                        + "WHERE id = :id AND project_id = :p",
                new MapSqlParameterSource("id", runId).addValue("p", projectId))
                .stream().findFirst()
                .orElseThrow(() -> new ServiceException("压测运行 " + runId + " 不存在或不属于该项目；"
                        + "若是较早的运行，可能已超出历史保留期被每日清理删除"
                        + "（默认保留 90 天，金跑标记豁免；已清理的数据不可恢复，调大保留天数仅保护后续运行），"
                        + "保留天数可在「系统设置」调整 perf.run.retention-days（保存即生效）",
                        BizErrorCode.NOT_FOUND));
        perfQueueNote.annotate(List.of(row));
        return row;
    }

    @Override
    public List<Map<String, Object>> listByScenario(long projectId, long scenarioId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT " + SELECT_COLUMNS + " FROM tp_perf_run "
                        + "WHERE project_id = :p AND scenario_id = :s ORDER BY id DESC",
                new MapSqlParameterSource("p", projectId).addValue("s", scenarioId));
        perfQueueNote.annotate(rows);
        return rows;
    }

    /**
 * Paged run window : clamp via the shared PageWindow so a
 * mis-typed drawer request still shows data, total counts every run of the
 * scenario for the load-more affordance, and rows keep the historical
 * id DESC order — the drawer is a headerless div list, so there is no
 * sortable column contract to build a whitelist for (exemption recorded in
 * PerfRunPageView, same posture the design note froze).
 */
    @Override
    public PerfRunPageView listByScenarioPaged(long projectId, long scenarioId,
                                               int page, int pageSize) {
        PageWindow window = PageWindow.of(page, pageSize);
        MapSqlParameterSource params = new MapSqlParameterSource("p", projectId)
                .addValue("s", scenarioId);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM tp_perf_run "
                        + "WHERE project_id = :p AND scenario_id = :s",
                params, Long.class);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT " + SELECT_COLUMNS
                        + " FROM tp_perf_run WHERE project_id = :p AND scenario_id = :s"
                        + " ORDER BY id DESC LIMIT :limit OFFSET :offset",
                params.addValue("limit", window.size()).addValue("offset", window.offset()));
        perfQueueNote.annotate(rows);
        return new PerfRunPageView(rows, total == null ? 0L : total,
                window.page(), window.size());
    }

    /**
 * One paged window of every run in the project segment ( GB-2 run
 * ledger). The drawer window above is scenario-keyed, so a run whose
 * scenario was deleted (A-13 keeps finished history with a NULL link)
 * vanishes from the workbench — this door lists them all. Rows keep the
 * historical view columns plus a scenario_name read-model field.
 */
    @Override
    public PerfRunPageView listProjectPaged(long projectId, int page, int pageSize,
                                            String sortBy, String sortDir,
                                            Integer status, String mark) {
        PageWindow window = PageWindow.of(page, pageSize);
        MapSqlParameterSource params = new MapSqlParameterSource("p", projectId);
        // Optional narrowing filters: a blank value stays the unfiltered window;
        // a bound mark of any spelling is compared as-is, so an unknown value
        // honestly returns zero rows instead of pretending to filter.
        StringBuilder filter = new StringBuilder();
        if (status != null) {
            filter.append(" AND status = :fstatus");
            params.addValue("fstatus", status);
        }
        if (mark != null && !mark.isBlank()) {
            filter.append(" AND mark = :fmark");
            params.addValue("fmark", mark.trim());
        }
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tp_perf_run WHERE project_id = :p" + filter,
                params, Long.class);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT " + SELECT_COLUMNS
                        + " FROM tp_perf_run WHERE project_id = :p" + filter
                        + ledgerOrderClause(sortBy, sortDir)
                        + " LIMIT :limit OFFSET :offset",
                params.addValue("limit", window.size()).addValue("offset", window.offset()));
        perfQueueNote.annotate(rows);
        attachScenarioNames(rows);
        return new PerfRunPageView(rows, total == null ? 0L : total,
                window.page(), window.size());
    }

    /**
     * Ledger sort columns: only physical tp_perf_run columns — the ledger's
     * sortable headers can reach these, and an off-list or injection-shaped
     * key is refused by the shared fail-closed gate before any SQL is built
     * (same posture as the scenario window). scenario_name is deliberately
     * absent: it is an annotated read-model field, not a column, and sorting
     * by it would order by a value SQL cannot see.
     */
    private static final Map<String, String> LEDGER_SORT_COLUMNS = Map.of(
            "status", "status",
            "mark", "mark",
            "started_at", "started_at",
            "finished_at", "finished_at",
            "created_at", "created_at");

    private static String ledgerOrderClause(String sortBy, String sortDir) {
        String column = SortColumnPolicy.require(sortBy, LEDGER_SORT_COLUMNS, "压测运行台账");
        if (column == null) {
            return " ORDER BY id DESC";
        }
        return " ORDER BY " + column
                + ("asc".equalsIgnoreCase(sortDir) ? " ASC" : " DESC")
                + ", id DESC";
    }

    /**
     * Name column for the ledger rows (one annotation pass, no per-row
     * queries): live scenario rows win so a renamed recipe shows its current
     * name; a decoupled run (A-13) falls back to the name frozen in its own
     * launch snapshot — the name it actually ran under, never a blank and
     * never a fabricated value.
     */
    private void attachScenarioNames(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return;
        }
        List<Long> linked = rows.stream()
                .map(row -> row.get("scenario_id"))
                .filter(java.util.Objects::nonNull)
                .map(id -> ((Number) id).longValue())
                .distinct().toList();
        Map<Long, String> liveNames = linked.isEmpty() ? Map.of()
                : jdbc.query("SELECT id, name FROM tp_perf_scenario WHERE id IN (:ids)",
                        new MapSqlParameterSource("ids", linked),
                        rs -> {
                            Map<Long, String> map = new java.util.HashMap<>();
                            while (rs.next()) {
                                map.put(rs.getLong("id"), rs.getString("name"));
                            }
                            return map;
                        });
        for (Map<String, Object> row : rows) {
            Object scenarioId = row.get("scenario_id");
            String name = scenarioId == null ? null
                    : liveNames.get(((Number) scenarioId).longValue());
            if (name == null) {
                name = snapshotScenarioName(row.get("pressure_snapshot"));
            }
            row.put("scenario_name", name);
        }
    }

    /** Read the pinned snapshot's name field; an unreadable blob shows nothing. */
    private String snapshotScenarioName(Object snapshotText) {
        if (snapshotText == null) {
            return null;
        }
        try {
            Object name = objectMapper.readValue(String.valueOf(snapshotText), Map.class).get("name");
            return name == null ? null : String.valueOf(name);
        } catch (Exception unreadable) {
            return null;
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception impossible) {
            throw new ServiceException(PerfRunRefusals.snapshotUnserializable(), BizErrorCode.VALIDATION_ERROR);
        }
    }
}
