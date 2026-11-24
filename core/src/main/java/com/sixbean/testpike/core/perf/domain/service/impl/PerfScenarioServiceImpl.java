package com.sixbean.testpike.core.perf.domain.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.audit.domain.component.Audit;
import com.sixbean.testpike.core.common.domain.component.SortColumnPolicy;
import com.sixbean.testpike.core.executor.domain.component.ArtifactStorage;
import com.sixbean.testpike.core.mock.domain.component.PageWindow;
import com.sixbean.testpike.core.perf.api.dto.PerfScenarioInput;
import com.sixbean.testpike.core.perf.api.dto.PerfScenarioPageView;
import com.fasterxml.jackson.databind.JsonNode;
import com.sixbean.testpike.core.perf.domain.component.PerfAccessGate;
import com.sixbean.testpike.core.perf.domain.component.PerfConfigReader;
import com.sixbean.testpike.core.perf.domain.component.PerfCustomScriptScanGate;
import com.sixbean.testpike.core.perf.domain.component.PerfRowAllocModes;
import com.sixbean.testpike.core.perf.domain.component.PerfRunDispatchSupport;
import com.sixbean.testpike.core.perf.domain.component.PerfStepActionGate;
import com.sixbean.testpike.core.perf.domain.service.PerfScenarioService;
import com.sixbean.testpike.core.sched.domain.component.ScheduleCronTimes;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;


@Service
public class PerfScenarioServiceImpl implements PerfScenarioService {

    public static final Set<String> PRESSURE_MODELS = Set.of(
            "CONSTANT_RPS", "RAMP", "SPIKE", "FIXED_VU", "LADDER", "BUSINESS_CURVE", "SOAK");

    
    public static final Set<String> SLA_KEYS = Set.of("p95Ms", "errorRate", "minRps", "breakerErrorRate");

    
    private static final Set<String> SLA_RATE_KEYS = Set.of("errorRate", "breakerErrorRate");

    /**
     * Single-source accessor for the per-model required pressure-config keys
     * (CF-4 template catalogue pre-judges shipped templates against the exact
     * same set the save gate uses — a second enumeration here would drift).
     */
    public static List<String> requiredKeysFor(String model) {
        return REQUIRED_KEYS.getOrDefault(model, List.of());
    }

    private static final Map<String, List<String>> REQUIRED_KEYS = Map.of(
            "CONSTANT_RPS", List.of("targetRps", "durationSeconds"),
            "RAMP", List.of("startRps", "peakRps", "rampSeconds", "holdSeconds"),
            "SPIKE", List.of("baseRps", "peakRps", "durationSeconds"),
            // B-2 : FIXED_VU is a closed user pool — the pool size is the only
            // unconditional key; when the run ends (loops / durationSeconds) is an
            // at-least-one rule enforced by the semantic gate below.
            "FIXED_VU", List.of("vu"),
            "LADDER", List.of("startRps", "stepRps", "stepSeconds", "steps"),
            "BUSINESS_CURVE", List.of("peakRps", "durationSeconds"),
            "SOAK", List.of("targetRps", "durationHours"));

    private static final Map<String, String> MODEL_NAMES = Map.of(
            "CONSTANT_RPS", "恒定 RPS", "RAMP", "阶梯爬坡", "SPIKE", "峰值脉冲",
            "FIXED_VU", "固定用户池闭环", "LADDER", "阶梯寻量",
            "BUSINESS_CURVE", "业务时段曲线", "SOAK", "长时浸泡");

    private static final Pattern PER_SESSION_N = Pattern.compile("PER_SESSION_[1-9]\\d*");

