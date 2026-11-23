package com.sixbean.testpike.core.appauto.domain.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.appauto.api.dto.AppautoEngineInfo;
import com.sixbean.testpike.core.appauto.api.dto.AppautoMatrixCombo;
import com.sixbean.testpike.core.appauto.api.dto.AppautoMatrixDispatchResult;
import com.sixbean.testpike.core.appauto.api.dto.AppautoMatrixFanoutInput;
import com.sixbean.testpike.core.appauto.api.dto.AppautoMatrixModelRow;
import com.sixbean.testpike.core.appauto.api.dto.AppautoMatrixPrecheckView;
import com.sixbean.testpike.core.appauto.api.dto.AppautoMatrixStepFailRow;
import com.sixbean.testpike.core.appauto.domain.component.AppautoConfigReader;
import com.sixbean.testpike.core.appauto.domain.component.AppautoMatrixDeviceResolver;
import com.sixbean.testpike.core.appauto.domain.service.AppautoEngineService;
import com.sixbean.testpike.core.appauto.domain.service.AppautoMatrixService;
import com.sixbean.testpike.core.batch.domain.component.BatchParamResolver;
import com.sixbean.testpike.core.batch.domain.component.BatchParamSnapshotStore;
import com.sixbean.testpike.core.batch.domain.component.BatchPrecheckResult;
import com.sixbean.testpike.core.batch.domain.component.BatchPrecheckRow;
import com.sixbean.testpike.core.batch.domain.component.BatchPrecheckSupport;
import com.sixbean.testpike.core.batch.domain.component.BatchQueueEstimateSupport;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import com.sixbean.testpike.core.cost.api.dto.CostWaterView;
import com.sixbean.testpike.core.cost.domain.service.CostService;
import com.sixbean.testpike.core.executor.domain.component.AppAutoDispatchSupport;
import com.sixbean.testpike.core.executor.domain.service.ExecTaskService;
import com.sixbean.testpike.core.project.api.dto.ProjectEnvInfo;
import com.sixbean.testpike.core.project.domain.service.ProjectEnvService;
import com.sixbean.testpike.core.script.api.dto.ScriptInfo;
import com.sixbean.testpike.core.script.domain.service.ScriptService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;


@Service
public class AppautoMatrixServiceImpl implements AppautoMatrixService {

    private static final Logger log = LoggerFactory.getLogger(AppautoMatrixServiceImpl.class);

    
    private static final String DEFAULT_BATCH_NAME = "机型矩阵扇出批";
    
    private static final int STATUS_QUEUED = 2;
    
    private static final int TYPE_FORMAL = 1;

    
    private static final int STATUS_PASS = 3;

    private final JdbcTemplate jdbcTemplate;
    private final ProjectEnvService projectEnvService;
    private final ScriptService scriptService;
    private final AppautoEngineService engineService;
    private final AppautoConfigReader appautoConfigReader;
    private final AppautoMatrixDeviceResolver deviceResolver;
    private final AppAutoDispatchSupport appAutoDispatchSupport;
    private final ExecTaskService execTaskService;
    private final BatchQueueEstimateSupport queueEstimateSupport;
    private final BatchParamResolver batchParamResolver;
    private final BatchParamSnapshotStore batchParamSnapshotStore;
    private final ConfigCacheHolder configCacheHolder;
    private final CostService costService;
    private final ObjectMapper objectMapper;

    public AppautoMatrixServiceImpl(JdbcTemplate jdbcTemplate,
                                    ProjectEnvService projectEnvService,
                                    ScriptService scriptService,
                                    AppautoEngineService engineService,
                                    AppautoConfigReader appautoConfigReader,
                                    AppautoMatrixDeviceResolver deviceResolver,
                                    AppAutoDispatchSupport appAutoDispatchSupport,
                                    ExecTaskService execTaskService,
                                    BatchQueueEstimateSupport queueEstimateSupport,
                                    BatchParamResolver batchParamResolver,
                                    BatchParamSnapshotStore batchParamSnapshotStore,
                                    ConfigCacheHolder configCacheHolder,
                                    CostService costService,
                                    ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.projectEnvService = projectEnvService;
        this.scriptService = scriptService;
        this.engineService = engineService;
        this.appautoConfigReader = appautoConfigReader;
        this.deviceResolver = deviceResolver;
        this.appAutoDispatchSupport = appAutoDispatchSupport;
        this.execTaskService = execTaskService;
        this.queueEstimateSupport = queueEstimateSupport;
        this.batchParamResolver = batchParamResolver;
        this.batchParamSnapshotStore = batchParamSnapshotStore;
        this.configCacheHolder = configCacheHolder;
        this.costService = costService;
        this.objectMapper = objectMapper;
    }

