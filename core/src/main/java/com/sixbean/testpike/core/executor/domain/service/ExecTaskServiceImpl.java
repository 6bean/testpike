package com.sixbean.testpike.core.executor.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.util.ExploreEnvelopeContract;
import com.sixbean.testpike.common.util.UiActionContract;
import com.sixbean.testpike.core.audit.domain.component.Audit;
import com.sixbean.testpike.core.batch.domain.component.BatchFinalizeSupport;
import com.sixbean.testpike.core.batch.domain.component.EnvExecutionLock;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.common.domain.component.PageQueryPolicy;
import com.sixbean.testpike.core.common.domain.component.SortColumnPolicy;
import com.sixbean.testpike.core.common.domain.component.VirtualScrollEndpoint;
import com.sixbean.testpike.core.config.domain.component.AiScriptLimitsReader;
import com.sixbean.testpike.core.config.domain.component.ExecPolicyReader;
import com.sixbean.testpike.core.executor.api.dto.BatchCancelResult;
import com.sixbean.testpike.core.executor.api.dto.BatchCaseRerunResult;
import com.sixbean.testpike.core.executor.api.dto.CaseExecHistoryView;
import com.sixbean.testpike.core.events.api.dto.CaseEventMessage;
import com.sixbean.testpike.core.events.api.dto.CaseEventType;
import com.sixbean.testpike.core.events.domain.service.CaseEventPublisher;
import com.sixbean.testpike.core.executor.api.dto.ClaimTaskView;
import com.sixbean.testpike.core.executor.api.dto.ExecResultInput;
import com.sixbean.testpike.core.executor.api.dto.ExecStepEvidenceView;
import com.sixbean.testpike.core.executor.api.dto.SessionArtifactsInput;
import com.sixbean.testpike.core.executor.api.dto.ExecStepSnapshotView;
import com.sixbean.testpike.core.executor.api.dto.ExecTaskCreateInput;
import com.sixbean.testpike.core.executor.api.dto.ExecTaskInfo;
import com.sixbean.testpike.core.executor.api.dto.ExecutorTaskPageView;
import com.sixbean.testpike.core.executor.api.dto.ExecutorInfo;
import com.sixbean.testpike.core.executor.api.dto.ParallelWaterLevelView;
import com.sixbean.testpike.core.executor.api.dto.ProjectRunningView;
import com.sixbean.testpike.core.executor.api.dto.RunningTaskView;
import com.sixbean.testpike.core.executor.api.dto.ScreenshotView;
import com.sixbean.testpike.core.executor.domain.component.ArtifactStorage;
import com.sixbean.testpike.core.executor.domain.component.BrowserKernelSupport;
import com.sixbean.testpike.core.executor.domain.component.DbExecAuditSupport;
import com.sixbean.testpike.core.executor.domain.component.ExecStepSnapshotSupport;
import com.sixbean.testpike.core.executor.domain.component.ParallelLimitSupport;
import com.sixbean.testpike.core.executor.domain.component.PoolDispatchSupport;
import com.sixbean.testpike.core.executor.domain.component.ProdBlacklistGuard;
import com.sixbean.testpike.core.executor.domain.component.RerunSource;
import com.sixbean.testpike.core.executor.domain.component.TimeoutConfigSupport;
import com.sixbean.testpike.core.project.api.dto.ProjectEnvInfo;
import com.sixbean.testpike.core.project.domain.service.ProjectEnvService;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.exception.ValidationException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;


@Service
public class ExecTaskServiceImpl implements ExecTaskService {

    private static final Logger log = LoggerFactory.getLogger(ExecTaskServiceImpl.class);

    private static final int STATUS_PENDING = 1;
    private static final int STATUS_RUNNING = 2;
    private static final int STATUS_SUCCESS = 3;
    private static final int STATUS_FAILED = 4;
    private static final int STATUS_TIMEOUT = 5;
    private static final int STATUS_SKIPPED = 6;
 
    private static final int STATUS_CANCELLED = 7;

 
    private static final int AI_CLASS_MIN = 1;
    private static final int AI_CLASS_MAX = 3;
    private static final int AI_NOTE_MAX_LEN = 500;
 
    private static final int STATUS_QUEUED_BATCH = 2;
    
    private static final int EXECUTOR_OFFLINE = 3;

    /** Value domain of tp_exec_task.last_reclaim_source (V270): which reclaim path last
     * moved a dangling running task back to the pending queue. */
    private static final int RECLAIM_SOURCE_SWEEP = 1;
    private static final int RECLAIM_SOURCE_MANUAL = 2;