    private static final String SELECT_COLUMNS = """
            id, project_id, env_id, name, pressure_model,
            pressure_config::text AS pressure_config, login_strategy, driver_limit,
            steps_json::text AS steps_json, sla_json::text AS sla_json, isolation_mode,
            dataset_id, row_alloc_mode,
            schedule_cron, schedule_enabled, schedule_env_id,
            source, engine, custom_script_key,
            created_at, updated_at
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final PerfStepActionGate stepActionGate;
    private final PerfConfigReader configReader;
    private final ArtifactStorage artifactStorage;
    /** Program-lens scope single source for both paged perf doors . */
    private final com.sixbean.testpike.core.perf.domain.component.PerfLensScopeSupport lensScopeSupport;
    private final com.sixbean.testpike.core.linkage.domain.service.ObjectChangedEventPublisher
            objectChangedEvents;

    public PerfScenarioServiceImpl(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper,
                                   PerfStepActionGate stepActionGate, PerfConfigReader configReader,
                                   ArtifactStorage artifactStorage,
                                   com.sixbean.testpike.core.perf.domain.component.PerfLensScopeSupport lensScopeSupport,
                                   com.sixbean.testpike.core.linkage.domain.service.ObjectChangedEventPublisher
                                           objectChangedEvents) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.stepActionGate = stepActionGate;
        this.configReader = configReader;
        this.artifactStorage = artifactStorage;
        this.lensScopeSupport = lensScopeSupport;
        this.objectChangedEvents = objectChangedEvents;
    }

    @Override
    @Audit(objectType = "perf_scenario", action = "create", objectId = "#result")
    public long create(long projectId, PerfScenarioInput input) {
        String strategy = validate(projectId, input);
        String scriptKey = mountScript(projectId, input, null);
        try {
            long id = jdbc.queryForObject("INSERT INTO tp_perf_scenario "
                            + "(project_id, env_id, name, pressure_model, pressure_config, "
                            + "login_strategy, driver_limit, steps_json, sla_json, isolation_mode, "
                            + "dataset_id, row_alloc_mode, schedule_cron, schedule_enabled, schedule_env_id, "
                            + "source, engine, custom_script_key) "
                            + "VALUES (:p, :e, :n, :m, CAST(:c AS jsonb), :s, :d, CAST(:t AS jsonb), "
                            + "CAST(:sla AS jsonb), :iso, :ds, :ram, :sc, :se, :senv, :src, :eng, :csk) "
                            + "RETURNING id",
                    params(projectId, input, strategy, scriptKey), Long.class);
            publishScenarioChanged(id, projectId);
            return id;
        } catch (DuplicateKeyException dup) {
            artifactStorage.deleteQuietly(scriptKey);
            throw new ServiceException("同项目下已存在同名压测场景，请换个名称——可在「压测工作台」点该行「编辑」修改「场景名称」后保存，或在「新建压测场景」向导第 1 步重新命名", BizErrorCode.RESOURCE_CONFLICT);
        }
    }

    @Override
    @Audit(objectType = "perf_scenario", action = "update", objectId = "#id")
    public void update(long projectId, long id, PerfScenarioInput input) {
        String strategy = validate(projectId, input);
        // Read the previous script mount before overwriting: a re-save of a CUSTOM
        // scenario swaps the stored body (new key), and a CUSTOM→DSL switch must
        // not leave the retired script orphaned in storage.
        String previousKey = jdbc.queryForList("SELECT custom_script_key FROM tp_perf_scenario "
                        + "WHERE id = :id AND project_id = :p",
                        new MapSqlParameterSource("id", id).addValue("p", projectId))
                .stream().findFirst().map(r -> (String) r.get("custom_script_key")).orElse(null);
        String scriptKey = mountScript(projectId, input, previousKey);
        int updated = jdbc.update("UPDATE tp_perf_scenario SET env_id = :e, name = :n, "
                        + "pressure_model = :m, pressure_config = CAST(:c AS jsonb), "
                        + "login_strategy = :s, driver_limit = :d, steps_json = CAST(:t AS jsonb), "
                        + "sla_json = CAST(:sla AS jsonb), isolation_mode = :iso, "
                        + "dataset_id = :ds, row_alloc_mode = :ram, "
                        + "schedule_cron = :sc, schedule_enabled = :se, schedule_env_id = :senv, "
                        + "source = :src, engine = :eng, custom_script_key = :csk, "
                        + "updated_at = now() WHERE id = :id AND project_id = :p",
                params(projectId, input, strategy, scriptKey).addValue("id", id));
        if (updated == 0) {
            artifactStorage.deleteQuietly(scriptKey);
            throw new ServiceException("压测场景不存在或不属于该项目——请返回「压测工作台」列表刷新后重新选择场景", BizErrorCode.NOT_FOUND);
        }
        publishScenarioChanged(id, projectId);
    }

    /**
     * Announce a scenario save on the linkage bus. tp_perf_scenario carries no
     * row version column, so the announcement uses epoch seconds as
     * objectVersion: wall-clock monotone across saves, which is what the
     * impact UPSERT version guard consumes (re-arms only on a greater
     * version). A same-second double save re-announces the same facts —
     * idempotent under the guard, never a silent stale drop.
     */
    private void publishScenarioChanged(long scenarioId, long projectId) {
        objectChangedEvents.publish(
                new com.sixbean.testpike.core.linkage.api.dto.ObjectChangedEventMessage(
                        com.sixbean.testpike.core.linkage.api.dto.ObjectChangedEventType
                                .PERF_SCENARIO.name(),
                        scenarioId, projectId, null,
                        (int) (System.currentTimeMillis() / 1000),
                        null, System.currentTimeMillis()));
    }

    @Override
    public Map<String, Object> view(long projectId, long id) {
        Map<String, Object> row = jdbc.queryForList("SELECT " + SELECT_COLUMNS + " FROM tp_perf_scenario "
                        + "WHERE id = :id AND project_id = :p",
                new MapSqlParameterSource("id", id).addValue("p", projectId))
                .stream().findFirst()
                .orElseThrow(() -> new ServiceException("压测场景不存在或不属于该项目——请返回「压测工作台」列表刷新后重新选择场景",
                        BizErrorCode.NOT_FOUND));
        // Editor echo: the CUSTOM body lives in object storage, so the detail door
        // reads it back next to its key (the list doors keep key-only — no blob
        // traffic through a paged table).
        if ("CUSTOM".equals(row.get("source")) && row.get("custom_script_key") != null) {
            byte[] body = artifactStorage.readBytesQuietly((String) row.get("custom_script_key"));
            row.put("script_content", body == null ? null : new String(body, java.nio.charset.StandardCharsets.UTF_8));
        }
        return row;
    }

    @Override
    public List<Map<String, Object>> list(long projectId) {
        return jdbc.queryForList("SELECT " + SELECT_COLUMNS + " FROM tp_perf_scenario "
                + "WHERE project_id = :p ORDER BY id DESC", new MapSqlParameterSource("p", projectId));
    }

    /**
 * Column keys the scenario ledger may sort by -> SQL expressions (all
 * physical columns of tp_perf_scenario; the jsonb payload columns
 * steps_json/pressure_config/sla_json stay deliberately off-list — sorting
 * a DSL/statistics blob has no honest user-facing meaning). Values are
 * never user input: off-list or injection-shaped keys are refused by the
 * shared fail-closed gate before any SQL is built , same
 * posture as the recording cassette ledger).
 */
    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "name", "name",
            "pressure_model", "pressure_model",
            "login_strategy", "login_strategy",
            "driver_limit", "driver_limit",
            "created_at", "created_at",
            "updated_at", "updated_at");

    @Override
    public PerfScenarioPageView listPaged(long projectId, int page, int pageSize,
                                          String sortBy, String sortDir) {
        return listPaged(0L, 0L, projectId, page, pageSize, sortBy, sortDir);
    }

    @Override
    public PerfScenarioPageView listPaged(long operatorId, long programId, long projectId,
                                          int page, int pageSize, String sortBy, String sortDir) {
        // Anchor rule + lens resolution + predicate all ride the perf shared source.
        lensScopeSupport.requireAnchor(projectId, programId, "压测场景");
        Set<Long> lensProjectIds = lensScopeSupport.resolveLens(operatorId, programId);
        PageWindow window = PageWindow.of(page, pageSize);
        MapSqlParameterSource params = new MapSqlParameterSource();
        String scope = com.sixbean.testpike.core.perf.domain.component.PerfLensScopeSupport
                .projectScope(lensProjectIds, projectId, params);
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tp_perf_scenario WHERE " + scope, params, Long.class);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT " + SELECT_COLUMNS
                        + " FROM tp_perf_scenario WHERE " + scope
                        + orderClause(sortBy, sortDir) + " LIMIT :limit OFFSET :offset",
                params.addValue("limit", window.size()).addValue("offset", window.offset()));
        return new PerfScenarioPageView(rows, total == null ? 0L : total,
                window.page(), window.size());
    }

    /**
     * ORDER BY builder: no sort key keeps the historical newest-first id DESC
     * (the wizard home page and promotion entries read positions off that
     * order); a whitelisted key orders by that column with the id DESC
     * tie-breaker so paging windows stay deterministic. The key itself passes
     * the shared fail-closed gate — only map VALUES reach the SQL string.
     */
    private static String orderClause(String sortBy, String sortDir) {
        String column = SortColumnPolicy.require(sortBy, SORT_COLUMNS, "压测场景列表");
        if (column == null) {
            return " ORDER BY id DESC";
        }
        return " ORDER BY " + column
                + ("asc".equalsIgnoreCase(sortDir) ? " ASC" : " DESC")
                + ", id DESC";
    }

    // ---------- A-13: delete with history kept (D2 decoupling) ----------

    @Override
    public Map<String, Object> deleteImpact(long projectId, long id) {
        requireScenarioRow(projectId, id);
        return impactLedger(id);
    }

    @Override
    @Transactional
    @Audit(objectType = "perf_scenario", action = "delete", objectId = "#id")
    public void delete(long projectId, long id) {
        Map<String, Object> scenario = requireScenarioRow(projectId, id);
        Long running = jdbc.queryForObject(
                "SELECT count(*) FROM tp_perf_run WHERE scenario_id = :id AND status = 1",
                new MapSqlParameterSource("id", id), Long.class);
        if (running != null && running > 0) {
            throw new ServiceException("该场景仍有 " + running + " 个运行中的压测，请先停止或等待结束后再删除——可在「压测工作台」点该行「运行历史」，对进行中的运行执行停止后重试",
                    BizErrorCode.RESOURCE_CONFLICT);
        }
        // D2: finished runs outlive the recipe. The link becomes NULL (column is
        // nullable since V291) while the row, its pinned snapshot and every metric
        // frame under it stay queryable — report/trend faces read the snapshot, so
        // nothing downstream re-derives truth from the deleted row.
        jdbc.update("UPDATE tp_perf_run SET scenario_id = NULL WHERE scenario_id = :id",
                new MapSqlParameterSource("id", id));
        // Approval tickets keep their audit trail but drop the scenario leg; the
        // D8 auto-launch link already fail-closes on a missing scenario, and a
        // pending ticket re-approved later simply loses the link instead of the
        // whole ticket (approvals stay queryable on env + applicant).
        jdbc.update("UPDATE tp_perf_approval SET scenario_id = NULL, updated_at = now() "
                        + "WHERE scenario_id = :id",
                new MapSqlParameterSource("id", id));
        int deleted = jdbc.update("DELETE FROM tp_perf_scenario WHERE id = :id AND project_id = :p",
                new MapSqlParameterSource("id", id).addValue("p", projectId));
        if (deleted == 0) {
            throw new ServiceException("压测场景不存在或不属于该项目——请返回「压测工作台」列表刷新后重新选择场景", BizErrorCode.NOT_FOUND);
        }
        // A mounted CUSTOM script is the scenario's only off-row payload; it
        // retires with the row (same posture as the CUSTOM→DSL switch).
        artifactStorage.deleteQuietly((String) scenario.get("custom_script_key"));
    }

    /** Counts the delete surface in one read so the card and the door see the same ledger. */
    private Map<String, Object> impactLedger(long scenarioId) {
        MapSqlParameterSource params = new MapSqlParameterSource("id", scenarioId);
        Long running = jdbc.queryForObject(
                "SELECT count(*) FROM tp_perf_run WHERE scenario_id = :id AND status = 1",
                params, Long.class);
        Long finished = jdbc.queryForObject(
                "SELECT count(*) FROM tp_perf_run WHERE scenario_id = :id AND status <> 1",
                params, Long.class);
        Long linked = jdbc.queryForObject(
                "SELECT count(*) FROM tp_perf_approval WHERE scenario_id = :id",
                params, Long.class);
        Long pending = jdbc.queryForObject(
                "SELECT count(*) FROM tp_perf_approval WHERE scenario_id = :id AND status = 2",
                params, Long.class);
        String source = jdbc.queryForObject(
                "SELECT source FROM tp_perf_scenario WHERE id = :id", params, String.class);
        Map<String, Object> impact = new HashMap<>();
        impact.put("runningRuns", running == null ? 0L : running);
        impact.put("historicalRuns", finished == null ? 0L : finished);
        impact.put("linkedApprovals", linked == null ? 0L : linked);
        impact.put("pendingApprovals", pending == null ? 0L : pending);
        impact.put("source", source);
        return impact;
    }

    private Map<String, Object> requireScenarioRow(long projectId, long id) {
        return jdbc.queryForList("SELECT id, source, custom_script_key FROM tp_perf_scenario "
                        + "WHERE id = :id AND project_id = :p",
                new MapSqlParameterSource("id", id).addValue("p", projectId))
                .stream().findFirst()
                .orElseThrow(() -> new ServiceException("压测场景不存在或不属于该项目——请返回「压测工作台」列表刷新后重新选择场景",
                        BizErrorCode.NOT_FOUND));
    }


    
    private String validate(long projectId, PerfScenarioInput input) {
        if (input.name() == null || input.name().isBlank()) {
            throw new ServiceException("请填写压测场景名称——请在「新建压测场景」向导第 1 步「场景名称」输入框填写后保存", BizErrorCode.VALIDATION_ERROR);
        }
        String model = input.pressureModel();
        if (model == null || !PRESSURE_MODELS.contains(model)) {
            // Ordered enumeration (declaration order = wizard order) built from the name
            // map so the option list can never drift behind the enum (B-2). Map.of has no
            // iteration order — the list is spelled out to keep the message stable.
            String options = java.util.stream.Stream.of(
                            "CONSTANT_RPS", "RAMP", "SPIKE", "FIXED_VU", "LADDER",
                            "BUSINESS_CURVE", "SOAK")
                    .map(k -> k + "（" + MODEL_NAMES.get(k) + "）")
                    .reduce((a, b) -> a + "/" + b).orElse("");
            throw new ServiceException("未知压力形态「" + model + "」，请在「压力形态」下拉重新选择，可选=" + options,
                    BizErrorCode.VALIDATION_ERROR);
        }
        Map<String, Integer> config = input.pressureConfig() == null ? Map.of() : input.pressureConfig();
        List<String> missing = new ArrayList<>();
        for (String key : REQUIRED_KEYS.get(model)) {
            Integer v = config.get(key);
            if (v == null) {
                missing.add(key);
            } else if (v <= 0) {
                throw new ServiceException("压力参数 " + key + " 必须是大于 0 的整数——请在「压力形态」步骤该参数框改为正整数，示例：填 100",
                        BizErrorCode.VALIDATION_ERROR);
            }
        }
        if (!missing.isEmpty()) {
            throw new ServiceException(MODEL_NAMES.get(model) + "形态缺少参数："
                    + String.join("、", missing) + "——请在「压力形态」步骤补齐上述参数后保存", BizErrorCode.VALIDATION_ERROR);
        }
        if ("RAMP".equals(model) && config.get("peakRps") < config.get("startRps")) {
            throw new ServiceException("阶梯爬坡的峰值 RPS 不能低于起始 RPS，请核对压力曲线——请在「压力形态」步骤把「峰值速率（RPS）」调到不低于「起始速率（RPS）」",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if ("SPIKE".equals(model) && config.get("peakRps") <= config.get("baseRps")) {
            throw new ServiceException("峰值脉冲的峰值 RPS 必须高于基线 RPS，否则与恒定压力无异——请在「压力形态」步骤把「峰值速率（RPS）」调到高于「基线速率（RPS）」后保存",
                    BizErrorCode.VALIDATION_ERROR);
        }
        validateModelSemantics(model, config);
        // Save-time value-domain guard: the mode string is stored once here and the launch
        // safety gate is the enforcement point (A-1/A-5); unknown spellings fail fast with names.
        String declaredMode = input.isolationMode();
        if (declaredMode != null && !declaredMode.isBlank()
                && !PerfAccessGate.ISOLATION_MODES.contains(declaredMode.trim())) {
            throw new ServiceException("未知隔离模式「" + declaredMode + "」，请在「隔离模式」下拉重新选择，可选值="
                    + PerfAccessGate.ISOLATION_MODES, BizErrorCode.VALIDATION_ERROR);
        }
        String strategy = normalizeStrategy(input.loginStrategy());
        // B-9 source door (before the steps gate): a scenario declares exactly one
        // thing-to-run. DSL = step orchestration (the historical default); CUSTOM =
        // a user-authored script bound to one engine, scanned at save time and
        // mounted into object storage by mountScript. The two forms are mutually
        // exclusive — a row carrying both would leave the launch ambiguous.
        boolean custom = "CUSTOM".equals(normalizeSource(input.source()));
        if (custom) {
            validateCustomScript(input);
        } else {
            if (input.scriptBody() != null && !input.scriptBody().isBlank()) {
                throw new ServiceException("DSL 步骤编排场景不能同时携带自定义脚本，"
                        + "请在「新建压测场景」向导的运行内容改为自定义脚本场景，或删除「压测步骤表」中的步骤", BizErrorCode.VALIDATION_ERROR);
            }
            // W-d engine binding: an engine on a DSL scenario is an optional pin
            // (explicit binding wins over the default routing at dispatch);
            // blank = the server-side default routing decides.
            if (input.engine() != null && !input.engine().isBlank()) {
                requireKnownEngine(input.engine());
            }
            validateSteps(input.steps());
        }
        validateSla(input.slaJson());
        if (input.driverLimit() != null && input.driverLimit() <= 0) {
            throw new ServiceException("驱动上限披露值必须大于 0（留空=不披露）——请在「驱动上限（台）」填写正整数或清空该输入框",
                    BizErrorCode.VALIDATION_ERROR);
        }
        Long envOwn = jdbc.queryForObject("SELECT count(*) FROM tp_project_env WHERE id = :e AND project_id = :p",
                new MapSqlParameterSource("e", input.envId()).addValue("p", projectId), Long.class);
        if (input.envId() == null || envOwn == null || envOwn == 0) {
            throw new ServiceException("目标环境不存在或不属于该项目——请在「压测目标」步骤的「目标环境」下拉重新选择本项目环境", BizErrorCode.NOT_FOUND);
        }
        validateDatasetBinding(projectId, input);
        validateSchedule(projectId, input);
        return strategy;
    }

    /**
 * Scheduled-firing guard (B-8 : the cron rides the shared
 * {@link ScheduleCronTimes#validate} gate (one expression contract for the
 * save side and the scanner side, red line 6 — no second parser). An enabled
 * switch without a pinned cron is refused naming the missing piece: the
 * scanner only fires rows that carry both, so such a control would look on
 * and never act. The pinned override environment must belong to the project
 * (same not-found wording as the default-env door — no cross-project leak).
 */
    private void validateSchedule(long projectId, PerfScenarioInput input) {
        ScheduleCronTimes.validate(input.scheduleCron());
        boolean enabled = Boolean.TRUE.equals(input.scheduleEnabled());
        if (enabled && (input.scheduleCron() == null || input.scheduleCron().isBlank())) {
            throw new ServiceException("启用定时压测前请先填写 cron 表达式（6 段格式：秒 分 时 日 月 周，"
                    + "例如 0 30 2 * * * 表示每日 02:30）", BizErrorCode.VALIDATION_ERROR);
        }
        if (input.scheduleEnvId() != null) {
            Long own = jdbc.queryForObject(
                    "SELECT count(*) FROM tp_project_env WHERE id = :e AND project_id = :p",
                    new MapSqlParameterSource("e", input.scheduleEnvId()).addValue("p", projectId),
                    Long.class);
            if (own == null || own == 0) {
                throw new ServiceException("定时执行环境不存在或不属于该项目——请在「定时执行」区的「定时执行环境」下拉重新选择本项目环境", BizErrorCode.NOT_FOUND);
            }
        }
    }

    /**
 * data-pool binding guard: a row-allocation strategy is only meaningful
 * together with a dataset; the dataset must belong to the same project (a
 * foreign id answers the same not-found wording as the env door — no leak);
 * unknown allocation spellings are refused naming the allowed set
 * (PerfRowAllocModes is the single source, no second enumeration here).
 */
    private void validateDatasetBinding(long projectId, PerfScenarioInput input) {
        String mode = input.rowAllocMode();
        boolean modeDeclared = mode != null && !mode.isBlank();
        if (input.datasetId() == null) {
            if (modeDeclared) {
                throw new ServiceException("未绑定压测数据集时不能声明行分配策略，请先选择数据集——可在「数据池（CSV 数据集）」下拉选择数据集，或清空「行分配策略」",
                        BizErrorCode.VALIDATION_ERROR);
            }
            return;
        }
        Long own = jdbc.queryForObject(
                "SELECT count(*) FROM tp_perf_dataset WHERE id = :d AND project_id = :p",
                new MapSqlParameterSource("d", input.datasetId()).addValue("p", projectId),
                Long.class);
        if (own == null || own == 0) {
            throw new ServiceException("压测数据集不存在或不属于该项目——请在「数据池（CSV 数据集）」重新选择本项目数据集，或先到「压测工作台」上传数据集", BizErrorCode.NOT_FOUND);
        }
        if (modeDeclared && !PerfRowAllocModes.VALUES.contains(mode.trim())) {
            throw new ServiceException("未知行分配策略「" + mode.trim() + "」，请在「行分配策略」下拉重新选择，可选值="
                    + PerfRowAllocModes.VALUES, BizErrorCode.VALIDATION_ERROR);
        }
    }

    /**
 * B-2 semantic gates for the four new pressure models — the
 * same fail-fast, plain-words posture as the RAMP/SPIKE checks above:
 * <ul>
 * <li>FIXED_VU: a closed user pool must declare when it stops — either a loop
 * count or a wall-clock window; a pool with neither would run until the global
 * timeout swallows it (A-14 posture: no implicit horizon).</li>
 * <li>LADDER: stepRps × steps must raise the rate above the start (an empty or
 * descending search is not a search).</li>
 * <li>BUSINESS_CURVE: hour weights are percentages of peakRps (curve0..curve23,
 * 0..100); all-zero/absent weights would compile a silent no-load run.</li>
 * </ul>
 */
    private void validateModelSemantics(String model, Map<String, Integer> config) {
        switch (model) {
            case "FIXED_VU" -> {
                Integer loops = config.get("loops");
                Integer duration = config.get("durationSeconds");
                if ((loops == null || loops <= 0) && (duration == null || duration <= 0)) {
                    throw new ServiceException("固定用户池压测必须声明结束条件，否则压力会一直运行不会自行结束——"
                            + "请在「压力形态」步骤填写「循环次数」或「持续秒数」至少其一",
                            BizErrorCode.VALIDATION_ERROR);
                }
            }
            case "LADDER" -> {
                int top = config.get("startRps") + config.get("stepRps") * config.get("steps");
                if (top <= config.get("startRps")) {
                    throw new ServiceException("阶梯寻量的每级增量 stepRps 与级数 steps 必须为正，"
                            + "否则压力永远停在起始速率——请在「压力形态」步骤把每级增量与级数改为大于 0 的整数", BizErrorCode.VALIDATION_ERROR);
                }
            }
            case "BUSINESS_CURVE" -> {
                boolean anyPositive = false;
                for (Map.Entry<String, Integer> e : config.entrySet()) {
                    String key = e.getKey();
                    if (!key.startsWith("curve")) {
                        continue;
                    }
                    int weight = e.getValue() == null ? 0 : e.getValue();
                    if (weight < 0 || weight > 100) {
                        throw new ServiceException("业务时段曲线权重 " + key + "=" + weight
                                + " 超出 0~100（按峰值 RPS 的百分比填写，如高峰时段填 100）——请在「业务时段权重（0~23 点，按峰值 RPS 百分比）」区把该时段改为 0~100 的数值",
                                BizErrorCode.VALIDATION_ERROR);
                    }
                    anyPositive |= weight > 0;
                }
                if (!anyPositive) {
                    throw new ServiceException("业务时段曲线至少需要一个时段（curve0~curve23）的权重大于 0，"
                            + "全零曲线等于不打压力——请在「业务时段权重（0~23 点，按峰值 RPS 百分比）」区把至少一个时段改为大于 0", BizErrorCode.VALIDATION_ERROR);
                }
            }
            case "SOAK" -> {
                // durationHours rides the same hot-updatable ceiling the launch gate
                // already enforces (perf.max-duration-hours); no second ceiling here.
            }
            default -> {
            }
        }
    }

    
    private String normalizeStrategy(String raw) {
        if (raw == null || raw.isBlank()) {
            return "PER_VUSER";
        }
        if ("PER_VUSER".equals(raw) || "SHARED".equals(raw) || PER_SESSION_N.matcher(raw).matches()) {
            return raw;
        }
        throw new ServiceException("未知登录策略「" + raw + "」，请在「登录策略」下拉重新选择，可选=PER_VUSER（默认，每虚拟用户独立登录）"
                + "/SHARED（全程共享一次登录）/PER_SESSION_N（每 N 个虚拟用户登录一次）",
                BizErrorCode.VALIDATION_ERROR);
    }

    /**
 * A-11 + A-3: perf steps narrow to http.request alone and their field
 * domain (url non-blank, expect keys, saveAs identifier, poll shape) is
 * judged by {@link PerfStepActionGate#stepViolation}, the same component the
 * compile stage uses — one verdict, one message corpus, no second spec
 * (red line 6; the shared 04 contract validator backs the field domain).
 * <p>B-3 : the mix keys add a whole-array structural check
 * ({@link PerfStepActionGate#mixStructureViolation}) — completeness of a
 * declaration is only visible across steps. Engine capability rides the
 * compile stage, not here.
 */
    private void validateSteps(List<Map<String, Object>> steps) {
        if (steps == null || steps.isEmpty()) {
            throw new ServiceException("压测场景至少包含一个请求步骤——请在「压测步骤表」点「添加压测步骤」补齐后保存", BizErrorCode.VALIDATION_ERROR);
        }
        // scale contract: the batch-import path can produce hundreds of steps at
        // once; the ceiling (K8 perf.scenario.max-steps, default 500) keeps a scenario
        // inside the range the wizard windowing and engine compile are designed for.
        // Rejection names both the current count and the ceiling (plain-words iron law).
        int maxSteps = configReader.scenarioMaxSteps();
        if (steps.size() > maxSteps) {
            throw new ServiceException("压测场景步骤数超限：" + steps.size() + " 步，上限 " + maxSteps
                    + " 步（系统参数 perf.scenario.max-steps 可调，请拆分场景或调整该参数）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        JsonNode tree = objectMapper.valueToTree(steps);
        String mixViolation = PerfStepActionGate.mixStructureViolation(tree);
        if (mixViolation != null) {
            throw new ServiceException(mixViolation, BizErrorCode.VALIDATION_ERROR);
        }
        for (Map<String, Object> step : steps) {
            String violation = stepActionGate.stepViolation(
                    step == null ? objectMapper.createObjectNode() : objectMapper.valueToTree(step));
            if (violation != null) {
                throw new ServiceException(violation, BizErrorCode.VALIDATION_ERROR);
            }
        }
    }

    
    private void validateSla(Map<String, Double> sla) {
        if (sla == null || sla.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Double> entry : sla.entrySet()) {
            String key = entry.getKey();
            if (!SLA_KEYS.contains(key)) {
                throw new ServiceException("未知的 SLA 阈值键「" + key + "」，请在「阈值门禁」步骤核对键名后重试，可选="
                        + String.join("/", SLA_KEYS) + "（p95Ms 毫秒 / errorRate 与 breakerErrorRate "
                        + "错误率 0-1 / minRps 最低吞吐）", BizErrorCode.VALIDATION_ERROR);
            }
            Double v = entry.getValue();
            if (v == null || !Double.isFinite(v) || v < 0) {
                throw new ServiceException("SLA 阈值 " + key + " 必须是大于等于 0 的数字（留空该键=不设该项判定）——请在「阈值门禁」步骤改为不小于 0 的数字或清空该键",
                        BizErrorCode.VALIDATION_ERROR);
            }
            if (SLA_RATE_KEYS.contains(key) && v > 1) {
                throw new ServiceException("SLA 阈值 " + key + "=" + v + " 超出比率范围：按 0-1 小数填写——请在「阈值门禁」步骤把该键改为 0-1 小数"
                        + "（如 5% 填 0.05）", BizErrorCode.VALIDATION_ERROR);
            }
        }
    }

    /**
     * B-9 source normalisation: null/blank = DSL (the historical default every
     * existing row carries via the column DEFAULT), unknown spellings are refused
     * naming the allowed set — same plain-words posture as the isolation-mode door.
     */
    private static String normalizeSource(String raw) {
        if (raw == null || raw.isBlank()) {
            return "DSL";
        }
        String trimmed = raw.trim().toUpperCase(java.util.Locale.ROOT);
        if ("DSL".equals(trimmed) || "CUSTOM".equals(trimmed)) {
            return trimmed;
        }
        throw new ServiceException("未知场景来源「" + raw + "」，请按 source 参数可选值重试，示例：CUSTOM（自定义脚本）/DSL（步骤编排）",
                BizErrorCode.VALIDATION_ERROR);
    }

    /**
     * Engine-binding normalisation (W-d, red line 6 single source): trim +
     * upper-case; null/blank = "no explicit binding" so the default routing key
     * decides at dispatch. Save validation, param binding and script mount all
     * read through this one pair of helpers — a second trim/upper or a second
     * value-domain check elsewhere is forbidden.
     */
    private String normalizeEngine(String raw) {
        return raw == null || raw.isBlank() ? null : raw.trim().toUpperCase(java.util.Locale.ROOT);
    }

    /** Value-domain door shared by the DSL and CUSTOM save branches; returns the normalised engine. */
    private String requireKnownEngine(String raw) {
        String engine = normalizeEngine(raw);
        if (engine == null || !PerfRunDispatchSupport.ENGINE_VALUES.contains(engine)) {
            throw new ServiceException("未知压测引擎「" + raw + "」，请在「压测引擎」下拉重新选择，可选="
                    + PerfRunDispatchSupport.ENGINE_VALUES, BizErrorCode.VALIDATION_ERROR);
        }
        return engine;
    }

    /**
     * B-9 custom-script save gate: the script body must be present, the engine
     * binding must be one the platform can actually run (PerfRunDispatchSupport
     * .ENGINE_VALUES single source, no second enumeration), and the body must
     * pass {@link PerfCustomScriptScanGate} before anything is stored — an
     * escaping script is refused naming every offending line (plain-words door),
     * never saved and never reaches an engine.
     */
    private void validateCustomScript(PerfScenarioInput input) {
        if (input.scriptBody() == null || input.scriptBody().isBlank()) {
            throw new ServiceException("自定义脚本场景必须提供脚本内容，请在「新建压测场景」向导的自定义脚本编辑区粘贴正文后保存",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (normalizeEngine(input.engine()) == null) {
            throw new ServiceException("自定义脚本场景必须绑定压测引擎，请在「压测引擎」下拉选择后保存，可选="
                    + PerfRunDispatchSupport.ENGINE_VALUES, BizErrorCode.VALIDATION_ERROR);
        }
        String engine = requireKnownEngine(input.engine());
        if (input.steps() != null && !input.steps().isEmpty()) {
            throw new ServiceException("自定义脚本场景的运行内容由脚本本身决定，不能同时编排压测步骤；"
                    + "需要步骤编排请改回 DSL 场景并在「压测步骤表」编排", BizErrorCode.VALIDATION_ERROR);
        }
        List<String> violations = PerfCustomScriptScanGate.scan(engine, input.scriptBody());
        if (!violations.isEmpty()) {
            throw new ServiceException("自定义脚本未通过静态扫描：" + String.join("；", violations) + "——请修正上述点名行后重新保存，正确写法以扫描逐条点名为准",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    /**
     * B-9 storage mount (runs after validate, so the scan verdict is settled):
     * CUSTOM bodies go to object storage under the perf-script scene using the
     * same project-routed chain as the CSV dataset pool; re-saving a CUSTOM
     * scenario replaces the mount (old key retired), a CUSTOM→DSL switch
     * retires it outright. DSL scenarios never touch storage (key stays null).
     */
    private String mountScript(long projectId, PerfScenarioInput input, String previousKey) {
        if (!"CUSTOM".equals(normalizeSource(input.source()))) {
            artifactStorage.deleteQuietly(previousKey);
            return null;
        }
        String engine = normalizeEngine(input.engine());
        String fileName = switch (engine) {
            case "K6" -> "scenario.js";
            case "JMETER" -> "scenario.jmx";
            default -> "Simulation.scala";
        };
        String key = artifactStorage.buildProjectObjectKey(projectId, "perf-script", fileName);
        artifactStorage.putBytesForProject(projectId, key,
                input.scriptBody().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "text/plain;charset=UTF-8");
        artifactStorage.deleteQuietly(previousKey);
        return key;
    }

    private MapSqlParameterSource params(long projectId, PerfScenarioInput input, String strategy,
                                         String scriptKey) {
        return new MapSqlParameterSource("p", projectId).addValue("e", input.envId())
                .addValue("n", input.name().trim()).addValue("m", input.pressureModel())
                .addValue("c", writeJson(input.pressureConfig()))
                .addValue("s", strategy)
                .addValue("d", input.driverLimit())
                .addValue("t", writeJson(input.steps() == null ? List.of() : input.steps()))
                .addValue("sla", input.slaJson() == null || input.slaJson().isEmpty()
                        ? null : writeJson(input.slaJson()))
                .addValue("iso", input.isolationMode() == null || input.isolationMode().isBlank()
                        ? null : input.isolationMode().trim())
                .addValue("ds", input.datasetId())
                .addValue("ram", input.rowAllocMode() == null || input.rowAllocMode().isBlank()
                        ? null : input.rowAllocMode().trim())
                .addValue("sc", input.scheduleCron() == null || input.scheduleCron().isBlank()
                        ? null : input.scheduleCron().trim())
                .addValue("se", Boolean.TRUE.equals(input.scheduleEnabled()) ? 1 : 0)
                .addValue("senv", input.scheduleEnvId())
                .addValue("src", normalizeSource(input.source()))
                .addValue("eng", normalizeEngine(input.engine()))
                .addValue("csk", scriptKey);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception impossible) {
            throw new ServiceException("场景 DSL 序列化失败——请重新保存场景；若仍失败请在「压测工作台」删除该场景后重建", BizErrorCode.VALIDATION_ERROR);
        }
    }
}