    @Override
    public AppautoMatrixPrecheckView precheck(AppautoMatrixFanoutInput input) {
        AppautoMatrixPlan plan = validateAndPlan(input);
        return buildPrecheckView(plan);
    }

    
    @Override
    public List<AppautoMatrixModelRow> modelReport(long projectId, long caseId) {
        int windowDays = appautoConfigReader.supportWindowDays();
        List<Map<String, Object>> taskRows = jdbcTemplate.queryForList("""
                SELECT action_json, status, steps_timeline::text AS timeline
                FROM tp_exec_task
                WHERE task_type = 6 AND project_id = ? AND case_id = ?
                  AND status IN (3, 4, 5)
                  AND COALESCE(finished_at, started_at, created_at)
                      >= now() - make_interval(days => ?::int)
                """, projectId, caseId, windowDays);
        Map<Long, String[]> profileById = deviceProfiles(projectId);
        Map<String, long[]> countsByModel = new LinkedHashMap<>();
        Map<String, Map<String, AppautoMatrixStepFailRow>> failsByModel = new LinkedHashMap<>();
        for (Map<String, Object> row : taskRows) {
            Long deviceId = appAutoDispatchSupport.leaseDeviceId((String) row.get("action_json"));
            String[] profile = deviceId == null ? null : profileById.get(deviceId);
            if (profile == null) {
                continue;
            }
            String key = profile[0] + "|" + profile[1];
            long[] c = countsByModel.computeIfAbsent(key, k -> new long[2]);
            c[0]++;
            if (((Number) row.get("status")).intValue() == STATUS_PASS) {
                c[1]++;
            } else {
                aggregateStepFails(key, (String) row.get("timeline"), failsByModel);
            }
        }
        List<AppautoMatrixModelRow> out = new ArrayList<>();
        for (Map.Entry<String, long[]> entry : countsByModel.entrySet()) {
            String[] profile = entry.getKey().split("\\|", -1);
            long[] c = entry.getValue();
            List<AppautoMatrixStepFailRow> steps = failsByModel.getOrDefault(
                    entry.getKey(), Map.of()).values().stream()
                    .sorted((a, b) -> Integer.compare(b.failCount(), a.failCount()))
                    .toList();
            double passRate = Math.round((double) c[1] / c[0] * 10000.0) / 10000.0;
            out.add(new AppautoMatrixModelRow(profile[0], profile[1], (int) c[0], (int) c[1],
                    (int) (c[0] - c[1]), passRate, steps, modelMessage(profile, c, steps)));
        }
        out.sort(java.util.Comparator.comparingDouble(AppautoMatrixModelRow::passRate)
                .thenComparing(AppautoMatrixModelRow::model));
        return out;
    }

    
    private void aggregateStepFails(String modelKey, String timelineJson,
                                    Map<String, Map<String, AppautoMatrixStepFailRow>> sink) {
        if (timelineJson == null || timelineJson.isBlank()) {
            return;
        }
        List<Map<String, Object>> rows;
        try {
            rows = objectMapper.readValue(timelineJson, new TypeReference<>() {
            });
        } catch (Exception e) {
            log.warn("矩阵报告时间线解析失败（按无步数据处理）: {}", e.getMessage());
            return;
        }
        Map<String, AppautoMatrixStepFailRow> byStep =
                sink.computeIfAbsent(modelKey, k -> new LinkedHashMap<>());
        for (Map<String, Object> step : rows) {
            if (!"fail".equals(str(step.get("result")))) {
                continue;
            }
            int stepNo = step.get("stepNo") instanceof Number n ? n.intValue() : 0;
            String action = str(step.get("action"));
            String detail = str(step.get("detail"));
            String slot = stepNo + "|" + action;
            AppautoMatrixStepFailRow prev = byStep.get(slot);
            byStep.put(slot, prev == null
                    ? new AppautoMatrixStepFailRow(stepNo, action, 1, detail)
                    : new AppautoMatrixStepFailRow(stepNo, action, prev.failCount() + 1,
                            prev.sampleDetail() == null ? detail : prev.sampleDetail()));
        }
    }

    
    private String modelMessage(String[] profile, long[] counts,
                                List<AppautoMatrixStepFailRow> steps) {
        String head = profile[0] + "（OS " + profile[1] + "）";
        if (steps.isEmpty()) {
            return head + "全部通过（" + counts[1] + "/" + counts[0] + " 样本）";
        }
        AppautoMatrixStepFailRow worst = steps.get(0);
        return head + "失败集中在第 " + worst.stepNo() + " 步 " + worst.action()
                + "（" + (worst.sampleDetail() == null ? "引擎未给归因详情" : worst.sampleDetail())
                + "，共 " + worst.failCount() + " 次）";
    }

    
    private Map<Long, String[]> deviceProfiles(long projectId) {
        Map<Long, String[]> out = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList(
                "SELECT id, model, os_version FROM tp_appauto_device WHERE project_id = ?",
                projectId)) {
            Object model = row.get("model");
            if (model == null || String.valueOf(model).isBlank()) {
                continue;
            }
            out.put(((Number) row.get("id")).longValue(), new String[]{
                    String.valueOf(model).trim(),
                    row.get("os_version") == null ? "未知" : String.valueOf(row.get("os_version"))});
        }
        return out;
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    @Override
    @Transactional
    public AppautoMatrixDispatchResult fanout(long operatorId, AppautoMatrixFanoutInput input) {
        AppautoMatrixPlan plan = validateAndPlan(input);
        ProjectEnvInfo env = requireEnv(input);

        String batchNo = "B-" + UUID.randomUUID().toString().substring(0, 8);
        String name = input.name() == null || input.name().isBlank()
                ? DEFAULT_BATCH_NAME : input.name().trim();
        Long batchId = jdbcTemplate.queryForObject("""
                INSERT INTO tp_exec_batch (project_id, env_id, batch_no, name, status,
                                           type, template_id, schedule_type, created_by, notify_feishu)
                VALUES (?, ?, ?, ?, ?, ?, NULL, 0, ?, 0) RETURNING id
                """, Long.class, input.projectId(), env.id(), batchNo, name,
                STATUS_QUEUED, TYPE_FORMAL, operatorId);
        jdbcTemplate.update("""
                INSERT INTO tp_batch_case (batch_id, case_id, case_version, sort_order, result)
                VALUES (?, ?, ?, 0, 4)
                """, batchId, plan.caseRow().id(), plan.caseRow().version());

        List<Long> taskIds = new ArrayList<>();
        String queueNote = null;
        Map<String, Integer> kindOrdinal = new java.util.HashMap<>();
        for (AppautoMatrixCombo combo : plan.combos()) {
            Long profileId = combo.envProfileId() != null
                    ? combo.envProfileId() : plan.caseRow().appautoEnvProfileId();
            Map<String, Object> envelope = appAutoDispatchSupport.buildEnvelope(
                    input.projectId(), combo.engineCode(), profileId, plan.appSteps());
            int ordinal = kindOrdinal.merge(String.valueOf(combo.deviceKindCode()), 1, Integer::sum) - 1;
            Map<String, Object> leaseRef = deviceResolver.resolve(
                    input.projectId(), combo.deviceKindCode(), ordinal, input.reservationId(),
                    combo.platformCode(), input.deviceGroupLabel());
            if (leaseRef != null) {
                envelope.put("leaseRef", leaseRef);
            }
            long taskId = execTaskService.createAppAutoRun(input.projectId(),
                    plan.caseRow().id(), batchId, toJson(envelope));
            if (queueNote == null) {
                queueNote = appAutoDispatchSupport.explainNoCapableExecutor(taskId);
            }
            taskIds.add(taskId);
        }

        queueEstimateSupport.lockQueuePosition();
        long ahead = queueEstimateSupport.pendingAheadCount(batchId);
        long estMinutes = queueEstimateSupport.estimateMinutes(ahead,
                queueEstimateSupport.idleCapacity());
        jdbcTemplate.update("""
                UPDATE tp_exec_batch SET queue_pos = ?, started_at = now(),
                       case_version_snapshot = ?::jsonb, env_version_snapshot = ?,
                       version = version + 1, updated_at = now()
                WHERE id = ?
                """, (int) ahead + 1,
                toJson(List.of(Map.of("caseId", plan.caseRow().id(),
                        "version", plan.caseRow().version()))),
                env.versionSnapshot(), batchId);
        batchParamSnapshotStore.save(batchId, 1, batchParamResolver.toEnvelope(
                batchParamResolver.assembleSections(input.projectId(), env.baseUrl())));

        String message = "机型矩阵已扇出 " + taskIds.size() + " 个执行任务（每任务约 "
                + queueEstimateSupport.minutesPerTask() + " 分钟，预计排队约 " + estMinutes
                + " 分钟），批次进入待执行队列"
                + (queueNote == null ? "" : "；" + queueNote)
                + (buildPrecheckView(plan).budgetHighWater()
                        ? "；提示：本月预算水位偏高，建议减少组合数降级扇出" : "");
        return new AppautoMatrixDispatchResult(batchId, batchNo, taskIds.size(), taskIds, message);
    }


    
    private AppautoMatrixPlan validateAndPlan(AppautoMatrixFanoutInput input) {
        if (input == null || input.combos() == null || input.combos().isEmpty()) {
            throw new ServiceException("矩阵扇出至少需要一个「引擎×平台×机型」组合——"
                    + "请在扇出向导勾选目标组合后重试", BizErrorCode.VALIDATION_ERROR);
        }
        AppautoMatrixCaseRow caseRow = loadCase(input);
        if (!"app".equals(caseRow.automationTarget()) && !"mp".equals(caseRow.automationTarget())) {
            throw new ServiceException("用例「" + caseRow.title() + "」未挂 App/小程序自动化目标，"
                    + "无法参与机型矩阵扇出——请先在「用例详情」选择自动化目标",
                    BizErrorCode.VALIDATION_ERROR);
        }
        BatchPrecheckResult pre = BatchPrecheckSupport.classify(
                List.of(new BatchPrecheckRow(caseRow.id(), "矩阵扇出", caseRow.title(),
                        caseRow.status(), caseRow.flaky())),
                configCacheHolder.get(BatchPrecheckSupport.CFG_UNEVALUATED_MODE));
        if (pre.approvedCaseIds().isEmpty()) {
            List<String> reasons = new ArrayList<>();
            pre.skips().forEach(s -> reasons.add(s.reason()));
            if (pre.blockedByUnevaluated()) {
                throw new ServiceException(BatchPrecheckSupport.blockMessage(pre.unevaluatedSkips()),
                        BizErrorCode.VALIDATION_ERROR);
            }
            throw new ServiceException("用例「" + caseRow.title() + "」当前不可派发（"
                    + String.join("；", reasons) + "）——矩阵扇出与批次启动同预检口径，请先在「用例详情」消除上述原因再扇出",
                    BizErrorCode.VALIDATION_ERROR);
        }
        ScriptInfo script = scriptService.getByCaseId(caseRow.id());
        List<Map<String, Object>> appSteps = script == null ? List.of()
                : appAutoDispatchSupport.extractAppSteps(script.actionJson());
        if (appSteps.isEmpty()) {
            throw new ServiceException("用例「" + caseRow.title() + "」未编排 app.* 动作步骤，"
                    + "矩阵扇出无可下发内容——请先在「用例详情」编排 App 步骤（正确写法示例 app.launch、app.tap）",
                    BizErrorCode.VALIDATION_ERROR);
        }

        Set<String> seen = new LinkedHashSet<>();
        List<AppautoMatrixCombo> combos = new ArrayList<>();
        for (AppautoMatrixCombo raw : input.combos()) {
            String engine = normCombo(raw.engineCode() == null || raw.engineCode().isBlank()
                    ? (caseRow.appautoEngine() == null || caseRow.appautoEngine().isBlank()
                            ? appautoConfigReader.defaultEngine() : caseRow.appautoEngine())
                    : raw.engineCode());
            AppautoMatrixCombo combo = new AppautoMatrixCombo(engine,
                    normCombo(raw.platformCode()), normCombo(raw.deviceKindCode()),
                    raw.envProfileId());
            if (!seen.add(combo.dedupKey())) {
                continue;
            }
            assertComboWithinEngineFace(combo);
            combos.add(combo);
        }
        int max = appautoConfigReader.matrixFanoutMax();
        if (combos.size() > max) {
            throw new ServiceException("机型矩阵扇出组合数 " + combos.size()
                    + " 超过单用例上限 " + max + "（配置键 appauto.matrix-fanout-max 可热更调高；"
                    + "上限防无意识烧穿设备池）——请到「机型矩阵报告」减少组合或分批扇出",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return new AppautoMatrixPlan(caseRow, appSteps, combos);
    }

    
    private AppautoMatrixCaseRow loadCase(AppautoMatrixFanoutInput input) {
        List<AppautoMatrixCaseRow> rows = jdbcTemplate.query("""
                SELECT id, title, version, automation_target, appauto_engine,
                       appauto_env_profile_id, status, flaky
                FROM tp_test_case
                WHERE id = ? AND project_id = ? AND deleted = 0
                """, (rs, n) -> new AppautoMatrixCaseRow(rs.getLong("id"), rs.getString("title"),
                rs.getInt("version"), rs.getString("automation_target"),
                rs.getString("appauto_engine"),
                rs.getObject("appauto_env_profile_id") == null
                        ? null : rs.getLong("appauto_env_profile_id"),
                rs.getInt("status"), rs.getInt("flaky")),
                input.caseId(), input.projectId());
        if (rows.isEmpty()) {
            throw new ServiceException("用例不存在或不属于当前项目，已拒绝跨项目扇出"
                    + "——请切换到用例所属项目，再从该用例的「用例详情」发起扇出",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return rows.get(0);
    }

    
    private void assertComboWithinEngineFace(AppautoMatrixCombo combo) {
        AppautoEngineInfo engine;
        try {
            engine = engineService.getByCode(combo.engineCode());
        } catch (ServiceException e) {
            throw new ServiceException("引擎「" + combo.engineCode() + "」不在描述符目录——"
                    + "矩阵组合只能选择引擎目录中的引擎——请到「引擎中心」查看可选引擎后重新选择",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (engine.status() != 1) {
            throw new ServiceException("引擎「" + engine.name() + "（" + engine.code()
                    + "）」已停用/下架，不能作为矩阵扇出组合——请到引擎中心启用或换引擎",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (combo.platformCode() == null || combo.platformCode().isBlank()
                || !containsIgnoreCase(engine.platformCodes(), combo.platformCode())) {
            throw new ServiceException("组合「" + combo.engineCode() + " × " + combo.platformCode()
                    + "」越出引擎声明的平台面（" + combo.engineCode() + " 支持："
                    + String.join("/", engine.platformCodes()) + "）——该组合不会被任何执行器受理，"
                    + "请到「引擎中心」核对该引擎支持的平台后改选",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (combo.deviceKindCode() == null || combo.deviceKindCode().isBlank()
                || !containsIgnoreCase(engine.deviceKindCodes(), combo.deviceKindCode())) {
            throw new ServiceException("组合「" + combo.engineCode() + " × " + combo.platformCode()
                    + " × " + combo.deviceKindCode() + "」越出引擎声明的设备类面（"
                    + combo.engineCode() + " 支持："
                    + String.join("/", engine.deviceKindCodes()) + "）——该组合不会被任何执行器受理，"
                    + "请到「引擎中心」核对该引擎支持的设备类后改选",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    private AppautoMatrixPrecheckView buildPrecheckView(AppautoMatrixPlan plan) {
        long ahead = queueEstimateSupport.pendingAheadCount(null);
        long idle = queueEstimateSupport.idleCapacity();
        long capacity = Math.max(idle, 1);
        long estQueueMinutes = queueEstimateSupport.estimateMinutes(ahead, capacity);
        int minutesPerTask = queueEstimateSupport.minutesPerTask();
        String startAt = OffsetDateTime.now(ZoneId.systemDefault())
                .plusMinutes(estQueueMinutes)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        boolean highWater = budgetHighWater(plan.caseRow().id());
        String message = "矩阵扇出预检：预计 " + plan.combos().size() + " 台 × " + minutesPerTask
                + " 分钟 = 约 " + (plan.combos().size() * (long) minutesPerTask) + " 分钟机时；"
                + "当前队列前有待派发 " + ahead + " 任务，预计排队至 " + startAt
                + (highWater ? "；提示：本月预算水位偏高，建议减少组合数降级扇出" : "");
        return new AppautoMatrixPrecheckView(plan.combos().size(), minutesPerTask, ahead,
                capacity, estQueueMinutes, startAt, appautoConfigReader.matrixFanoutMax(),
                highWater, message);
    }

    
    private boolean budgetHighWater(long caseId) {
        try {
            Long projectId = jdbcTemplate.queryForObject(
                    "SELECT project_id FROM tp_test_case WHERE id = ?", Long.class, caseId);
            CostWaterView water = costService.waterLevel(projectId);
            return water.budgetCents() > 0
                    && water.costCents() * 100 >= water.budgetCents() * water.alertPct();
        } catch (RuntimeException e) {
            log.warn("矩阵预检预算水位读取失败（按不偏高处理）: {}", e.getMessage());
            return false;
        }
    }

    
    private ProjectEnvInfo requireEnv(AppautoMatrixFanoutInput input) {
        if (input.envId() == null) {
            throw new ServiceException("矩阵扇出需要选择执行环境后再派发（与批次启动同口径）"
                    + "——请在扇出面板的「执行环境」下拉选择档案（档案在「项目环境」页维护）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return projectEnvService.get(input.projectId(), input.envId());
    }

    private static String normCombo(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private static boolean containsIgnoreCase(List<String> face, String value) {
        return face != null && face.stream().anyMatch(v -> v != null
                && v.trim().equalsIgnoreCase(value));
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new ServiceException("矩阵信封序列化失败——请重试；持续出现请到「机型矩阵报告」反馈该次扇出",
                    BizErrorCode.INTERNAL_ERROR);
        }
    }
}