    private static final String COLUMNS = """
            id, project_id, case_id, executor_id, status, claim_token, schema_version,
            action_json, env_base_url, env_version_snapshot, error_type, error_detail, steps_timeline::text,
            screenshots_json::text, video_key, duration_ms, log, result_ext::text,
            ai_failure_class, ai_failure_note, task_type,
            to_char(started_at, 'YYYY-MM-DD HH24:MI:SS') AS started_at,
            to_char(finished_at, 'YYYY-MM-DD HH24:MI:SS') AS finished_at,
            to_char(created_at, 'YYYY-MM-DD HH24:MI:SS') AS created_at
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ExecutorService executorService;
    private final ObjectMapper objectMapper;
    private final ArtifactStorage artifactStorage;
    private final ProjectEnvService projectEnvService;
    private final ProdBlacklistGuard prodBlacklistGuard;
    private final BatchFinalizeSupport batchFinalizeSupport;
    private final TimeoutConfigSupport timeoutConfigSupport;
    private final EnvExecutionLock envExecutionLock;
    private final AiScriptLimitsReader aiScriptLimitsReader;
    private final ExecPolicyReader execPolicyReader;
    private final DbExecAuditSupport dbExecAuditSupport;
    private final ExecStepSnapshotSupport execStepSnapshotSupport;
    
    private final com.sixbean.testpike.core.batch.domain.component.BatchParamSnapshotReader
            batchParamSnapshotReader;
    private final CaseEventPublisher caseEventPublisher;
    private final ParallelLimitSupport parallelLimitSupport;
    private final PoolDispatchSupport poolDispatchSupport;
    private final com.sixbean.testpike.core.visual.domain.component.VisualBaselineClaimAssembler visualBaselineClaimAssembler;
    private final com.sixbean.testpike.core.visual.domain.component.VisualBaselineCaptureSupport visualBaselineCaptureSupport;
    private final com.sixbean.testpike.core.apitest.domain.component.ApiRunResultFinalizer apiRunResultFinalizer;
    /** AV face3: unexpected 401/403 on a consumed declared login state marks it invalid (single write path). */
    private final com.sixbean.testpike.core.session.domain.component.SessionAuthRejectionWriteback sessionAuthRejectionWriteback;
    private final com.sixbean.testpike.core.webelement.domain.component.WebElementAnchorStatWriteback webElementAnchorStatWriteback;
    /** Self-heal evidence rows land in the heal ledger verbatim (single write path). */
    private final com.sixbean.testpike.core.webelement.domain.component.StepHealEvidenceWriteback stepHealEvidenceWriteback;
    /** grade-3 owner: settles write-back platform-reproof ledger entries with the terminal result. */
    private final com.sixbean.testpike.core.script.domain.component.ScriptWritebackPlatformReproof scriptWritebackPlatformReproof;
    
    private final com.sixbean.testpike.core.batch.domain.component.BatchContextStore batchContextStore;
    
    private final com.sixbean.testpike.core.executor.domain.component.SessionDeclarationSupport
            sessionDeclarationSupport;
    
    private final com.sixbean.testpike.core.lease.domain.service.ResourceLeaseService resourceLeaseService;
 
    private final com.sixbean.testpike.core.appauto.domain.component.AppautoLeaseRunRecorder
            appautoLeaseRunRecorder;

 
    private final com.sixbean.testpike.core.appauto.domain.service.AppautoPortabilityService
            appautoPortabilityService;

 /** Single source for the claim-side mock key: resolves a browser task's case
  *  binding through the shared resolver (same gate as api-test envelope assembly). */
    private final com.sixbean.testpike.core.mock.domain.component.MockServingBindingResolver
            mockServingBindingResolver;
    /** WE batch S1: {{ELEM:}} normalisation + claim envelope anchor snapshot, one
     *  batched ledger read per dispatch (executor must not call back into core). */
    private final com.sixbean.testpike.core.webelement.domain.component.WebElementDispatchSnapshot
            webElementDispatchSnapshot;
    
    private final com.sixbean.testpike.core.executor.domain.component.RoleDeclarationSupport
            roleDeclarationSupport;
    /** face 2: sessionState declaration read point + ledger consumption single
 * source (claim envelope sessionState trailing key assembly, red line 6 reuse). */
    private final com.sixbean.testpike.core.executor.domain.component.SessionStateDeclarationSupport
            sessionStateDeclarationSupport;
    private final com.sixbean.testpike.core.session.domain.component.SessionStateSupport
            sessionStateSupport;
    
    private final com.sixbean.testpike.core.apitest.domain.service.StepAliasService stepAliasService;

    private final com.sixbean.testpike.core.perf.domain.component.PerfRunDispatchSupport
            perfRunDispatchSupport;

    private final com.sixbean.testpike.core.perf.domain.component.PerfRunResultFinalizer
            perfRunResultFinalizer;

 /** GA-4 capability routing consumer: perf candidates are gated on the
 *  engine face of the claiming executor (claim skip + order-side early reject). */
    private final com.sixbean.testpike.core.perf.domain.component.PerfEngineRoutingSupport
            perfEngineRoutingSupport;

 
    private final com.sixbean.testpike.core.executor.domain.component.AppAutoDispatchSupport
            appAutoDispatchSupport;

 
    private final com.sixbean.testpike.core.executor.domain.component.AppAutoPreflightGate
            appAutoPreflightGate;

    public ExecTaskServiceImpl(JdbcTemplate jdbcTemplate, ExecutorService executorService,
                               ObjectMapper objectMapper, ArtifactStorage artifactStorage,
                               ProjectEnvService projectEnvService, ProdBlacklistGuard prodBlacklistGuard,
                               BatchFinalizeSupport batchFinalizeSupport,
                               TimeoutConfigSupport timeoutConfigSupport,
                               EnvExecutionLock envExecutionLock,
                               AiScriptLimitsReader aiScriptLimitsReader,
                               ExecPolicyReader execPolicyReader,
                               DbExecAuditSupport dbExecAuditSupport,
                               ExecStepSnapshotSupport execStepSnapshotSupport,
                               com.sixbean.testpike.core.batch.domain.component.BatchParamSnapshotReader
                                       batchParamSnapshotReader,
                               CaseEventPublisher caseEventPublisher,
                               ParallelLimitSupport parallelLimitSupport,
                               PoolDispatchSupport poolDispatchSupport,
                               com.sixbean.testpike.core.visual.domain.component.VisualBaselineClaimAssembler visualBaselineClaimAssembler,
                               com.sixbean.testpike.core.visual.domain.component.VisualBaselineCaptureSupport visualBaselineCaptureSupport,
                               com.sixbean.testpike.core.apitest.domain.component.ApiRunResultFinalizer apiRunResultFinalizer,
                               com.sixbean.testpike.core.session.domain.component.SessionAuthRejectionWriteback
                                       sessionAuthRejectionWriteback,
                               com.sixbean.testpike.core.webelement.domain.component.WebElementAnchorStatWriteback
                                       webElementAnchorStatWriteback,
                               com.sixbean.testpike.core.webelement.domain.component.StepHealEvidenceWriteback
                                       stepHealEvidenceWriteback,
                               com.sixbean.testpike.core.script.domain.component.ScriptWritebackPlatformReproof
                                       scriptWritebackPlatformReproof,
                               com.sixbean.testpike.core.batch.domain.component.BatchContextStore batchContextStore,
                               com.sixbean.testpike.core.executor.domain.component.SessionDeclarationSupport
                                       sessionDeclarationSupport,
                               com.sixbean.testpike.core.lease.domain.service.ResourceLeaseService
                                       resourceLeaseService,
                               com.sixbean.testpike.core.executor.domain.component.RoleDeclarationSupport
                                       roleDeclarationSupport,
                               com.sixbean.testpike.core.executor.domain.component.SessionStateDeclarationSupport
                                       sessionStateDeclarationSupport,
                               com.sixbean.testpike.core.session.domain.component.SessionStateSupport
                                       sessionStateSupport,
                               com.sixbean.testpike.core.apitest.domain.service.StepAliasService
                                       stepAliasService,
                               com.sixbean.testpike.core.perf.domain.component.PerfRunDispatchSupport
                                       perfRunDispatchSupport,
                               com.sixbean.testpike.core.perf.domain.component.PerfRunResultFinalizer
                                       perfRunResultFinalizer,
                               com.sixbean.testpike.core.perf.domain.component.PerfEngineRoutingSupport
                                       perfEngineRoutingSupport,
                               com.sixbean.testpike.core.executor.domain.component.AppAutoDispatchSupport
                                       appAutoDispatchSupport,
                               com.sixbean.testpike.core.executor.domain.component.AppAutoPreflightGate
                                       appAutoPreflightGate,
                               com.sixbean.testpike.core.appauto.domain.component.AppautoLeaseRunRecorder
                                       appautoLeaseRunRecorder,
                               com.sixbean.testpike.core.appauto.domain.service.AppautoPortabilityService
                                       appautoPortabilityService,
                               com.sixbean.testpike.core.mock.domain.component.MockServingBindingResolver
                                       mockServingBindingResolver,
                               com.sixbean.testpike.core.webelement.domain.component.WebElementDispatchSnapshot
                                       webElementDispatchSnapshot) {
        this.jdbcTemplate = jdbcTemplate;
        this.executorService = executorService;
        this.objectMapper = objectMapper;
        this.artifactStorage = artifactStorage;
        this.projectEnvService = projectEnvService;
        this.prodBlacklistGuard = prodBlacklistGuard;
        this.batchFinalizeSupport = batchFinalizeSupport;
        this.timeoutConfigSupport = timeoutConfigSupport;
        this.envExecutionLock = envExecutionLock;
        this.aiScriptLimitsReader = aiScriptLimitsReader;
        this.execPolicyReader = execPolicyReader;
        this.dbExecAuditSupport = dbExecAuditSupport;
        this.execStepSnapshotSupport = execStepSnapshotSupport;
        this.batchParamSnapshotReader = batchParamSnapshotReader;
        this.caseEventPublisher = caseEventPublisher;
        this.parallelLimitSupport = parallelLimitSupport;
        this.poolDispatchSupport = poolDispatchSupport;
        this.visualBaselineClaimAssembler = visualBaselineClaimAssembler;
        this.visualBaselineCaptureSupport = visualBaselineCaptureSupport;
        this.apiRunResultFinalizer = apiRunResultFinalizer;
        this.sessionAuthRejectionWriteback = sessionAuthRejectionWriteback;
        this.webElementAnchorStatWriteback = webElementAnchorStatWriteback;
        this.stepHealEvidenceWriteback = stepHealEvidenceWriteback;
        this.scriptWritebackPlatformReproof = scriptWritebackPlatformReproof;
        this.batchContextStore = batchContextStore;
        this.sessionDeclarationSupport = sessionDeclarationSupport;
        this.resourceLeaseService = resourceLeaseService;
        this.roleDeclarationSupport = roleDeclarationSupport;
        this.sessionStateDeclarationSupport = sessionStateDeclarationSupport;
        this.sessionStateSupport = sessionStateSupport;
        this.stepAliasService = stepAliasService;
        this.perfRunDispatchSupport = perfRunDispatchSupport;
        this.perfRunResultFinalizer = perfRunResultFinalizer;
        this.perfEngineRoutingSupport = perfEngineRoutingSupport;
        this.appAutoDispatchSupport = appAutoDispatchSupport;
        this.appAutoPreflightGate = appAutoPreflightGate;
        this.appautoLeaseRunRecorder = appautoLeaseRunRecorder;
        this.appautoPortabilityService = appautoPortabilityService;
        this.mockServingBindingResolver = mockServingBindingResolver;
        this.webElementDispatchSnapshot = webElementDispatchSnapshot;
    }

    @Override
    @Transactional
    public long create(ExecTaskCreateInput input) {
        if (input.projectId() <= 0 || input.caseId() <= 0) {
            throw new ServiceException("项目与用例不能为空——请在顶栏选择所属项目后，从「用例详情」发起试运行", BizErrorCode.VALIDATION_ERROR);
        }
        if (input.actionJson() == null || input.actionJson().isBlank()) {
            throw new ServiceException("动作 JSON 不能为空——请在「用例详情」点「生成脚本」产出脚本后再发起试运行", BizErrorCode.VALIDATION_ERROR);
        }
        if (input.envId() == null) {
            throw new ServiceException("请选择执行环境（开发/测试/预发/生产）——请在「选择执行环境」下拉中选择环境后重试", BizErrorCode.VALIDATION_ERROR);
        }
        ProjectEnvInfo env = projectEnvService.get(input.projectId(), input.envId());
        if (env.envType() == 4) {
            throw new ServiceException("生产环境默认禁止执行（生产防护）——请在「选择执行环境」下拉改为开发/测试/预发环境后重试",
                    BizErrorCode.FORBIDDEN);
        }
        String effectiveUrl = input.effectiveBaseUrl(env.baseUrl());
        if (prodBlacklistGuard.matches(effectiveUrl)) {
            throw new ServiceException("目标地址 " + effectiveUrl
                    + " 命中生产域名黑名单，禁止执行——请在「项目管理」环境配置改用非生产地址后重试；"
                    + "确需执行生产时请联系管理员核实域名黑名单",
                    BizErrorCode.FORBIDDEN);
        }
        String browser = BrowserKernelSupport.resolve(input.browser(), env);
        jdbcTemplate.update("""
                UPDATE tp_test_case SET status = 6, version = version + 1, updated_at = now()
                WHERE id = ? AND status = 3 AND deleted = 0
                """, input.caseId());
        return jdbcTemplate.queryForObject("""
                INSERT INTO tp_exec_task (project_id, case_id, status, schema_version,
                                          action_json, env_base_url, env_version_snapshot, batch_id, browser)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, input.projectId(), input.caseId(), STATUS_PENDING,
                input.schemaVersion() == null ? "v2" : input.schemaVersion(),
                input.actionJson(), effectiveUrl, env.versionSnapshot(), input.batchId(), browser);
    }

    

    @Override
    @Transactional
    public long createToolTestRun(long projectId, String actionJson) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO tp_exec_task (project_id, case_id, status, schema_version,
                                          action_json, task_type)
                VALUES (?, ?, ?, 'tool-v1', ?, 2) RETURNING id
                """, Long.class, projectId, 0L, STATUS_PENDING, actionJson);
    }

    @Override
    @Transactional
    public long createExploreAct(long projectId, String actionJson) {
        if (projectId <= 0) {
            throw new ServiceException("探索动作的项目不能为空——请先在顶栏「所属项目」下拉选择项目，再发起探索动作", BizErrorCode.VALIDATION_ERROR);
        }
        if (actionJson == null || actionJson.isBlank()) {
            throw new ServiceException("动作 JSON 不能为空——请在「用例详情」点「生成脚本」产出动作 JSON 后再发起", BizErrorCode.VALIDATION_ERROR);
        }
        validateExploreMobileShape(actionJson);
        return jdbcTemplate.queryForObject("""
                INSERT INTO tp_exec_task (project_id, case_id, status, schema_version,
                                          action_json, task_type)
                VALUES (?, ?, ?, 'explore-v1', ?, 3) RETURNING id
                """, Long.class, projectId, 0L, STATUS_PENDING, actionJson);
    }

    
    private void validateExploreMobileShape(String actionJson) {
        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(actionJson);
        } catch (Exception dirty) {
            return;
        }
        if (envelope == null || !envelope.isObject()
                || !ExploreEnvelopeContract.TARGET_MOBILE.equals(envelope.path("target").asText(null))) {
            return;
        }
        String forbidden = UiActionContract.exploreForbiddenViolation(envelope.path("action").asText(""));
        if (forbidden != null) {
            throw new ServiceException(forbidden, BizErrorCode.VALIDATION_ERROR);
        }
        JsonNode engine = envelope.path("engine");
        if (!engine.isObject() || engine.isEmpty()) {
            throw new ServiceException("移动探索信封 engine 缺失（引擎描述符必随信封自带，"
                    + "执行器无会话语境禁猜引擎）——请补齐信封，格式：engine=引擎描述符对象",
                    BizErrorCode.VALIDATION_ERROR);
        }
        String serial = envelope.path("leaseRef").path("serial").asText("");
        if (serial.isBlank()) {
            throw new ServiceException("移动探索信封 leaseRef.serial 缺失（设备会话由 serial 定位，"
                    + "探索动作必须自带设备序列号）——请补齐信封，格式：leaseRef.serial=已预约设备序列号"
                    + "（可在「设备预约日历」确认设备已预约）",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    @Override
    @Transactional
    public long createApiTestRun(long projectId, String actionJson) {
        if (projectId <= 0) {
            throw new ServiceException("API 测试运行的项目不能为空——请在顶栏选择所属项目后，从「用例详情」发起 API 测试运行", BizErrorCode.VALIDATION_ERROR);
        }
        if (actionJson == null || actionJson.isBlank()) {
            throw new ServiceException("动作 JSON 不能为空——请在「用例详情」点「生成脚本」产出动作 JSON 后再发起", BizErrorCode.VALIDATION_ERROR);
        }
        return jdbcTemplate.queryForObject("""
                INSERT INTO tp_exec_task (project_id, case_id, status, schema_version,
                                          action_json, task_type)
                VALUES (?, ?, ?, 'apitest-v1', ?, 4) RETURNING id
                """, Long.class, projectId, 0L, STATUS_PENDING, actionJson);
    }

    @Override
    @Transactional
    public long createPerfRunTask(long projectId, String actionJson) {
        if (projectId <= 0) {
            throw new ServiceException("压测运行任务的项目不能为空——请先在顶栏「所属项目」下拉选择项目，再发起压测运行", BizErrorCode.VALIDATION_ERROR);
        }
        perfRunDispatchSupport.validateEnvelope(projectId, actionJson);
        // GA-4 order-side early rejection (mirrors the app-auto health gate):
        // online executors exist but none reports the engine this run needs =
        // human-worded reject before the INSERT (zero rows written). An empty
        // fleet keeps queue semantics (the run auto-starts when a node comes).
        String routingReject = perfEngineRoutingSupport
                .explainNoCapablePerfExecutorForEnvelope(actionJson);
        if (routingReject != null) {
            throw new ServiceException(routingReject, BizErrorCode.VALIDATION_ERROR);
        }
        return jdbcTemplate.queryForObject("""
                INSERT INTO tp_exec_task (project_id, case_id, status, schema_version,
                                          action_json, task_type)
                VALUES (?, ?, ?, 'perf-v1', ?, 5) RETURNING id
                """, Long.class, projectId, 0L, STATUS_PENDING, actionJson);
    }

    @Override
    @Transactional
    public long createAppAutoRun(long projectId, String actionJson) {
        return createAppAutoRun(projectId, 0L, null, actionJson);
    }

    @Override
    @Transactional
    public long createAppAutoRun(long projectId, long caseId, Long batchId, String actionJson) {
        if (projectId <= 0) {
            throw new ServiceException("APP 自动化任务的项目不能为空——请先在顶栏「所属项目」下拉选择项目，再发起 APP 自动化任务", BizErrorCode.VALIDATION_ERROR);
        }
        appAutoDispatchSupport.validateEnvelope(projectId, actionJson);
        String preflight = appAutoPreflightGate.explainFailure(projectId, actionJson);
        if (preflight != null) {
            throw new ServiceException(preflight, BizErrorCode.VALIDATION_ERROR);
        }
        Long effectiveBatchId = batchId == null || batchId == 0L ? null : batchId;
        return jdbcTemplate.queryForObject("""
                INSERT INTO tp_exec_task (project_id, case_id, status, schema_version,
                                          action_json, task_type, batch_id)
                VALUES (?, ?, ?, 'appauto-v1', ?, 6, ?) RETURNING id
                """, Long.class, projectId, caseId, STATUS_PENDING, actionJson, effectiveBatchId);
    }

    @Override
    @Transactional
    public void timeoutToolRun(long taskId) {
        Long executorId = jdbcTemplate.query(
                "SELECT executor_id FROM tp_exec_task WHERE id = ? AND status IN (?, ?)",
                (org.springframework.jdbc.core.ResultSetExtractor<Long>) rs ->
                        rs.next() ? (Long) rs.getObject(1) : null,
                taskId, STATUS_PENDING, STATUS_RUNNING);
        int marked = jdbcTemplate.update("""
                UPDATE tp_exec_task SET status = ?, error_type = ?, error_detail = ?,
                       finished_at = now(), updated_at = now()
                WHERE id = ? AND status IN (?, ?)
                """, STATUS_TIMEOUT, 3, "工具试跑预算耗尽，已按超时终止（IT-TOOL-03）",
                taskId, STATUS_PENDING, STATUS_RUNNING);
        if (marked > 0 && executorId != null) {
            executorService.releaseCapacity(executorId);
        }
    }

    @Override
    @Transactional
    public ClaimTaskView claim(long executorId) {
        ExecutorInfo executor = executorService.get(executorId);
        if (executor == null) {
            throw new ServiceException("执行器未注册——请启动执行器程序完成注册后再领取任务（注册状态可在「执行与试运行」的执行器总览查看）", BizErrorCode.NOT_FOUND);
        }
        if (!executorService.isOnline(executorId)) {
            throw new ServiceException("执行器不在线，无法领取任务——请检查执行机进程是否存活（心跳超过 15 秒未上报即判离线，在线状态可在「执行与试运行」的执行器总览查看）", BizErrorCode.STATE_MACHINE_REJECT);
        }
        if (executor.runningCnt() >= executor.capacity()) {
            return null;
        }
        if (executor.drainRequested()) {
            return null;
        }
        long timeoutMs = timeoutConfigSupport.caseTimeoutMinutes() * 60_000L;
        Map<String, Integer> scriptLimits = aiScriptLimitsReader.toClaimPayload();
        Map<String, Object> execPolicy = execPolicyReader.toClaimPayload();
        int globalMax = parallelLimitSupport.globalMax();
        int perProjectMax = parallelLimitSupport.perProjectMax();
        Map<Long, Long> runningByProject = runningCountByProject();
        long globalRunning = runningByProject.values().stream().mapToLong(Long::longValue).sum();
        if (globalMax > 0 && globalRunning >= globalMax) {
            return null;
        }
        Map<Long, String> stickyRefByTask = new LinkedHashMap<>();
        Map<Long, Boolean> appSkipByTask = new LinkedHashMap<>();
 // GA-4 load-engine routing: a perf candidate whose envelope engine is not on
 // this executor's capability face skips the queue position (same candidate-
 // level continue posture as app-auto; parse happens once here at assembly).
        Map<Long, Boolean> perfSkipByTask = new LinkedHashMap<>();
        Map<Long, String> appSerialByTask = new LinkedHashMap<>();
        Map<Long, String> leaseKindByTask = new LinkedHashMap<>();
 // A-14 budget self-healing: envelope budgetMs is parsed once at candidate
 // assembly (same shape as capability routing / sticky affinity — no per-candidate
 // re-parse); a missing key or dirty envelope yields no entry, so claim dispatch
 // and the timeout sweep fall back to the global case timeout.
        Map<Long, Long> budgetByTask = new LinkedHashMap<>();
        List<long[]> candidates = jdbcTemplate.query("""
                SELECT t.id, t.project_id, COALESCE(t.batch_id, 0) AS batch_id, COALESCE(b.env_id, 0) AS env_id,
                       b.pool_tag_id, t.action_json
                FROM tp_exec_task t
                LEFT JOIN tp_exec_batch b ON b.id = t.batch_id
                WHERE t.status = ? AND b.status IS DISTINCT FROM ?
                ORDER BY t.id LIMIT 20
                """, (rs, n) -> {
            long taskId = rs.getLong("id");
            String actionJson = rs.getString("action_json");
            long perfBudget = perfRunDispatchSupport.budgetMsOf(actionJson);
            if (perfBudget > 0) {
                budgetByTask.put(taskId, perfBudget);
            }
            if (sessionDeclarationSupport.isSticky(actionJson)) {
                String sessionKey = sessionDeclarationSupport.sessionKey(actionJson);
                if (sessionKey != null) {
                    stickyRefByTask.put(taskId, sessionDeclarationSupport.leaseRef(
                            rs.getLong("project_id"), rs.getLong("env_id"), sessionKey));
                }
            }
            appSkipByTask.put(taskId, appAutoDispatchSupport.shouldSkipForExecutor(
                    actionJson, executor.capabilities()));
            perfSkipByTask.put(taskId, perfEngineRoutingSupport.shouldSkipForExecutor(
                    actionJson, executor.capabilities()));
            String deviceSerial = appAutoDispatchSupport.leaseDeviceSerial(actionJson);
            String leaseKind = com.sixbean.testpike.core.appauto.domain.component
                    .AppautoLeaseRunRecorder.KIND_DEVICE;
            if (deviceSerial == null) {
                deviceSerial = appAutoDispatchSupport.exploreMobileLeaseSerial(actionJson);
                leaseKind = com.sixbean.testpike.core.appauto.domain.component
                        .AppautoLeaseRunRecorder.KIND_EXPLORE;
            }
            if (deviceSerial != null) {
                appSerialByTask.put(taskId, deviceSerial);
                leaseKindByTask.put(taskId, leaseKind);
            }
            return new long[]{taskId, rs.getLong("project_id"),
                    rs.getLong("batch_id"), rs.getLong("env_id"),
                    rs.getObject("pool_tag_id") == null ? 0L : rs.getLong("pool_tag_id")};
        }, STATUS_PENDING, 5);
        Map<Long, Integer> poolQuotaByProject = new LinkedHashMap<>();
        for (long[] cand : candidates) {
            if (Boolean.TRUE.equals(appSkipByTask.get(cand[0]))) {
                continue;
            }
            if (Boolean.TRUE.equals(perfSkipByTask.get(cand[0]))) {
                continue;   // GA-4: perf candidate without a matching engine face — queued, next candidate judged
            }
            if (perProjectMax > 0 && runningByProject.getOrDefault(cand[1], 0L) >= perProjectMax) {
                continue;
            }
            if (cand[4] != 0L && !poolDispatchSupport.isMemberOfPool(
                    executor.groupIds(), cand[4])) {
                continue;
            }
            if (cand[4] != 0L) {
                Integer quotaPct = poolQuotaByProject.get(cand[1]);
                if (quotaPct == null) {
                    Integer configured = poolDispatchSupport.quotaPctOfProject(cand[1]);
                    quotaPct = configured == null ? 0 : configured;
                    poolQuotaByProject.put(cand[1], quotaPct);
                }
                if (quotaPct > 0) {
                    int slotCap = poolDispatchSupport.poolSlotCap(cand[4], quotaPct);
                    if (poolDispatchSupport.runningInPool(cand[1], cand[4]) >= slotCap) {
                        continue;
                    }
                }
            }
            if (cand[2] != 0L && cand[3] != 0L && !envExecutionLock.tryAcquire(cand[3], cand[2])) {
                continue;
            }
            String stickyRef = stickyRefByTask.get(cand[0]);
            if (stickyRef != null) {
                String leaseHolder = resourceLeaseService.activeHolder(
                        com.sixbean.testpike.core.executor.domain.component.SessionDeclarationSupport
                                .LEASE_KIND, stickyRef);
                if (leaseHolder != null && !leaseHolder.equals(
                        com.sixbean.testpike.core.executor.domain.component.SessionDeclarationSupport
                                .holderOf(executorId))) {
                    continue;
                }
            }
            String appDeviceSerial = appSerialByTask.get(cand[0]);
            if (appDeviceSerial != null) {
                String deviceHolder = appautoLeaseRunRecorder.activeHolder(
                        appDeviceSerial, leaseKindByTask.get(cand[0]));
                if (deviceHolder != null && !deviceHolder.equals(
                        com.sixbean.testpike.core.executor.domain.component.SessionDeclarationSupport
                                .holderOf(executorId))) {
                    continue;
                }
            }
 // A-14 dispatch-type single point: a perf candidate carrying an envelope budget
 // gets its own horizon; every other type keeps the global case timeout untouched
 // (TimeoutConfigSupport remains the shared source; legacy assertion still holds).
            long taskTimeoutMs = budgetByTask.getOrDefault(cand[0], timeoutMs);
            ClaimTaskView view = claimOne(cand[0], executorId, taskTimeoutMs, scriptLimits, execPolicy);
            if (view == null) {
                continue;
            }
            if (appDeviceSerial != null) {
                appautoLeaseRunRecorder.bindOnDispatch(cand[1], appDeviceSerial,
                        cand[2] == 0L ? null : cand[2],
                        com.sixbean.testpike.core.executor.domain.component.SessionDeclarationSupport
                                .holderOf(executorId),
                        leaseKindByTask.get(cand[0]));
            }
            jdbcTemplate.update("""
                    UPDATE tp_executor SET running_cnt = running_cnt + 1, updated_at = now() WHERE id = ?
                    """, executorId);
            if (view.batchId() != null) {
                jdbcTemplate.update("UPDATE tp_exec_batch SET status = ?, version = version + 1,"
                                + " updated_at = now() WHERE id = ? AND status = ?",
                        4, view.batchId(), STATUS_QUEUED_BATCH);
                // R62 B-face: dispatch frame rides the same shared ledger (single
                // writer door, no second channel) so the tail replays who took the task.
                batchFinalizeSupport.recordDispatchFrame(view.batchId(),
                        view.taskId(), view.caseId(), executorId);
            }
            return view;
        }
        return null;
    }

    @Override
    @Transactional
    public void ingestSessionArtifacts(SessionArtifactsInput input) {
        List<java.util.Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT batch_id, status, claim_token, action_json, executor_id, project_id"
                        + " FROM tp_exec_task WHERE id = ?", input.taskId());
        if (rows.isEmpty()) {
            throw new ServiceException("执行任务不存在——请在「执行与试运行」刷新任务列表确认任务仍在；如需重跑请在「用例详情」重新发起试运行", BizErrorCode.NOT_FOUND);
        }
        java.util.Map<String, Object> task = rows.get(0);
        if (((Number) task.get("status")).intValue() != STATUS_RUNNING) {
            throw new ServiceException("任务不在执行中，拒绝回注会话工件——请在「执行与试运行」确认任务处于执行中后再回注（已结束的任务不会重新进入执行）", BizErrorCode.STATE_MACHINE_REJECT);
        }
        if (input.claimToken() == null || !input.claimToken().equals(task.get("claim_token"))) {
            throw new ServiceException("任务领取令牌不匹配，拒绝回注（防重放）——请使用最近一次领取任务返回的「领取令牌」重新提交（令牌在重新领取后刷新）", BizErrorCode.VERSION_CONFLICT);
        }
        Long batchId = task.get("batch_id") == null ? null : ((Number) task.get("batch_id")).longValue();
        if (batchId == null) {
            throw new ServiceException("试运行任务不属于任何批次，会话工件无法回注批次上下文——如需批次归集请改从「执行批次」发起；"
                    + "试运行工件仍随任务自身保留",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (input.artifacts() != null) {
            input.artifacts().forEach((key, value) -> {
                if (key != null && !key.isBlank() && value != null) {
                    batchContextStore.put(batchId,
                            com.sixbean.testpike.core.batch.domain.component.BatchContextStore
                                    .ARTIFACT_PREFIX + key, value);
                }
            });
        }
        String actionJson = (String) task.get("action_json");
        String sessionKey = sessionDeclarationSupport.sessionKey(actionJson);
        if (sessionDeclarationSupport.isSticky(actionJson) && sessionKey != null
                && task.get("executor_id") != null) {
            long executorId = ((Number) task.get("executor_id")).longValue();
            Long envId = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(env_id, 0) FROM tp_exec_batch WHERE id = ?", Long.class, batchId);
            String ref = sessionDeclarationSupport.leaseRef(
                    ((Number) task.get("project_id")).longValue(), envId, sessionKey);
            String holder = com.sixbean.testpike.core.executor.domain.component
                    .SessionDeclarationSupport.holderOf(executorId);
            if (!resourceLeaseService.renew(
                    com.sixbean.testpike.core.executor.domain.component.SessionDeclarationSupport
                            .LEASE_KIND, ref, holder)) {
                resourceLeaseService.acquire(
                        com.sixbean.testpike.core.executor.domain.component.SessionDeclarationSupport
                                .LEASE_KIND, ref,
                        ((Number) task.get("project_id")).longValue(), envId, batchId, holder);
            }
        }
    }

    
    @Override
    public ParallelWaterLevelView parallelWaterLevel() {
        Map<Long, Long> runningByProject = runningCountByProject();
        long globalRunning = runningByProject.values().stream().mapToLong(Long::longValue).sum();
        List<ProjectRunningView> projects = runningByProject.entrySet().stream()
                .map(e -> new ProjectRunningView(e.getKey(), e.getValue()))
                .sorted(java.util.Comparator.comparingLong(ProjectRunningView::running)
                        .thenComparingLong(ProjectRunningView::projectId).reversed())
                .toList();
        return new ParallelWaterLevelView(globalRunning, parallelLimitSupport.globalMax(),
                parallelLimitSupport.perProjectMax(), projects);
    }

    
    private Map<Long, Long> runningCountByProject() {
        Map<Long, Long> byProject = new LinkedHashMap<>();
        jdbcTemplate.query("SELECT project_id, count(*) AS n FROM tp_exec_task"
                        + " WHERE status = ? GROUP BY project_id",
                (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                        byProject.put(rs.getLong(1), rs.getLong(2)),
                STATUS_RUNNING);
        return byProject;
    }

    
    private ClaimTaskView claimOne(long taskId, long executorId, long timeoutMs,
                                   Map<String, Integer> scriptLimits,
                                   Map<String, Object> execPolicy) {
        List<ClaimTaskView> claimed = jdbcTemplate.query("""
                SELECT t.id, t.project_id, t.case_id, t.schema_version, t.action_json,
                       t.env_base_url, t.batch_id, t.task_type, t.browser
                FROM tp_exec_task t
                LEFT JOIN tp_exec_batch b ON b.id = t.batch_id
                WHERE t.id = ? AND t.status = ? AND b.status IS DISTINCT FROM 5
                FOR UPDATE OF t SKIP LOCKED
                """, (rs, rowNum) -> {
            String token = UUID.randomUUID().toString().replace("-", "");
            jdbcTemplate.update("""
                    UPDATE tp_exec_task SET status = ?, executor_id = ?, claim_token = ?,
                           started_at = now(), updated_at = now() WHERE id = ?
                    """, STATUS_RUNNING, executorId, token, rs.getLong("id"));
            Long taskBatchId = (Long) rs.getObject("batch_id");
            com.sixbean.testpike.core.batch.domain.component.BatchParamView pinned =
                    batchParamSnapshotReader.readForClaim(taskBatchId, rs.getLong("project_id"),
                            rs.getString("env_base_url"));
            // WE S1 (03 trailing key): normalise {{ELEM:}} targets and snapshot the
            // referenced ledger anchors once per dispatch. Reference-free or dirty
            // action JSON returns the identical string + null binding = legacy shape.
            com.sixbean.testpike.core.webelement.domain.component.WebElementDispatchSnapshot.Envelope
                    elementEnvelope = webElementDispatchSnapshot.assemble(
                            rs.getLong("project_id"), rs.getInt("task_type"),
                            rs.getString("action_json"));
            return new ClaimTaskView(rs.getLong("id"), rs.getLong("case_id"), token,
                    rs.getString("schema_version"), elementEnvelope.actionJson(),
                    rs.getString("env_base_url"), taskBatchId, timeoutMs,
                    pinned.envVars(),
                    rs.getInt("task_type"), scriptLimits, execPolicy,
                    pinned.connections(),
                    pinned.dataVars(),
                    rs.getString("browser"),
                    visualBaselineClaimAssembler.assemble(rs.getLong("project_id"),
                            rs.getLong("case_id"), rs.getString("action_json")),
                    pinned.tlsPolicy(),
                    taskBatchId == null ? Map.of() : batchContextStore.readArtifacts(taskBatchId),
                    roleBindingContextOf(rs.getString("action_json")),
                    stepAliasesOf(rs.getLong("project_id")),
                    rs.getInt("task_type") == 5
                            ? perfRunDispatchSupport.assembleForClaim(
                                    rs.getLong("project_id"), rs.getString("action_json"))
                            : null,
 // Claim-side mock binding: a published-space browser task carries
 // {spaceKey,baseUrl,snapshotVersion}; unbound/draft/retired/other types emit
 // no key (zero-drift trailing discipline). The resolver is the single gate
 // shared with api-test envelope assembly; envBaseUrl keeps the authoritative
 // environment URL — the executor rewrites it only after its guards decide.
                    mockBindingOf(rs.getInt("task_type"), rs.getLong("case_id")),
                    // face 2 (03 trailing sessionState key): a declared login
                    // state is materialised here; refusals travel as an envelope,
                    // never as a thrown claim (queue poisoning ban).
                    sessionStateOf(rs.getInt("task_type"), rs.getLong("project_id"),
                            rs.getString("action_json")),
                    elementEnvelope.anchors());
        }, taskId, STATUS_PENDING);
        return claimed.isEmpty() ? null : claimed.get(0);
    }

    
    private Map<String, String> stepAliasesOf(long projectId) {
        Map<String, String> aliases = new java.util.LinkedHashMap<>();
        stepAliasService.list(projectId)
                .forEach(info -> aliases.put(info.aliasName(), info.target()));
        return aliases;
    }

    /**
     * Claim-side trailing mock key: only a task_type=1 browser task whose case
     * declares a published mock space gets a binding; everything else emits no
     * key (zero-drift trailing discipline, same as perf). The reverse lookup of
     * the case binding is the single read point; publishing/draft gating and
     * address composition live in MockServingBindingResolver (shared with the
     * api-test envelope assembly — red line 6, no second copy).
     */
    private com.sixbean.testpike.core.mock.api.dto.MockServingBinding mockBindingOf(
            int taskType, long caseId) {
        if (taskType != 1 || caseId <= 0) {
            return null;
        }
        Long spaceId = jdbcTemplate.query(
                "SELECT tc.mock_space_id FROM tp_test_case tc"
                        + " WHERE tc.id = ? AND tc.deleted = 0",
                rs -> rs.next() && rs.getObject(1) != null
                        ? rs.getLong(1) : null,
                caseId);
        return mockServingBindingResolver.resolve(spaceId);
    }

    /**
 * face 2 claim assembly: a task_type=1 browser task (or, since
 * WE-S4 P2d, a task_type=3 web explore task whose envelope carries the
 * session login-state declaration attached at submitAct) declaring
 * sessionState{name|roleScope} materialises the login state through
 * the SessionStateSupport single source (resolve + browserStorageStateJson —
 * no second translation, red line 6). Unresolvable declarations come back as
 * a refusal envelope instead of throwing: one bad row must not poison the
 * whole claim round (the queue keeps moving; the executor fails just this
 * task with the relayed plain-language remedy). No declaration or other
 * task type = null key, old claim shape untouched (trailing zero-drift discipline).
 */
    private com.sixbean.testpike.core.session.api.dto.SessionStateClaimBinding sessionStateOf(
            int taskType, long projectId, String actionJson) {
        if (taskType != 1 && taskType != 3) {
            return null;
        }
        com.sixbean.testpike.core.executor.domain.component.SessionStateDeclaration decl =
                sessionStateDeclarationSupport.declaration(actionJson);
        if (decl == null) {
            return null;
        }
        try {
            com.sixbean.testpike.core.session.infrastructure.dataobject.LoginState row =
                    sessionStateSupport.resolve(projectId, decl.name(), decl.roleScope());
            String document = sessionStateSupport.browserStorageStateJson(
                    projectId, row.getStateName(), null);
            return new com.sixbean.testpike.core.session.api.dto.SessionStateClaimBinding(
                    row.getStateName(), row.getKind(), document, null);
        } catch (com.sixbean.testpike.common.exception.ServiceException refused) {
            String selector = decl.hasName() ? decl.name() : decl.roleScope();
            return new com.sixbean.testpike.core.session.api.dto.SessionStateClaimBinding(
                    selector, null, null, refused.getMessage());
        }
    }

    
    private Map<String, String> roleBindingContextOf(String actionJson) {
        var roles = roleDeclarationSupport.roles(actionJson);
        if (roles.isEmpty()) {
            return null;
        }
        Map<String, String> context = new java.util.LinkedHashMap<>();
        roles.forEach(role -> context.put(role.name(), role.binding()));
        return context;
    }

    @Override
    @Transactional
    public void submitResult(ExecResultInput input) {
        ExecTaskInfo task = get(input.taskId());
        if (task == null) {
            throw new ServiceException("执行任务不存在——请在「执行与试运行」刷新任务列表确认任务仍在；如需重跑请在「用例详情」重新发起试运行", BizErrorCode.NOT_FOUND);
        }
        if (task.status() != STATUS_RUNNING) {
            return;
        }
        if (input.claimToken() == null || !input.claimToken().equals(task.claimToken())) {
            throw new ServiceException("任务领取令牌不匹配，拒绝回传（防重放）——请使用最近一次领取任务返回的「领取令牌」重新回传（令牌在重新领取后刷新）", BizErrorCode.VERSION_CONFLICT);
        }
        if (input.result() == null || input.result().isBlank()) {
            throw new ServiceException("执行结果不能为空——请按格式：executionResult=pass/fail/manual_skip 回传", BizErrorCode.VALIDATION_ERROR);
        }
        execStepSnapshotSupport.validate(input.protocolSnapshots());
        int targetStatus = switch (input.result()) {
            case "pass" -> STATUS_SUCCESS;
            case "fail" -> STATUS_FAILED;
            case "manual_skip" -> STATUS_SKIPPED;
            case "cancelled" -> STATUS_CANCELLED;
            default -> throw new ServiceException("执行结果仅支持 pass/fail/manual_skip/cancelled——请改用上述取值回传，示例：executionResult=fail",
                    BizErrorCode.VALIDATION_ERROR);
        };
        jdbcTemplate.update("""
                UPDATE tp_exec_task SET status = ?, error_type = ?, error_detail = ?,
                       steps_timeline = ?::jsonb, screenshots_json = ?::jsonb,
                       duration_ms = ?, log = ?, result_ext = ?::jsonb,
                       finished_at = now(), updated_at = now() WHERE id = ?
                """, targetStatus, input.errorType(), clipToTaskDetailColumn(input.errorDetail()),
                toJson(input.stepsTimeline()), toJson(input.screenshots()),
                input.durationMs(), input.log(), toResultExt(input), input.taskId());
        backfillCaseStatus(task, input.result());
        execStepSnapshotSupport.saveSnapshots(task.id(), input.protocolSnapshots());
        batchFinalizeSupport.onTaskTerminal(task, input.result(), input.errorType(), input.durationMs());
        dbExecAuditSupport.onResultSubmitted(task, input.stepsTimeline());
        visualBaselineCaptureSupport.onResultSubmitted(task, input.stepsTimeline());
        apiRunResultFinalizer.onResultSubmitted(task, input);
        // AV face3: unexpected 401/403 evidence against a declared login state marks
        // that ledger row invalid. Same hook discipline as the line above (after the
        // terminal UPDATE, idempotent early return guards replays, failures here are
        // logged and never break the relay chain).
        sessionAuthRejectionWriteback.onResultSubmitted(task, input);
        // WE relayed per-attempt anchor outcomes feed the columnar stat
        // account and the recomputed Page Element Library confidence. Same hook
        // discipline as the line above (after the terminal UPDATE, idempotent
        // early return guards replays, failures never break the relay chain).
        webElementAnchorStatWriteback.onResultSubmitted(task, input);
        // Heal-evidence trail: relayed self-heal rows land in tp_exec_step_heal_evidence.
        // Same hook discipline as the line above (after the terminal UPDATE,
        // idempotent early return guards replays, failures never break the
        // relay chain).
        stepHealEvidenceWriteback.onResultSubmitted(task, input);
        // a registered write-back reproof settles with this
        // terminal result (pass -> evidence grade 3 + review artifact, any
        // other verdict -> grade kept + failure recorded). Same hook
        // discipline as the line above (after the terminal UPDATE, idempotent
        // early return guards replays, failures never break the relay chain).
        scriptWritebackPlatformReproof.onResultSubmitted(task, input.result());
        perfRunResultFinalizer.onResultSubmitted(task.taskType(), task.projectId(),
                task.actionJson(), input);
        if (task.executorId() != null) {
            executorService.releaseCapacity(task.executorId());
        }
        if (STATUS_FAILED == targetStatus) {
            publishTaskFailedEvent(task, input);
        }
    }

    
    private void publishTaskFailedEvent(ExecTaskInfo task, ExecResultInput input) {
        caseEventPublisher.publish(new CaseEventMessage(
                CaseEventType.EXEC_TASK_FAILED.name(), task.caseId(), task.projectId(), null,
                null, null, null, null, null, System.currentTimeMillis(), null,
                task.id(), input.errorType(), truncateDetail(input.errorDetail()),
                lastScreenshotKey(input.screenshots())));
    }

    
    private static String truncateDetail(String detail) {
        if (detail == null) {
            return null;
        }
        return detail.length() <= 2000 ? detail : detail.substring(0, 2000);
    }

    /** tp_exec_task.error_detail column budget (VARCHAR(1000), schema-init). */
    private static final int TASK_DETAIL_COLUMN_WIDTH = 1000;

    /**
     * Clip an engine failure detail to the task table column width before the
     * terminal UPDATE (the write used to pass the raw engine log; a long
     * detail blew the UPDATE into a data-integrity error and stalled the run
     * at status=1). The tail survives — an engine log's fatal lines sit at the
     * end — and the marker states how much was dropped so a fragment is never
     * mistaken for the whole log. Details within budget pass through untouched.
     * Shared by every executor write of this column (single source).
     */
    static String clipToTaskDetailColumn(String detail) {
        if (detail == null || detail.length() <= TASK_DETAIL_COLUMN_WIDTH) {
            return detail;
        }
        int dropped = detail.length() - (TASK_DETAIL_COLUMN_WIDTH - 34);
        String marker;
        for (int i = 0; i < 3; i++) {
            marker = "[truncated, " + dropped + " chars dropped] ";
            dropped = detail.length() - (TASK_DETAIL_COLUMN_WIDTH - marker.length());
        }
        marker = "[truncated, " + dropped + " chars dropped] ";
        int kept = detail.length() - dropped;
        return marker + detail.substring(detail.length() - kept);
    }

    
    private String lastScreenshotKey(Object screenshots) {
        try {
            JsonNode arr = screenshots instanceof String s && !s.isBlank()
                    ? objectMapper.readTree(s)
                    : objectMapper.valueToTree(screenshots);
            if (arr.isArray() && arr.size() > 0) {
                JsonNode last = arr.get(arr.size() - 1);
                return last.isTextual() && !last.asText().isBlank() ? last.asText() : null;
            }
        } catch (Exception e) {
            log.warn("失败事件末步截图解析失败（归因 vision 链路自动降级）taskId 事件载荷不受影响", e);
        }
        return null;
    }

    
    private void backfillCaseStatus(ExecTaskInfo task, String result) {
        if ("pass".equals(result)) {
            jdbcTemplate.update("""
                    UPDATE tp_test_case SET status = 3, version = version + 1, updated_at = now()
                    WHERE id = ? AND status IN (6, 7, 8) AND deleted = 0
                    """, task.caseId());
        } else if ("fail".equals(result)) {
            jdbcTemplate.update("""
                    UPDATE tp_test_case SET status = CASE WHEN status = 3 THEN 8 ELSE 7 END,
                           version = version + 1, updated_at = now()
                    WHERE id = ? AND status IN (3, 6) AND deleted = 0
                    """, task.caseId());
        }
        backfillPlanCase(task, result);
    }

    
    private void backfillPlanCase(ExecTaskInfo task, String result) {
        int pcStatus = switch (result) {
            case "pass" -> 3;
            case "fail" -> 4;
            case "manual_skip" -> 5;
            default -> 0;
        };
        if (pcStatus == 0) {
            return;
        }
        jdbcTemplate.update("""
                UPDATE tp_plan_case pc SET status = ?, updated_at = now()
                FROM tp_exec_task t, tp_exec_batch b
                WHERE pc.batch_case_ref = t.id AND t.batch_id = b.id
                  AND b.plan_id IS NOT NULL AND t.id = ?
                """, pcStatus, task.id());
    }

    
    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return value instanceof String s ? s : objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new ServiceException("步骤时间戳/截图 JSON 序列化失败: " + e.getMessage()
                    + "——请重试回传；持续失败时请携带「任务ID」联系管理员排查执行记录",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    
    private String toResultExt(ExecResultInput input) {
        if (isEmptyEvidence(input.consoleViolations())
                && isEmptyEvidence(input.netHits())
                && isEmptyEvidence(input.dbDigest())
                && isEmptyEvidence(input.recordEvents())
                && isEmptyEvidence(input.mockNotes())
                && isEmptyEvidence(input.mobilePerfSummary())) {
            return null;
        }
        Map<String, Object> ext = new java.util.LinkedHashMap<>();
        ext.put("consoleViolations", input.consoleViolations());
        ext.put("netHits", input.netHits());
        ext.put("dbDigest", input.dbDigest());
        if (!isEmptyEvidence(input.recordEvents())) {
            ext.put("recordEvents", input.recordEvents());
        }
        // Mock audit bucket: an empty list never writes the key, keeping result_ext
        // free of empty-array noise for non-mock tasks (same discipline as recordEvents).
        if (!isEmptyEvidence(input.mockNotes())) {
            ext.put("mockNotes", input.mockNotes());
        }
        // Mobile sampling rollup: the executor is the only aggregation point, so core
        // copies the numbers verbatim. An empty object never writes the key, which keeps
        // result_ext free of noise for tasks that never drove a device (same discipline
        // as recordEvents and mockNotes).
        if (!isEmptyEvidence(input.mobilePerfSummary())) {
            ext.put("mobilePerfSummary", input.mobilePerfSummary());
        }
        return toJson(ext);
    }

    private static boolean isEmptyEvidence(Object value) {
        return value == null
                || (value instanceof java.util.Collection<?> c && c.isEmpty())
                || (value instanceof java.util.Map<?, ?> m && m.isEmpty())
                || (value instanceof String s && (s.isBlank() || "[]".equals(s.trim())));
    }

    @Override
    public ExecTaskInfo get(long taskId) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM tp_exec_task WHERE id = ?",
                this::mapRow, taskId).stream().findFirst()
                .orElseThrow(() -> new ServiceException("执行任务不存在——请在「执行与试运行」刷新任务列表确认任务仍在；如需重跑请在「用例详情」重新发起试运行", BizErrorCode.NOT_FOUND));
    }

    @Override
    public List<ExecTaskInfo> listByIds(List<Long> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) {
            return List.of();
        }
        List<Long> ids = taskIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM tp_exec_task WHERE id IN ("
                        + com.sixbean.testpike.core.common.domain.component.SqlPlaceholders.in(ids.size())
                        + ") ORDER BY id",
                this::mapRow, ids.toArray());
    }

    @Override
    public List<ExecTaskInfo> listByProject(long projectId, int page, int size,
                                            String sortBy, String sortDir,
                                            Long batchId, String statusIn) {
        int safeSize = PageQueryPolicy.clampSize(VirtualScrollEndpoint.EXEC_TASKS_LIST, size);
        int offset = (int) PageQueryPolicy.clampOffset(page, safeSize, Integer.MAX_VALUE);
        List<Object> args = new ArrayList<>();
        args.add(projectId);
        String narrowing = projectTaskNarrowing(batchId, statusIn, args);
        args.add(safeSize);
        args.add(offset);
        return jdbcTemplate.query("SELECT " + COLUMNS
                        + " FROM tp_exec_task WHERE project_id = ?" + narrowing
                        + orderClause(sortBy, sortDir) + " LIMIT ? OFFSET ?",
                this::mapRow, args.toArray());
    }

    /** Task status codes selectable in the list filter (1 pending … 6 skipped). */
    private static final java.util.Set<Integer> TASK_STATUS_WHITELIST =
            java.util.Set.of(1, 2, 3, 4, 5, 6);

    /**
     * Parses the comma separated status filter through the whitelist; an
     * off-whitelist or non-numeric code is refused fail-closed with a
     * human-readable message (single-source in core — the admin bridge and
     * the page never re-implement this decision).
     */
    private static List<Integer> parseStatusWhitelist(String statusIn) {
        if (statusIn == null || statusIn.isBlank()) {
            return List.of();
        }
        List<Integer> codes = new ArrayList<>();
        for (String token : statusIn.split(",")) {
            int code;
            try {
                code = Integer.parseInt(token.trim());
            } catch (NumberFormatException e) {
                throw new ValidationException("执行任务状态筛选必须是逗号分隔的状态码（收到：" + token + "）");
            }
            if (!TASK_STATUS_WHITELIST.contains(code)) {
                throw new ValidationException("执行任务状态筛选只接受 1 待派发/2 执行中/3 成功/4 失败/5 超时/6 跳过（收到："
                        + code + "）");
            }
            if (!codes.contains(code)) {
                codes.add(code);
            }
        }
        return codes;
    }

    /**
     * Shared WHERE fragment + bound args for the narrowed project task read
     * (one builder for list and count so rows and the total can never drift
     * apart across two filter copies).
     */
    private String projectTaskNarrowing(Long batchId, String statusIn, List<Object> args) {
        StringBuilder where = new StringBuilder();
        if (batchId != null) {
            where.append(" AND batch_id = ?");
            args.add(batchId);
        }
        List<Integer> statuses = parseStatusWhitelist(statusIn);
        if (!statuses.isEmpty()) {
            where.append(" AND status IN (")
                    .append(com.sixbean.testpike.core.common.domain.component.SqlPlaceholders.in(statuses.size()))
                    .append(")");
            args.addAll(statuses);
        }
        return where.toString();
    }

    /** Physical columns the project task list may order by; input never reaches SQL raw. */
    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "id", "id",
            "case_id", "case_id",
            "status", "status",
            "schema_version", "schema_version",
            "duration_ms", "duration_ms",
            "created_at", "created_at");

    /**
     * ORDER BY builder: a null/blank sort key keeps the historical id DESC
     * newest-first order; a whitelisted key orders by that column with the
     * row id as tie-breaker so paging stays deterministic under equal values.
     * An off-list key is refused fail-closed by the shared whitelist gate.
     */
    private String orderClause(String sortBy, String sortDir) {
        String column = SortColumnPolicy.require(sortBy, SORT_COLUMNS, "执行任务");
        if (column == null) {
            return " ORDER BY id DESC";
        }
        return " ORDER BY " + column
                + ("asc".equalsIgnoreCase(sortDir) ? " ASC" : " DESC")
                + ", id DESC";
    }

    @Override
    public long countByProject(long projectId, Long batchId, String statusIn) {
        List<Object> args = new ArrayList<>();
        args.add(projectId);
        String narrowing = projectTaskNarrowing(batchId, statusIn, args);
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tp_exec_task WHERE project_id = ?" + narrowing,
                Long.class, args.toArray());
        return count == null ? 0 : count;
    }

    @Override
    public List<ScreenshotView> listScreenshots(long taskId) {
        ExecTaskInfo info = get(taskId);
        return grantScreenshotViews(taskId, info.screenshots());
    }

    
    @Override
    public String screenshotViewUrl(long taskId, String objectKey) {
        ExecTaskInfo info = get(taskId);
        boolean inGate = false;
        String screenshots = info.screenshots();
        if (objectKey != null && !objectKey.isBlank()
                && screenshots != null && !screenshots.isBlank()) {
            try {
                JsonNode arr = objectMapper.readTree(screenshots);
                for (JsonNode node : arr) {
                    if (objectKey.equals(node.isTextual() ? node.asText() : null)) {
                        inGate = true;
                        break;
                    }
                }
            } catch (Exception e) {
                throw new ServiceException("截图产物解析失败: " + e.getMessage() + "——请重新上传截图后重试；持续失败时请携带「任务ID」联系管理员排查产物存储", BizErrorCode.INTERNAL_ERROR);
            }
        }
        if (!inGate) {
            throw new ServiceException("截图不属于该执行任务，拒绝签发——请核对「任务ID」与截图归属同一任务后重新签发", BizErrorCode.FORBIDDEN);
        }
        return artifactStorage.grantViewUrl(objectKey);
    }

    @Override
    public List<ExecStepSnapshotView> listStepSnapshots(long taskId) {
        get(taskId);
        return execStepSnapshotSupport.listSnapshots(taskId);
    }

    
    @Override
    public List<ExecStepEvidenceView> listStepEvidence(long taskId) {
        ExecTaskInfo info = get(taskId);
        String timeline = info.stepsTimeline();
        if (timeline == null || timeline.isBlank()) {
            return List.of();
        }
        try {
            JsonNode arr = objectMapper.readTree(timeline);
            if (!arr.isArray()) {
                return List.of();
            }
            List<ExecStepEvidenceView> views = new ArrayList<>();
            for (JsonNode row : arr) {
                JsonNode evidence = row.path("evidence");
                if (!evidence.isObject() || evidence.isEmpty()) {
                    continue;
                }
                String shotKey = textOrNull(evidence.path("screenshot"));
                String treeKey = textOrNull(evidence.path("view_tree"));
                String logKey = textOrNull(evidence.path("log_tail"));
                String recKey = textOrNull(evidence.path("record"));
                String netKey = textOrNull(evidence.path("net_timeline"));
                String perfKey = textOrNull(evidence.path("perf_timeline"));
                views.add(new ExecStepEvidenceView(taskId,
                        row.path("stepNo").isNumber() ? row.path("stepNo").asInt() : 0,
                        textOrNull(row.path("action")),
                        shotKey, shotKey == null ? null : artifactStorage.grantViewUrl(shotKey),
                        treeKey, treeKey == null ? null : artifactStorage.grantViewUrl(treeKey),
                        logKey, logKey == null ? null : artifactStorage.grantViewUrl(logKey),
                        recKey, recKey == null ? null : artifactStorage.grantViewUrl(recKey),
                        netKey, netKey == null ? null : artifactStorage.grantViewUrl(netKey),
                        perfKey, perfKey == null ? null : artifactStorage.grantViewUrl(perfKey)));
            }
            views.sort(java.util.Comparator.comparingInt(ExecStepEvidenceView::stepNo));
            return views;
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException("步骤证据解析失败: " + e.getMessage() + "——请重新提交步骤证据；持续失败时请携带「任务ID」联系管理员排查", BizErrorCode.INTERNAL_ERROR);
        }
    }

    
    private static String textOrNull(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        String v = node.asText();
        return v.isBlank() ? null : v;
    }

    
    private List<ScreenshotView> grantScreenshotViews(long taskId, String screenshotsJson) {
        if (screenshotsJson == null || screenshotsJson.isBlank()) {
            return List.of();
        }
        try {
            JsonNode arr = objectMapper.readTree(screenshotsJson);
            List<ScreenshotView> views = new ArrayList<>();
            for (JsonNode node : arr) {
                String key = node.isTextual() ? node.asText() : null;
                if (key == null || key.isBlank()) {
                    continue;
                }
                views.add(new ScreenshotView(taskId, key, artifactStorage.grantViewUrl(key)));
            }
            return views;
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException("截图产物解析失败: " + e.getMessage() + "——请重新上传截图后重试；持续失败时请携带「任务ID」联系管理员排查产物存储", BizErrorCode.INTERNAL_ERROR);
        }
    }

    @Override
    public PageResult<CaseExecHistoryView> listHistoryByCase(long caseId, long projectId, int page, int size) {
        Integer caseCnt = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tp_test_case WHERE id = ? AND project_id = ? AND deleted = 0",
                Integer.class, caseId, projectId);
        if (caseCnt == null || caseCnt == 0) {
            throw new ServiceException("用例不存在——请在「用例库」刷新列表确认该用例仍在（若已删除可在「回收站」找回）", BizErrorCode.NOT_FOUND);
        }
        int limit = PageQueryPolicy.enforceSize(size <= 0 ? 10 : size);
        int offset = (int) PageQueryPolicy.clampOffset(page, limit, Integer.MAX_VALUE);
        List<CaseExecHistoryView> rows = jdbcTemplate.query("""
                SELECT t.id, t.batch_id, t.status, t.error_type, t.error_detail,
                       t.ai_failure_class, t.ai_failure_note, t.duration_ms,
                       to_char(t.started_at, 'YYYY-MM-DD HH24:MI:SS') AS started_at,
                       to_char(t.finished_at, 'YYYY-MM-DD HH24:MI:SS') AS finished_at,
                       t.screenshots_json::text AS screenshots,
                       b.name AS batch_name, b.type AS batch_type,
                       b.env_version_snapshot AS batch_env_version,
                       t.env_version_snapshot AS task_env_version, t.env_base_url,
                       t.action_json AS action_json_text
                FROM tp_exec_task t
                LEFT JOIN tp_exec_batch b ON b.id = t.batch_id
                WHERE t.case_id = ? AND t.project_id = ?
                ORDER BY t.id DESC LIMIT ? OFFSET ?
                """, (rs, rowNum) -> {
            long taskId = rs.getLong("id");
            Long batchId = (Long) rs.getObject("batch_id");
            return new CaseExecHistoryView(taskId, batchId, rs.getString("batch_name"),
                    batchId == null ? null : (Integer) rs.getObject("batch_type"),
                    rs.getString("batch_env_version") != null ? rs.getString("batch_env_version")
                            : (rs.getString("task_env_version") != null
                                    ? rs.getString("task_env_version") : rs.getString("env_base_url")),
                    rs.getInt("status"), (Integer) rs.getObject("error_type"),
                    rs.getString("error_detail"),
                    (Integer) rs.getObject("ai_failure_class"), rs.getString("ai_failure_note"),
                    (Integer) rs.getObject("duration_ms"),
                    rs.getString("started_at"), rs.getString("finished_at"),
                    grantScreenshotViews(taskId, rs.getString("screenshots")),
                    appAutoDispatchSupport.envelopeEngineCode(rs.getString("action_json_text")));
        }, caseId, projectId, limit, offset);
        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tp_exec_task WHERE case_id = ? AND project_id = ?",
                Long.class, caseId, projectId);
        return new PageResult<>(rows, total == null ? 0 : total);
    }

    @Override
    public ExecutorTaskPageView listByExecutor(long executorId, String statusGroup, int page, int size,
                                               String sortBy, String sortDir) {
        // Sort is resolved before the window shortcut: an injection-shaped key is
        // refused fail-closed even when the executor currently has no rows
        // .
        String orderBy = orderClause(sortBy, sortDir);
        String countsSql = """
                SELECT
                  (SELECT COUNT(*) FROM tp_exec_task WHERE status = 1) AS pending,
                  COUNT(*) FILTER (WHERE status = 2) AS running,
                  COUNT(*) FILTER (WHERE status IN (3, 6)) AS done,
                  COUNT(*) FILTER (WHERE status IN (4, 5)) AS failed,
                  COUNT(*) AS total
                FROM tp_exec_task WHERE executor_id = ?
                """;
        var counts = jdbcTemplate.queryForObject(countsSql,
                (rs, rowNum) -> new int[]{
                        rs.getInt("pending"), rs.getInt("running"), rs.getInt("done"),
                        rs.getInt("failed"), rs.getInt("total")},
                executorId);
        int pending = counts == null ? 0 : counts[0];
        int running = counts == null ? 0 : counts[1];
        int done = counts == null ? 0 : counts[2];
        int failed = counts == null ? 0 : counts[3];
        long total = counts == null ? 0 : counts[4];
        int limit = PageQueryPolicy.enforceSize(size <= 0 ? 10 : size);
        int offset = (int) PageQueryPolicy.clampOffset(page, limit, Integer.MAX_VALUE);
        if (pending + running + done + failed == 0) {
            return new ExecutorTaskPageView(pending, running, done, failed, List.of(), total);
        }
        String where = "pending".equals(statusGroup) ? " WHERE status = 1"
                : " WHERE executor_id = ?";
        String statusFilter = switch (statusGroup == null ? "all" : statusGroup) {
            case "pending" -> "";
            case "running" -> " AND status = 2";
            case "done" -> " AND status IN (3, 6)";
            case "failed" -> " AND status IN (4, 5)";
            case "terminal" -> " AND status IN (3, 4, 5, 6)";
            default -> "";
        };
        List<ExecTaskInfo> list;
        if ("pending".equals(statusGroup)) {
            list = jdbcTemplate.query("SELECT " + COLUMNS + " FROM tp_exec_task" + where
                            + orderBy + " LIMIT ? OFFSET ?",
                    this::mapRow, limit, offset);
        } else {
            list = jdbcTemplate.query("SELECT " + COLUMNS + " FROM tp_exec_task" + where
                            + statusFilter + orderBy + " LIMIT ? OFFSET ?",
                    this::mapRow, executorId, limit, offset);
        }
        return new ExecutorTaskPageView(pending, running, done, failed, list, total);
    }

    @Override
    public String getLog(long taskId) {
        List<String> logs = jdbcTemplate.query("SELECT log FROM tp_exec_task WHERE id = ?",
                (rs, rowNum) -> rs.getString("log"), taskId);
        if (logs.isEmpty()) {
            throw new ServiceException("执行任务不存在——请在「执行与试运行」刷新任务列表确认任务仍在；如需重跑请在「用例详情」重新发起试运行", BizErrorCode.NOT_FOUND);
        }
        return logs.get(0);
    }

    
    @Override
    @Transactional
    public int sweepTimeoutTasks() {
        int timeoutMinutes = timeoutConfigSupport.caseTimeoutMinutes();
        // A-14 budget self-healing: the timeout horizon is decided per row — a perf
        // row is judged by its envelope budgetMs (scenario duration + margin), every
        // other row keeps the global case timeout. The SQL coarse filter matches the
        // claim path exactly (envelope parse stays in PerfRunDispatchSupport, single
        // source, no second budget dialect).
        List<long[]> timedOut = jdbcTemplate.query("""
                SELECT t.id, COALESCE(t.executor_id, 0) AS executor_id, COALESCE(t.batch_id, 0) AS batch_id,
                       t.action_json
                FROM tp_exec_task t
                WHERE t.status = ? AND (
                    COALESCE(t.task_type, 0) <> 5
                        AND t.started_at < now() - make_interval(mins => ?)
                    OR COALESCE(t.task_type, 0) = 5
                        AND COALESCE((t.action_json::jsonb ->> 'budgetMs')::BIGINT, 0) <= 0
                        AND t.started_at < now() - make_interval(mins => ?)
                    OR COALESCE(t.task_type, 0) = 5
                        AND t.started_at < now()
                            - (t.action_json::jsonb ->> 'budgetMs')::BIGINT * interval '1 millisecond'
                )
                ORDER BY t.id
                """, (rs, n) -> new long[]{rs.getLong("id"), rs.getLong("executor_id"),
                rs.getLong("batch_id"),
                perfRunDispatchSupport.budgetMsOf(rs.getString("action_json"))},
                STATUS_RUNNING, timeoutMinutes, timeoutMinutes);
        if (timedOut.isEmpty()) {
            return 0;
        }
        LinkedHashSet<Long> batches = new LinkedHashSet<>();
        int swept = 0;
        // Second-pass horizon check: the SQL filter is coarse, the envelope parse in
        // the row mapper is the single source shared with claim dispatch. A budgeted
        // row inside its own horizon is left running (that is the whole point — the
        // global line must not cut a long perf run down mid-flight).
        for (long[] row : timedOut) {
            long budgetMs = row[3];
            long horizonMs = budgetMs > 0 ? budgetMs : timeoutMinutes * 60_000L;
            Long elapsedMs = jdbcTemplate.queryForObject("""
                    SELECT COALESCE(EXTRACT(EPOCH FROM (now() - started_at)) * 1000, 0)::BIGINT
                    FROM tp_exec_task WHERE id = ? AND status = ?
                    """, Long.class, row[0], STATUS_RUNNING);
            if (elapsedMs == null || elapsedMs < horizonMs) {
                continue;
            }
            String humanReason = budgetMs > 0
                    ? "执行超时：压测运行超过自身预算 " + (budgetMs / 60_000L)
                      + " 分钟（场景时长+宽限）未回传结果，已由服务端标记超时（如为误判请延长场景时长或调整 perf.budget-margin-minutes）"
                    : "执行超时：任务超过 " + timeoutMinutes + " 分钟未回传结果，已由服务端标记超时（如为误判请联系管理员调整超时阈值）";
            int marked = jdbcTemplate.update("""
                    UPDATE tp_exec_task SET status = ?, error_detail = ?, finished_at = now(),
                           updated_at = now()
                    WHERE id = ? AND status = ?
                    """, STATUS_TIMEOUT, humanReason, row[0], STATUS_RUNNING);
            if (marked == 0) {
                continue;
            }
            swept++;
            applyTerminalRelease(row[1] == 0 ? null : row[1], row[0]);
            if (row[2] != 0) {
                batches.add(row[2]);
            }
        }
        batchFinalizeSupport.onTasksTimedOut(batches);
        return swept;
    }

    
    private void applyTerminalRelease(Long executorId, long taskId) {
        if (executorId != null) {
            executorService.releaseCapacity(executorId);
        }
        releasePoolItemsOfTask(taskId);
    }

    
    private void releaseDirectPoolLock(long taskId) {
        jdbcTemplate.update(
                "UPDATE tp_data_pool_item SET status = 1, locked_by = NULL, locked_at = NULL"
                        + " WHERE status = 2 AND locked_by = ?", taskId);
    }

    
    private void releasePoolItemsOfTask(long taskId) {
        jdbcTemplate.update("""
                UPDATE tp_data_pool_item SET status = 1, locked_by = NULL, locked_at = NULL
                WHERE status = 2 AND locked_by IN (
                    SELECT bc.id FROM tp_batch_case bc
                    JOIN tp_exec_task t ON t.batch_id = bc.batch_id AND t.case_id = bc.case_id
                    WHERE t.id = ?)
                """, taskId);
    }

    
    @Override
    @Transactional
    public int redistributeTasksOfOfflineExecutors() {
        return redistributeOfflineTasks(null, RECLAIM_SOURCE_SWEEP);
    }

 
    @Override
    @Transactional
    public int redistributeTasksOfBatch(long batchId) {
        return redistributeOfflineTasks(batchId, RECLAIM_SOURCE_MANUAL);
    }

    @Override
    public int countSweepReclaimedOfBatch(long batchId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_exec_task WHERE batch_id = ? AND status = ?"
                        + " AND last_reclaim_source = ?",
                Integer.class, batchId, STATUS_PENDING, RECLAIM_SOURCE_SWEEP);
        return n == null ? 0 : n;
    }

    /** Shared reclaim section for the global sweep and the per-batch manual recovery.
     * batchId=null means global. Every reclaim move stamps last_reclaim_source (1=sweep,
     * 2=manual) so the recover reconciliation can tell apart tasks reclaimed by the 15s
     * auto sweep from those reclaimed by the operator just now (V270 trace columns). */
    private int redistributeOfflineTasks(Long batchId, int reclaimSource) {
        List<long[]> dangling = batchId == null
                ? jdbcTemplate.query("""
                        SELECT t.id, t.executor_id FROM tp_exec_task t
                        JOIN tp_executor e ON e.id = t.executor_id
                        WHERE t.status = ? AND e.status = ?
                        ORDER BY t.id
                        """, (rs, n) -> new long[]{rs.getLong("id"), rs.getLong("executor_id")},
                        STATUS_RUNNING, EXECUTOR_OFFLINE)
                : jdbcTemplate.query("""
                        SELECT t.id, t.executor_id FROM tp_exec_task t
                        JOIN tp_executor e ON e.id = t.executor_id
                        WHERE t.status = ? AND e.status = ? AND t.batch_id = ?
                        ORDER BY t.id
                        """, (rs, n) -> new long[]{rs.getLong("id"), rs.getLong("executor_id")},
                        STATUS_RUNNING, EXECUTOR_OFFLINE, batchId);
        int moved = 0;
        for (long[] row : dangling) {
            int marked = jdbcTemplate.update("""
                    UPDATE tp_exec_task SET status = ?, claim_token = ?, executor_id = NULL,
                           started_at = NULL, updated_at = now(),
                           last_reclaim_source = ?, last_reclaimed_at = now()
                    WHERE id = ? AND status = ?
                    """, STATUS_PENDING, UUID.randomUUID().toString().replace("-", ""),
                    reclaimSource, row[0], STATUS_RUNNING);
            if (marked == 0) {
                continue;
            }
            moved++;
            executorService.releaseCapacity(row[1]);
        }
        return moved;
    }

    
    @Override
    @Transactional
    @Audit(objectType = "exec_task", action = "batch_case_rerun", objectId = "#taskId")
    public BatchCaseRerunResult rerunBatchCase(long taskId, Long operatorId, String engineCode) {
        RerunSource source = lockSourceTask(taskId);
        ExecTaskInfo task = source.task();
        if (task.status() == STATUS_PENDING || task.status() == STATUS_RUNNING) {
            throw new ServiceException("该用例正在排队或执行中，请等待本次执行结束后，再在「用例详情」点击重跑",
                    BizErrorCode.STATE_MACHINE_REJECT);
        }
        Long pending = source.batchId() == null
                ? jdbcTemplate.query("SELECT id FROM tp_exec_task WHERE case_id = ? AND batch_id IS NULL"
                        + " AND status IN (?, ?) AND id <> ? ORDER BY id DESC LIMIT 1",
                        (rs, i) -> rs.getLong(1), task.caseId(), STATUS_PENDING, STATUS_RUNNING, taskId)
                    .stream().findFirst().orElse(null)
                : jdbcTemplate.query("SELECT id FROM tp_exec_task WHERE batch_id = ? AND case_id = ?"
                        + " AND status IN (?, ?) ORDER BY id DESC LIMIT 1",
                        (rs, i) -> rs.getLong(1), source.batchId(), task.caseId(),
                        STATUS_PENDING, STATUS_RUNNING)
                    .stream().findFirst().orElse(null);
        if (pending != null) {
            return new BatchCaseRerunResult(pending, false, "该用例已有重跑任务在排队或执行中，无需重复发起");
        }
        boolean appTask = appAutoDispatchSupport.isAppAutoEnvelope(task.actionJson());
        String switchNote = "";
        String effectiveActionJson = task.actionJson();
        if (engineCode != null && !engineCode.isBlank()) {
            if (!appTask) {
                throw new ServiceException("换引擎重跑仅适用于 App 自动化任务——本任务不是 App "
                        + "自动化信封（网页用例不挂引擎），请在「用例详情」清空「目标引擎」后按原脚本重跑",
                        BizErrorCode.VALIDATION_ERROR);
            }
            com.sixbean.testpike.core.appauto.api.dto.AppautoPortabilityInfo pre =
                    appautoPortabilityService.precheck(task.projectId(), task.caseId(), engineCode);
            if (com.sixbean.testpike.core.appauto.api.dto.AppautoPortabilityInfo.REWRITE
                    .equals(pre.verdict())) {
                throw new ServiceException("换引擎「" + pre.engineName() + "」预检未通过，已拒派"
                        + "（不烧执行机会话）——需改写：请先在「用例详情」的 App 步骤区调整编排后重试。改写清单："
                        + String.join("；", pre.items()), BizErrorCode.VALIDATION_ERROR);
            }
            effectiveActionJson = toJson(appAutoDispatchSupport.buildEnvelope(task.projectId(),
                    engineCode, envelopeEnvProfileId(task.actionJson()),
                    appAutoDispatchSupport.extractAppSteps(task.actionJson())));
            switchNote = "（本次换用引擎：" + pre.engineName()
                    + (com.sixbean.testpike.core.appauto.api.dto.AppautoPortabilityInfo.DEGRADED
                            .equals(pre.verdict()) ? "，降级跑——能力收窄项："
                            + String.join("；", pre.items()) : "") + "）";
        }
        long newTaskId;
        if (appTask) {
            newTaskId = createAppAutoRun(task.projectId(), task.caseId(), source.batchId(),
                    effectiveActionJson);
        } else {
            long envId = resolveRerunEnvId(task, source.batchId());
            newTaskId = create(new ExecTaskCreateInput(task.projectId(), task.caseId(),
                    task.schemaVersion(), effectiveActionJson, envId, source.batchId()));
        }
        jdbcTemplate.update("""
                UPDATE tp_test_case SET status = 6, version = version + 1, updated_at = now()
                WHERE id = ? AND status IN (7, 8) AND deleted = 0
                """, task.caseId());
        jdbcTemplate.update("UPDATE tp_plan_case SET batch_case_ref = ?, updated_at = now() WHERE batch_case_ref = ?",
                newTaskId, taskId);
        return new BatchCaseRerunResult(newTaskId, true, "已发起重跑，等待执行器领取" + switchNote);
    }

    
    private Long envelopeEnvProfileId(String actionJson) {
        try {
            Object parsed = objectMapper.readValue(actionJson,
                    new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {
                    });
            if (parsed instanceof java.util.Map<?, ?> envelope
                    && envelope.get("envProfile") instanceof java.util.Map<?, ?> profile
                    && profile.get("id") instanceof Number id) {
                return id.longValue();
            }
        } catch (Exception e) {
            log.debug("换引擎重跑档案引用解析失败（按无档案重建）: {}", e.getMessage());
        }
        return null;
    }

    
    static final int BATCH_CANCEL_MAX = 500;

    @Override
    @Transactional
    @Audit(objectType = "exec_task", action = "task_cancel", objectId = "#taskId")
    public void cancel(long operatorId, long taskId) {
        ExecTaskInfo task = get(taskId);
        if (task.status() != STATUS_PENDING && task.status() != STATUS_RUNNING) {
            throw new ServiceException("该任务已结束（当前状态码 " + task.status()
                    + "），无需取消——如需重跑请在「用例详情」重新发起试运行", BizErrorCode.STATE_MACHINE_REJECT);
        }
        cancelRunningTask(task, "已由操作人取消（操作人 id=" + operatorId
                + "，终止指令将随下一次心跳下发执行器终止进程）");
    }

    @Override
    @Transactional
    public BatchCancelResult batchCancel(long operatorId, List<Long> taskIds) {
        List<Long> ids = taskIds == null ? List.of() : taskIds;
        if (ids.size() > BATCH_CANCEL_MAX) {
            throw new ServiceException("批量取消单次最多 " + BATCH_CANCEL_MAX + " 条（当前 "
                    + ids.size() + " 条），请在「执行批次」任务列表每批勾选不超过上限的条数分批取消", BizErrorCode.VALIDATION_ERROR);
        }
        int cancelled = 0;
        List<Long> rejected = new ArrayList<>();
        for (Long id : ids) {
            if (id == null) {
                continue;
            }
            ExecTaskInfo task = findOrNull(id);
            if (task == null || (task.status() != STATUS_PENDING && task.status() != STATUS_RUNNING)
                    || !cancelRunningTask(task, "已由操作人批量取消（任务终止指令已下发执行器）")) {
                rejected.add(id);
            } else {
                cancelled++;
            }
        }
        return new BatchCancelResult(cancelled, rejected);
    }

    
    private boolean cancelRunningTask(ExecTaskInfo task, String reason) {
        int marked = jdbcTemplate.update("""
                UPDATE tp_exec_task SET status = ?, error_detail = ?, finished_at = now(),
                       updated_at = now()
                WHERE id = ? AND status IN (?, ?)
                """, STATUS_CANCELLED, reason, task.id(), STATUS_PENDING, STATUS_RUNNING);
        if (marked == 0) {
            return false;
        }
        applyTerminalRelease(task.executorId(), task.id());
        releaseDirectPoolLock(task.id());
        Long batchId = jdbcTemplate.query("SELECT batch_id FROM tp_exec_task WHERE id = ?",
                (org.springframework.jdbc.core.ResultSetExtractor<Long>) rs ->
                        rs.next() ? (Long) rs.getObject(1) : null, task.id());
        if (batchId != null) {
            batchFinalizeSupport.onTasksTimedOut(List.of(batchId));
        }
        return true;
    }

    @Override
    public List<Long> pendingCancelsFor(long executorId) {
        List<Long> ids = jdbcTemplate.query("""
                SELECT id FROM tp_exec_task
                WHERE status = ? AND executor_id = ? AND cancel_notified_at IS NULL
                ORDER BY id
                """, (rs, n) -> rs.getLong(1), STATUS_CANCELLED, executorId);
        if (ids.isEmpty()) {
            return ids;
        }
        String placeholders = String.join(",", ids.stream().map(x -> "?").toList());
        jdbcTemplate.update("UPDATE tp_exec_task SET cancel_notified_at = now(), updated_at = now()"
                + " WHERE id IN (" + placeholders + ") AND cancel_notified_at IS NULL", ids.toArray());
        return ids;
    }

    @Override
    public PageResult<RunningTaskView> listRunning(int page, int size, String sortBy, String sortDir) {
        int limit = PageQueryPolicy.enforceSize(size <= 0 ? 20 : size);
        int offset = (int) PageQueryPolicy.clampOffset(page, limit, Integer.MAX_VALUE);
        // Sort is resolved before the query: an injection-shaped key is refused
        // fail-closed even when no task is currently running .
        String orderBy = runningOrderClause(sortBy, sortDir);
        long timeoutMs = timeoutConfigSupport.caseTimeoutMinutes() * 60_000L;
        List<RunningTaskView> rows = jdbcTemplate.query("""
                SELECT t.id, t.project_id, c.title, t.batch_id, t.executor_id,
                       to_char(t.started_at, 'YYYY-MM-DD HH24:MI:SS') AS started_at,
                       COALESCE(EXTRACT(EPOCH FROM (now() - t.started_at)) * 1000, 0)::BIGINT AS elapsed_ms
                FROM tp_exec_task t
                LEFT JOIN tp_test_case c ON c.id = t.case_id
                WHERE t.status = ?
                """ + orderBy + """
                 LIMIT ? OFFSET ?
                """, (rs, n) -> new RunningTaskView(rs.getLong("id"), rs.getLong("project_id"),
                rs.getString("title"), (Long) rs.getObject("batch_id"),
                (Long) rs.getObject("executor_id"), rs.getString("started_at"),
                rs.getLong("elapsed_ms"), timeoutMs), STATUS_RUNNING, limit, offset);
        Long total = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_exec_task WHERE status = ?", Long.class, STATUS_RUNNING);
        return new PageResult<>(rows, total == null ? 0 : total);
    }

    /**
     * Columns the running window may order by: physical task columns plus the
     * computed started_at/elapsed pair the rows render. Values are fully
     * qualified (t./alias) — input never reaches SQL raw. The elapsed_ms key
     * references the SELECT alias, the same expression the row displays, so
     * header order and visible duration can never disagree.
     */
    private static final Map<String, String> RUNNING_SORT_COLUMNS = Map.of(
            "id", "t.id",
            "case_id", "t.case_id",
            "batch_id", "t.batch_id",
            "executor_id", "t.executor_id",
            "started_at", "t.started_at",
            "elapsed_ms", "elapsed_ms");

    /**
     * ORDER BY builder for the running window: a null/blank sort key keeps the
     * historical id ASC order (note: the project/executor task windows default
     * to id DESC — each window keeps its own historical order, no drift); a
     * whitelisted key orders by that column with the row id as a constant ASC
     * tie-breaker so paging stays deterministic under equal values. An
     * off-list key is refused fail-closed by the shared whitelist gate.
     */
    private String runningOrderClause(String sortBy, String sortDir) {
        String column = SortColumnPolicy.require(sortBy, RUNNING_SORT_COLUMNS, "执行中任务");
        if (column == null) {
            return " ORDER BY t.id ASC";
        }
        return " ORDER BY " + column
                + ("asc".equalsIgnoreCase(sortDir) ? " ASC" : " DESC")
                + ", t.id ASC";
    }

    
    private ExecTaskInfo findOrNull(long taskId) {
        List<ExecTaskInfo> rows = jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM tp_exec_task WHERE id = ?", this::mapRow, taskId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    
    private RerunSource lockSourceTask(long taskId) {
        return jdbcTemplate.query("SELECT batch_id, " + COLUMNS + " FROM tp_exec_task WHERE id = ? FOR UPDATE",
                (rs, rowNum) -> new RerunSource(mapRow(rs, rowNum), (Long) rs.getObject("batch_id")), taskId)
                .stream().findFirst()
                .orElseThrow(() -> new ServiceException("执行任务不存在——请在「执行与试运行」刷新任务列表确认任务仍在；如需重跑请在「用例详情」重新发起试运行", BizErrorCode.NOT_FOUND));
    }

    
    private long resolveRerunEnvId(ExecTaskInfo task, Long batchId) {
        if (batchId != null) {
            Long batchEnvId = jdbcTemplate.query("SELECT env_id FROM tp_exec_batch WHERE id = ?",
                    (rs, i) -> (Long) rs.getObject(1), batchId)
                    .stream().findFirst().orElse(null);
            if (batchEnvId != null) {
                return batchEnvId;
            }
        }
        if (task.envBaseUrl() != null && !task.envBaseUrl().isBlank()) {
            Long envId = jdbcTemplate.query("SELECT id FROM tp_project_env WHERE project_id = ? AND base_url = ?"
                    + " ORDER BY id LIMIT 1", (rs, i) -> rs.getLong(1),
                    task.projectId(), task.envBaseUrl())
                    .stream().findFirst().orElse(null);
            if (envId != null) {
                return envId;
            }
        }
        throw new ServiceException("无法确定重跑的执行环境（原环境已删除或未记录）——请在「用例详情」重新选择「选择执行环境」后发起试运行",
                BizErrorCode.VALIDATION_ERROR);
    }

    

    @Override
    public void updateAiClassify(long taskId, Integer aiFailureClass, String aiFailureNote) {
        if (aiFailureClass == null || aiFailureClass < AI_CLASS_MIN || aiFailureClass > AI_CLASS_MAX) {
            throw new ServiceException("失败归因取值非法——请按格式：1=产品缺陷 / 2=AI定位错误 / 3=环境问题 填写",
                    BizErrorCode.VALIDATION_ERROR);
        }
        String note = aiFailureNote == null ? null
                : aiFailureNote.length() <= AI_NOTE_MAX_LEN
                        ? aiFailureNote : aiFailureNote.substring(0, AI_NOTE_MAX_LEN);
        int updated = jdbcTemplate.update("""
                UPDATE tp_exec_task
                   SET ai_failure_class = ?, ai_failure_note = ?, ai_classified_at = now(),
                       updated_at = now()
                 WHERE id = ?
                """, aiFailureClass, note, taskId);
        if (updated == 0) {
            throw new ServiceException("执行任务不存在——请在「执行与试运行」刷新任务列表确认任务仍在；如需重跑请在「用例详情」重新发起试运行", BizErrorCode.NOT_FOUND);
        }
    }

    @Override
    public List<Map<String, Object>> listRecentClassify(long caseId) {
        return jdbcTemplate.queryForList("""
                SELECT error_detail, ai_failure_class
                  FROM tp_exec_task
                 WHERE case_id = ? AND ai_failure_class IS NOT NULL
                   AND ai_classified_at > now() - interval '24 hour'
                """, caseId);
    }

    private ExecTaskInfo mapRow(ResultSet rs, int rowNum) throws SQLException {
        int taskType = rs.getInt("task_type");
        // Badge deep-link source: only a pressure row re-reads its envelope
        // (runIdOf is fail-safe null on dirty payloads); every other row keeps
        // null without touching the JSON = zero drift on the shared read path.
        Long perfRunId = taskType == 5
                ? perfRunDispatchSupport.runIdOf(rs.getString("action_json"))
                : null;
        return new ExecTaskInfo(rs.getLong("id"), rs.getLong("project_id"),
                rs.getLong("case_id"), (Long) rs.getObject("executor_id"),
                rs.getInt("status"), rs.getString("claim_token"),
                rs.getString("schema_version"), rs.getString("action_json"),
                rs.getString("env_base_url"), rs.getString("env_version_snapshot"),
                (Integer) rs.getObject("error_type"),
                rs.getString("error_detail"), rs.getString("steps_timeline"),
                rs.getString("screenshots_json"), rs.getString("video_key"),
                (Integer) rs.getObject("duration_ms"), rs.getString("log"),
                rs.getString("result_ext"),
                (Integer) rs.getObject("ai_failure_class"), rs.getString("ai_failure_note"),
                rs.getString("started_at"), rs.getString("finished_at"), rs.getString("created_at"),
                taskType, perfRunId);
    }
}
