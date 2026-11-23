package com.sixbean.testpike.core.batch.domain.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.OptimisticLockingException;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.report.PassRateSql;
import com.sixbean.testpike.core.audit.api.dto.AuditLogInput;
import com.sixbean.testpike.core.audit.domain.component.Audit;
import com.sixbean.testpike.core.audit.domain.component.AuditLogContext;
import com.sixbean.testpike.core.audit.domain.component.AuditSnapshot;
import com.sixbean.testpike.core.audit.domain.service.AuditLogService;
import com.sixbean.testpike.core.batch.api.dto.BadgeView;
import com.sixbean.testpike.core.batch.api.dto.BatchCaseInfo;
import com.sixbean.testpike.core.batch.api.dto.BatchCreateInput;
import com.sixbean.testpike.core.batch.api.dto.BatchFromTemplateInput;
import com.sixbean.testpike.core.batch.api.dto.BatchInfo;
import com.sixbean.testpike.core.batch.api.dto.BatchQuery;
import com.sixbean.testpike.core.batch.api.dto.BatchRecoveryView;
import com.sixbean.testpike.core.batch.api.dto.BatchStartResult;
import com.sixbean.testpike.core.batch.api.dto.BatchTemplateInfo;
import com.sixbean.testpike.core.batch.api.dto.BatchUpdateInput;
import com.sixbean.testpike.core.batch.api.dto.EnvOverrideInput;
import com.sixbean.testpike.core.batch.api.dto.TemplateScheduleInput;
import com.sixbean.testpike.core.batch.domain.component.BatchFinalizeSupport;
import com.sixbean.testpike.core.batch.domain.component.BatchPrecheckResult;
import com.sixbean.testpike.core.batch.domain.component.BatchPrecheckRow;
import com.sixbean.testpike.core.batch.domain.component.BatchPrecheckSupport;
import com.sixbean.testpike.core.batch.domain.component.EnvExecutionLock;
import com.sixbean.testpike.core.batch.domain.service.BatchService;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.common.domain.component.SqlPlaceholders;
import com.sixbean.testpike.core.common.domain.component.SortColumnPolicy;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import com.sixbean.testpike.core.executor.api.dto.BatchCaseRerunResult;
import com.sixbean.testpike.core.executor.api.dto.ExecTaskCreateInput;
import com.sixbean.testpike.core.executor.domain.component.BrowserKernelSupport;
import com.sixbean.testpike.core.executor.domain.component.TimeoutConfigSupport;
import com.sixbean.testpike.core.executor.domain.component.SessionStateDeclaration;
import com.sixbean.testpike.core.executor.domain.service.ExecTaskService;
import com.sixbean.testpike.core.plans.api.dto.SkipItem;
import com.sixbean.testpike.core.project.api.dto.ProjectEnvInfo;
import com.sixbean.testpike.core.project.domain.component.ProjectRoleResolver;
import com.sixbean.testpike.core.project.domain.service.ProjectAccessService;
import com.sixbean.testpike.core.project.domain.service.ProjectEnvService;
import com.sixbean.testpike.core.sched.domain.component.ScheduleCronTimes;
import com.sixbean.testpike.core.script.api.dto.ScriptInfo;
import com.sixbean.testpike.core.script.domain.service.ScriptService;
import com.sixbean.testpike.core.session.infrastructure.dataobject.LoginState;
import com.sixbean.testpike.core.session.domain.component.SessionPreflightOutcome;
import com.sixbean.testpike.core.session.domain.component.SessionPreflightRow;
import com.sixbean.testpike.core.session.domain.component.SessionPreflightSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;


@Service
public class BatchServiceImpl implements BatchService {

 
    private static final int STATUS_DRAFT = 1;
    private static final int STATUS_QUEUED = 2;
    private static final int STATUS_ENV_UNAVAILABLE = 3;
    private static final int STATUS_RUNNING = 4;
    private static final int STATUS_PAUSED = 5;
    private static final int STATUS_DONE = 6;
    private static final int STATUS_PARTIAL_FAILED = 7;
    private static final int STATUS_CANCELLED = 8;
 
    private static final int TASK_PENDING = 1;
    private static final int TASK_RUNNING = 2;
    private static final int TASK_FAILED = 4;
    private static final int TASK_TIMEOUT = 5;
    private static final int TASK_SKIPPED = 6;
    private static final int TYPE_FORMAL = 1;
    private static final int TYPE_TEMP = 2;
 
    private static final int RESULT_NOT_RUN = 4;
 
    private static final String DELETED_SKIP_REASON = "启动前已从用例库删除";

    private final JdbcTemplate jdbcTemplate;
    private final CaseService caseService;
    private final ProjectEnvService projectEnvService;
    private final ProjectAccessService projectAccessService;
    private final ObjectMapper objectMapper;
    private final ConfigCacheHolder configCacheHolder;
    private final ExecTaskService execTaskService;
    private final ScriptService scriptService;
    private final BatchFinalizeSupport batchFinalizeSupport;
    private final TimeoutConfigSupport timeoutConfigSupport;
    private final EnvExecutionLock envExecutionLock;
    private final AuditLogService auditLogService;
 
    private final com.sixbean.testpike.core.project.domain.component.EnvTlsSupport envTlsSupport;
 
    private final com.sixbean.testpike.core.batch.domain.component.CiGatePolicy ciGatePolicy;
    private final com.sixbean.testpike.core.batch.domain.component.BatchParamResolver batchParamResolver;
    private final com.sixbean.testpike.core.batch.domain.component.BatchParamSnapshotStore batchParamSnapshotStore;
 
    private final com.sixbean.testpike.core.monitor.domain.service.MonitorEpisodeService monitorEpisodeService;
    private final com.sixbean.testpike.core.executor.domain.component.AppAutoDispatchSupport appAutoDispatchSupport;
    
    private final com.sixbean.testpike.core.batch.domain.component.BatchQueueEstimateSupport queueEstimateSupport;
    
    private final com.sixbean.testpike.core.program.domain.component.ProgramLensScope programLensScope;
    
    private final com.sixbean.testpike.core.executor.domain.component.SessionStateDeclarationSupport sessionStateDeclarationSupport;
    private final com.sixbean.testpike.core.session.domain.component.SessionStateSupport sessionStateSupport;
    private final com.sixbean.testpike.core.session.domain.component.SessionGovernanceKeys sessionGovernanceKeys;

    public BatchServiceImpl(JdbcTemplate jdbcTemplate, CaseService caseService,
                            ProjectEnvService projectEnvService,
                            ProjectAccessService projectAccessService, ObjectMapper objectMapper,
                            ConfigCacheHolder configCacheHolder, ExecTaskService execTaskService,
                            ScriptService scriptService,
                            BatchFinalizeSupport batchFinalizeSupport,
                            TimeoutConfigSupport timeoutConfigSupport,
                            EnvExecutionLock envExecutionLock,
                            AuditLogService auditLogService,
                            com.sixbean.testpike.core.project.domain.component.EnvTlsSupport envTlsSupport,
                            com.sixbean.testpike.core.batch.domain.component.CiGatePolicy ciGatePolicy,
                            com.sixbean.testpike.core.batch.domain.component.BatchParamResolver batchParamResolver,
                            com.sixbean.testpike.core.batch.domain.component.BatchParamSnapshotStore batchParamSnapshotStore,
                            com.sixbean.testpike.core.monitor.domain.service.MonitorEpisodeService monitorEpisodeService,
                            com.sixbean.testpike.core.executor.domain.component.AppAutoDispatchSupport appAutoDispatchSupport,
                            com.sixbean.testpike.core.batch.domain.component.BatchQueueEstimateSupport queueEstimateSupport,
                            com.sixbean.testpike.core.program.domain.component.ProgramLensScope programLensScope,
                            com.sixbean.testpike.core.executor.domain.component.SessionStateDeclarationSupport sessionStateDeclarationSupport,
                            com.sixbean.testpike.core.session.domain.component.SessionStateSupport sessionStateSupport,
                            com.sixbean.testpike.core.session.domain.component.SessionGovernanceKeys sessionGovernanceKeys) {
        this.jdbcTemplate = jdbcTemplate;
        this.caseService = caseService;
        this.projectEnvService = projectEnvService;
        this.projectAccessService = projectAccessService;
        this.objectMapper = objectMapper;
        this.configCacheHolder = configCacheHolder;
        this.execTaskService = execTaskService;
        this.scriptService = scriptService;
        this.batchFinalizeSupport = batchFinalizeSupport;
        this.timeoutConfigSupport = timeoutConfigSupport;
        this.envExecutionLock = envExecutionLock;
        this.auditLogService = auditLogService;
        this.envTlsSupport = envTlsSupport;
        this.ciGatePolicy = ciGatePolicy;
        this.batchParamResolver = batchParamResolver;
        this.batchParamSnapshotStore = batchParamSnapshotStore;
        this.monitorEpisodeService = monitorEpisodeService;
        this.appAutoDispatchSupport = appAutoDispatchSupport;
        this.queueEstimateSupport = queueEstimateSupport;
        this.programLensScope = programLensScope;
        this.sessionStateDeclarationSupport = sessionStateDeclarationSupport;
        this.sessionStateSupport = sessionStateSupport;
        this.sessionGovernanceKeys = sessionGovernanceKeys;
    }


    @Override
    @Transactional
    @Audit(objectType = "exec_batch", action = "batch_create", objectId = "#result.id", after = "#result")
    public BatchInfo create(long operatorId, BatchCreateInput input) {
        if (input.name() == null || input.name().isBlank()) {
            throw new ServiceException("批次名称不能为空——请在「执行批次」建批表单填写「批次名称」后再提交", BizErrorCode.VALIDATION_ERROR);
        }
        if (input.caseIds() == null || input.caseIds().isEmpty()) {
            throw new ServiceException("请至少勾选一条用例（从用例库选择后创建批次）——请在「执行批次」建批表单的用例列表勾选至少 1 条后保存",
                    BizErrorCode.VALIDATION_ERROR);
        }
        ProjectEnvInfo env = resolveEnvOfProject(input.envId(), input.projectId());
        List<CaseInfo> cases = loadOwnedCases(input.projectId(), input.caseIds());
        if (env != null) {
            assertEndTypesMatch(env, cases);
        }
        String batchNo = "B-" + UUID.randomUUID().toString().substring(0, 8);
        Integer notifyFeishu = input.notifyFeishu();
        if (notifyFeishu != null && (notifyFeishu < 0 || notifyFeishu > 1)) {
            throw new ServiceException("飞书群通知开关仅接受 0=仅站内 1=开——请在「执行批次」建批表单把「飞书群通知」改成 0 或 1（0=仅站内通知，1=同时推送飞书群）", BizErrorCode.VALIDATION_ERROR);
        }
        assertPoolTagOfProject(input.poolTagId(), input.projectId());
        String browser = input.browser() == null || input.browser().isBlank()
                ? null : BrowserKernelSupport.resolve(input.browser(), env);
        Integer cleanAppData = input.cleanAppData();
        if (cleanAppData != null && (cleanAppData < 0 || cleanAppData > 1)) {
            throw new ServiceException("跑批自动清机开关仅接受 0=关 1=开——请在「执行批次」建批表单把「跑批后自动清机」改成 0 或 1（0=不清机，1=跑批后自动清空应用数据）", BizErrorCode.VALIDATION_ERROR);
        }
        long batchId = jdbcTemplate.queryForObject("""
                INSERT INTO tp_exec_batch (project_id, env_id, batch_no, name, status,
                                           type, template_id, schedule_type, created_by, notify_feishu,
                                           pool_tag_id, browser, clean_app_data)
                VALUES (?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, input.projectId(),
                env == null ? null : env.id(), batchNo, input.name().trim(), STATUS_DRAFT,
                TYPE_FORMAL, input.templateId(), operatorId, notifyFeishu == null ? 0 : notifyFeishu,
                input.poolTagId(), browser, cleanAppData == null ? 0 : cleanAppData);
        insertBatchCases(batchId, cases);
        return get(batchId);
    }

    @Override
    @Transactional
    @Audit(objectType = "exec_batch", action = "batch_update", objectId = "#id", after = "#result")
    public BatchInfo update(long operatorId, long id, BatchUpdateInput input) {
        return update(operatorId, id, input, get(id).version());
    }

    @Override
    @Transactional
    @Audit(objectType = "exec_batch", action = "batch_update", objectId = "#id", after = "#result")
    public BatchInfo update(long operatorId, long id, BatchUpdateInput input, int expectedVersion) {
        BatchInfo batch = get(id);
        AuditLogContext.setBefore(AuditSnapshot.snapshot(batch));
        if (batch.status() != STATUS_DRAFT) {
            throw new ServiceException("仅草稿态批次可编辑用例集（当前状态：" + batch.statusLabel()
                    + "；请在「执行批次」列表新建草稿批次后编辑，已启动批次请先取消再重建）", BizErrorCode.STATE_MACHINE_REJECT);
        }
        assertVersionFresh(batch, expectedVersion);
        ProjectEnvInfo env = batchEnv(id);
        if (input.envId() != null && !input.envId().equals(batch.envId())) {
            env = resolveEnvOfProject(input.envId(), batch.projectId());
            jdbcTemplate.update("UPDATE tp_exec_batch SET env_id = ?, updated_at = now() WHERE id = ?",
                    input.envId(), id);
        }
        if (input.name() != null && !input.name().isBlank()
                && !input.name().trim().equals(batch.name())) {
            jdbcTemplate.update("UPDATE tp_exec_batch SET name = ?, updated_at = now() WHERE id = ?",
                    input.name().trim(), id);
        }
        if (input.poolTagId() != null) {
            assertPoolTagOfProject(input.poolTagId(), batch.projectId());
            jdbcTemplate.update("UPDATE tp_exec_batch SET pool_tag_id = ?, updated_at = now() WHERE id = ?",
                    input.poolTagId(), id);
        }
        if (input.browser() != null && !input.browser().isBlank()) {
            String browser = BrowserKernelSupport.resolve(input.browser(), env);
            jdbcTemplate.update("UPDATE tp_exec_batch SET browser = ?, updated_at = now() WHERE id = ?",
                    browser, id);
        }
        if (input.cleanAppData() != null) {
            if (input.cleanAppData() < 0 || input.cleanAppData() > 1) {
                throw new ServiceException("跑批自动清机开关仅接受 0=关 1=开——请在「执行批次」建批表单把「跑批后自动清机」改成 0 或 1（0=不清机，1=跑批后自动清空应用数据）",
                        BizErrorCode.VALIDATION_ERROR);
            }
            jdbcTemplate.update("UPDATE tp_exec_batch SET clean_app_data = ?, updated_at = now()"
                    + " WHERE id = ?", input.cleanAppData(), id);
        }
        if (input.removeCaseIds() != null && !input.removeCaseIds().isEmpty()) {
            jdbcTemplate.update("DELETE FROM tp_batch_case WHERE batch_id = ? AND case_id IN ("
                    + SqlPlaceholders.in(input.removeCaseIds().size()) + ")",
                    concat(id, input.removeCaseIds()));
        }
        if (input.addCaseIds() != null && !input.addCaseIds().isEmpty()) {
            List<CaseInfo> added = loadOwnedCases(batch.projectId(), input.addCaseIds());
            if (env != null) {
                assertEndTypesMatch(env, added);
            }
            insertBatchCases(id, added);
        }
        int bumped = jdbcTemplate.update("UPDATE tp_exec_batch SET version = version + 1,"
                + " updated_at = now() WHERE id = ? AND version = ?", id, expectedVersion);
        if (bumped == 0) {
            throw new OptimisticLockingException(
                    "批次已被他人修改，请刷新后重试（当前版本 " + get(id).version() + "）");
        }
        return get(id);
    }

    @Override
    public BatchInfo get(long id) {
        List<BatchInfo> rows = jdbcTemplate.query(BATCH_COLUMNS + " WHERE b.id = ?",
                (rs, n) -> mapBatch(rs), id);
        if (rows.isEmpty()) {
            throw new ServiceException("批次不存在——请在「执行批次」刷新列表确认该批次仍在（可能已被删除）", BizErrorCode.NOT_FOUND);
        }
        return withEnvOngoingEpisode(rows.get(0));
    }

    
    private BatchInfo withEnvOngoingEpisode(BatchInfo b) {
        if (b.envId() == null) {
            return b;
        }
        com.sixbean.testpike.core.monitor.api.dto.MonitorEpisodeView open =
                monitorEpisodeService.findOpenEpisodeByEnv(b.envId());
        if (open == null) {
            return b;
        }
        return new BatchInfo(b.id(), b.projectId(), b.batchNo(), b.name(), b.envId(), b.envName(),
                b.status(), b.statusLabel(), b.type(), b.templateId(), b.caseCount(), b.queuePos(),
                b.createdBy(), b.createdAt(), b.startedAt(), b.finishedAt(), b.version(),
                b.message(), b.notifyFeishu(), b.poolTagId(), b.browser(), b.runtimeChangeCount(),
                open, b.cleanAppData());
    }

    @Override
    public BadgeView getByBadgeToken(String badgeToken) {
        UUID token;
        try {
            token = UUID.fromString(badgeToken == null ? "" : badgeToken.trim());
        } catch (IllegalArgumentException e) {
            throw new ServiceException("徽章地址无效（应为批次徽章链接，请核对来源）——请在「执行批次」详情页直接打开该批次徽章，或复制页面展示的完整链接重试",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return queryBadge(" WHERE b.badge_token = CAST(? AS uuid)", token.toString(),
                "徽章不存在（批次可能已删除）");
    }

    @Override
    public BadgeView badgeByBatchId(long batchId) {
        return queryBadge(" WHERE b.id = ?", batchId, "批次不存在");
    }

    
    @Override
    public com.sixbean.testpike.core.batch.api.dto.CiVerdictView verdict(long batchId) {
        List<Object[]> rows = jdbcTemplate.query(
                "SELECT status, plan_id FROM tp_exec_batch WHERE id = ?",
                (rs, n) -> new Object[]{rs.getInt("status"), rs.getObject("plan_id")}, batchId);
        if (rows.isEmpty()) {
            throw new ServiceException("批次不存在——请在「执行批次」刷新列表确认该批次仍在（可能已被删除）", BizErrorCode.NOT_FOUND);
        }
        Long planId = (Long) rows.get(0)[1];
        return ciGatePolicy.evaluate(batchId, (Integer) rows.get(0)[0], planId);
    }

    
    private BadgeView queryBadge(String where, Object arg, String notFoundMessage) {
        List<BadgeView> rows = jdbcTemplate.query(
                "SELECT b.id, b.status, (" + PassRateSql.RATE_EXPR + ") AS pass_rate"
                        + " FROM tp_exec_batch b LEFT JOIN tp_exec_task t ON t.batch_id = b.id"
                        + where + " GROUP BY b.id, b.status",
                (rs, n) -> new BadgeView(rs.getLong("id"), rs.getInt("status"),
                        BatchInfo.statusLabel(rs.getInt("status")),
                        (java.math.BigDecimal) rs.getObject("pass_rate")), arg);
        if (rows.isEmpty()) {
            throw new ServiceException(notFoundMessage, BizErrorCode.NOT_FOUND);
        }
        return rows.get(0);
    }

    
    @Override
    public com.sixbean.testpike.core.batch.api.dto.BatchReportView report(long batchId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT batch_id, batch_status, total, pass_cnt, fail_cnt, skip_cnt, timeout_cnt,"
                        + " pass_rate, fail_dist_json::text AS fail_dist, attr_json::text AS attr,"
                        + " duration_ms, env_version_snapshot, mock_task_cnt, llm_task_cnt,"
                        + " mobile_perf_json::text AS mobile_perf"
                        + " FROM tp_batch_report WHERE batch_id = ?", batchId);
        if (rows.isEmpty()) {
            throw new ServiceException("该批次暂无汇总报告（批次结束后自动生成；"
                    + "草稿/在跑/已取消批次不产生报告）——请在「执行批次」详情页等批次跑完后再打开「汇总报告」", BizErrorCode.NOT_FOUND);
        }
        Map<String, Object> r = rows.get(0);
        int status = ((Number) r.get("batch_status")).intValue();
        return new com.sixbean.testpike.core.batch.api.dto.BatchReportView(
                batchId, status, BatchInfo.statusLabel(status),
                ((Number) r.get("total")).intValue(),
                ((Number) r.get("pass_cnt")).intValue(),
                ((Number) r.get("fail_cnt")).intValue(),
                ((Number) r.get("skip_cnt")).intValue(),
                ((Number) r.get("timeout_cnt")).intValue(),
                r.get("pass_rate") == null ? null
                        : new java.math.BigDecimal(String.valueOf(r.get("pass_rate"))),
                readCountMap(String.valueOf(r.get("fail_dist"))),
                readCountMap(String.valueOf(r.get("attr"))),
                r.get("duration_ms") == null ? null : ((Number) r.get("duration_ms")).longValue(),
                r.get("env_version_snapshot") == null ? null
                        : String.valueOf(r.get("env_version_snapshot")),
                readMonitorEvidence(batchId),
                ((Number) r.get("mock_task_cnt")).intValue(),
                ((Number) r.get("llm_task_cnt")).intValue(),
                readMobilePerf(r.get("mobile_perf")));
    }

    /**
 * Mobile performance rollup = the V486 snapshot column deserialized as-is.
     * <p>The DTO property names are the snapshot keys written by BatchReportSupport at
     * finalization, so forwarding adds no arithmetic and no second calculation site
     * (recomputation at query time is banned on this read path — same-row discipline as
     * the counters above). A null column, an empty text or the literal "null" all mean
     * the batch reported no mobile metric: the rollup stays null so the report page
     * renders no performance block instead of inventing zeros.
     */
    private com.sixbean.testpike.core.batch.api.dto.MobilePerfRollupView readMobilePerf(Object mobilePerfText) {
        if (mobilePerfText == null) {
            return null;
        }
        String json = String.valueOf(mobilePerfText);
        if (json.isBlank() || "null".equals(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json,
                    com.sixbean.testpike.core.batch.api.dto.MobilePerfRollupView.class);
        } catch (Exception e) {
            throw new ServiceException("批次移动性能汇总数据解析失败——请在「执行批次」详情页刷新「移动性能」区域重试；持续异常请携带批次号反馈", BizErrorCode.INTERNAL_ERROR);
        }
    }

    
    private java.util.List<com.sixbean.testpike.core.monitor.api.dto.MonitorEpisodeView>
            readMonitorEvidence(long batchId) {
        List<java.util.Map<String, Object>> windows = jdbcTemplate.queryForList(
                "SELECT env_id, started_at, COALESCE(finished_at, now()) AS finished_at"
                        + " FROM tp_exec_batch WHERE id = ?", batchId);
        if (windows.isEmpty() || windows.get(0).get("env_id") == null) {
            return List.of();
        }
        java.util.Map<String, Object> w = windows.get(0);
        long envId = ((Number) w.get("env_id")).longValue();
        java.time.LocalDateTime from = toLocalDateTime(w.get("started_at"));
        java.time.LocalDateTime to = toLocalDateTime(w.get("finished_at"));
        if (from == null) {
            return List.of();
        }
        return monitorEpisodeService.findEpisodesOverlappingWindow(envId, from, to);
    }

    private static java.time.LocalDateTime toLocalDateTime(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof java.time.OffsetDateTime odt) {
            return odt.toLocalDateTime();
        }
        return ((java.sql.Timestamp) value).toLocalDateTime();
    }

    
    private Map<String, Integer> readCountMap(String json) {
        if (json == null || json.isBlank() || "null".equals(json)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Integer>>() {
            });
        } catch (Exception e) {
            throw new ServiceException("报告分布数据解析失败——请在「执行批次」详情页刷新「汇总报告」重试；持续异常请携带批次号反馈", BizErrorCode.INTERNAL_ERROR);
        }
    }

    /** Server-side sort whitelist (batch list; frontend column props map onto these
     * keys via SORT_KEY_MAP). Values are physical columns or select aliases —
     * case_count is the subquery alias from BATCH_COLUMNS, never user input. */
    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "name", "b.name",
            "batch_no", "b.batch_no",
            "status", "b.status",
            "created_at", "b.created_at",
            "case_count", "case_count",
            "queue_pos", "b.queue_pos");

    @Override
    public PageResult<BatchInfo> list(BatchQuery query, Long operatorId) {
        int page = Math.max(1, query.page());
        int size = Math.min(200, Math.max(1, query.size() == 0 ? 20 : query.size()));
 // Sort keys must resolve to a physical column (or select alias) through the
 // shared whitelist — user input never reaches the SQL string (SortColumnPolicy.require,
 // same posture as the mcp/skill/tool domains; off-list key = fail-closed 400).
        String sortColumn = SortColumnPolicy.require(query.sortBy(), SORT_COLUMNS, "批次");
        String order = sortColumn == null
                ? " ORDER BY b.id DESC"
                : " ORDER BY " + sortColumn
                        + ("asc".equalsIgnoreCase(query.sortDir()) ? " ASC" : " DESC") + ", b.id DESC";
 // Program lens : optional narrowing resolved by the ProgramLensScope single
 // source — never re-implemented here. null = no lens (legacy path untouched);
 // empty set = honest empty page; non-empty = lens projects ∩ caller visibility.
        java.util.Set<Long> lensProjectIds = query.programId() > 0
                ? programLensScope.visibleProjectIds(query.programId(), operatorId)
                : null;
        StringBuilder where = new StringBuilder(" WHERE");
        List<Object> args = new ArrayList<>();
        if (lensProjectIds == null) {
            where.append(" b.project_id = ?");
            args.add(query.projectId());
        } else if (lensProjectIds.isEmpty()) {
            where.append(" 1 = 0");
        } else {
            where.append(" b.project_id IN (")
                    .append(SqlPlaceholders.in(lensProjectIds.size())).append(")");
            args.addAll(lensProjectIds);
            // Lens + explicit project stays conjunctive (same posture as CaseFilters):
            // the caller sees only rows inside both the lens and the picked project.
            if (query.projectId() > 0L) {
                where.append(" AND b.project_id = ?");
                args.add(query.projectId());
            }
        }
        where.append(" AND b.type <> ?");
        args.add(TYPE_TEMP);
        if (query.status() != null) {
            where.append(" AND b.status = ?");
            args.add(query.status());
        }
        if (query.keyword() != null && !query.keyword().isBlank()) {
            where.append(" AND b.name ILIKE ?");
            args.add("%" + query.keyword().trim() + "%");
        }
        long total = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_exec_batch b" + where, Long.class, args.toArray());
        args.add(size);
        args.add((page - 1) * size);
        List<BatchInfo> list = jdbcTemplate.query(
                BATCH_COLUMNS + where + order + " LIMIT ? OFFSET ?",
                (rs, n) -> mapBatch(rs), args.toArray());
        return new PageResult<>(list, total);
    }

    @Override
    public List<BatchCaseInfo> listCases(long batchId) {
        return jdbcTemplate.query("""
                SELECT bc.id, bc.batch_id, bc.case_id, c.case_no, c.title, c.end_type,
                       c.version AS case_version, bc.sort_order, bc.result, bc.manual_skip, bc.flaky
                FROM tp_batch_case bc
                JOIN tp_test_case c ON c.id = bc.case_id
                WHERE bc.batch_id = ?
                ORDER BY bc.sort_order, bc.id
                """, (rs, n) -> new BatchCaseInfo(rs.getLong("id"), rs.getLong("batch_id"),
                rs.getLong("case_id"), rs.getString("case_no"), rs.getString("title"),
                rs.getString("end_type"), rs.getInt("case_version"), rs.getInt("sort_order"),
                rs.getInt("result"), BatchCaseInfo.resultLabel(rs.getInt("result")),
                rs.getInt("manual_skip"), rs.getInt("flaky")), batchId);
    }

    @Override
    @Transactional
    @Audit(objectType = "exec_batch", action = "batch_start", objectId = "#id", after = "#result")
    public BatchStartResult start(long operatorId, long id) {
        return this.start(operatorId, id, null);
    }

    @Override
    @Transactional
    public BatchStartResult start(long operatorId, long id, EnvOverrideInput envOverrides) {
        assertEnvOverrides(envOverrides);
        List<Integer> locked = jdbcTemplate.query(
                "SELECT status FROM tp_exec_batch WHERE id = ? FOR UPDATE",
                (rs, n) -> rs.getInt(1), id);
        if (locked.isEmpty()) {
            throw new ServiceException("批次不存在——请在「执行批次」刷新列表确认该批次仍在（可能已被删除）", BizErrorCode.NOT_FOUND);
        }
        BatchInfo batch = get(id);
        if (batch.status() != STATUS_DRAFT) {
            throw new ServiceException("仅草稿态批次可启动（当前状态：" + batch.statusLabel()
                    + "；请在「执行批次」列表选择草稿态批次启动，已启动批次请先取消再重建）", BizErrorCode.STATE_MACHINE_REJECT);
        }
        ProjectEnvInfo env = batchEnv(id);
        if (env == null) {
            throw new ServiceException("请选择执行环境后再启动批次——请在「执行批次」建批表单选择「执行环境」（环境可在「项目环境」页维护）", BizErrorCode.VALIDATION_ERROR);
        }
        if (BatchPrecheckSupport.rejectInsecureTls(
                configCacheHolder.get(BatchPrecheckSupport.CFG_REJECT_INSECURE_TLS),
                envTlsSupport.readMode(env.configJson()))) {
            throw new ServiceException(
                    BatchPrecheckSupport.insecureBlockMessage(env.name()), BizErrorCode.VALIDATION_ERROR);
        }
        List<BatchPrecheckRow> alive = new ArrayList<>();
        List<SkipItem> deletedSkips = new ArrayList<>();
        Map<Long, Integer> versions = new java.util.LinkedHashMap<>();
        // Live members declaring a mock space feed the exit-plan mock ban below.
        int[] mockBoundCount = {0};
        jdbcTemplate.query("""
                SELECT bc.case_id, c.case_no, c.title, c.status, c.flaky, c.deleted, c.version,
                       c.mock_space_id
                FROM tp_batch_case bc
                JOIN tp_test_case c ON c.id = bc.case_id
                WHERE bc.batch_id = ?
                ORDER BY bc.sort_order, bc.id
                """, rs -> {
            long caseId = rs.getLong("case_id");
            int status = rs.getInt("status");
            versions.put(caseId, rs.getInt("version"));
            if (rs.getInt("deleted") == 1) {
                deletedSkips.add(new SkipItem(caseId, rs.getString("case_no"),
                        rs.getString("title"), status, DELETED_SKIP_REASON));
            } else {
                if (rs.getObject("mock_space_id") != null) {
                    mockBoundCount[0]++;
                }
                alive.add(new BatchPrecheckRow(caseId, rs.getString("case_no"),
                        rs.getString("title"), status, rs.getInt("flaky")));
            }
        }, id);
        if (versions.isEmpty()) {
            throw new ServiceException("批次内无用例，请先添加用例再启动——请在「执行批次」打开该批次，在建批表单勾选用例保存后再启动", BizErrorCode.VALIDATION_ERROR);
        }
        BatchPrecheckResult pre = BatchPrecheckSupport.classify(alive,
                configCacheHolder.get(BatchPrecheckSupport.CFG_UNEVALUATED_MODE));
        if (pre.blockedByUnevaluated()) {
            throw new ServiceException(BatchPrecheckSupport.blockMessage(pre.unevaluatedSkips()),
                    BizErrorCode.VALIDATION_ERROR);
        }
        // Exit-plan mock ban (honesty chain D2, ruling M-f/U-c): simulated traffic
        // may never stand as real-integration exit evidence. Precheck-stage refusal
        // only — the review state machine is untouched (red line ①). Disabled/absent
        // config = no interception (zero drift for existing batches).
        if (BatchPrecheckSupport.rejectMockBoundCases(
                configCacheHolder.get(BatchPrecheckSupport.CFG_MOCK_EXIT_PLAN_FORBID),
                mockBoundCount[0])) {
            throw new ServiceException(
                    BatchPrecheckSupport.mockBoundBlockMessage(mockBoundCount[0]),
                    BizErrorCode.VALIDATION_ERROR);
        }
        // AV3 trigger 1 (login-state start gate): every sessionState declared in a
        // case's action JSON must resolve to a usable ledger row whose expiry light
        // is not expired. EXPIRED or unresolved rows refuse the whole batch before
        // any snapshot/task materialisation, so one dead session never hangs the
        // run on a login page. EXPIRING/UNKNOWN only append a notice to the start
        // message (execution-side 401/403 still flips the light invalid at once).
        // Classification and refusal wording stay in SessionPreflightSupport —
        // this class only assembles the rows (single source, red line 6).
        SessionPreflightOutcome sessionPreflight = sessionStatePreflight(
                batch.projectId(), alive, pre.approvedCaseIds());
        if (sessionPreflight.refused()) {
            throw new ServiceException(SessionPreflightSupport.refusalMessage(sessionPreflight,
                    sessionGovernanceKeys.renewLeadMinutes()), BizErrorCode.VALIDATION_ERROR);
        }
        List<SkipItem> skips = new ArrayList<>(deletedSkips);
        skips.addAll(pre.skips());
        List<Map<String, Object>> snap = versions.entrySet().stream()
                .map(e -> Map.<String, Object>of("caseId", e.getKey(), "version", e.getValue()))
                .toList();
        int noScript = 0;
        int created = 0;
        String urlOverride = envOverrides == null ? null : envOverrides.url();
        Map<Long, com.sixbean.testpike.core.executor.domain.component.AppAutoCaseTarget>
                appTargets = appAutoTargetsOf(pre.approvedCaseIds());
        int appQueued = 0;
        int appStepSkips = 0;
        String appQueueNote = null;
        List<Long> appSkipCaseIds = new java.util.ArrayList<>();
        for (Long caseId : pre.approvedCaseIds()) {
            ScriptInfo script = scriptService.getByCaseId(caseId);
            var target = appTargets.get(caseId);
            boolean appBranch = target != null && target.automationTarget() != null
                    && !"web".equals(target.automationTarget());
            if (appBranch) {
                List<Map<String, Object>> appSteps = script == null ? List.of()
                        : appAutoDispatchSupport.extractAppSteps(script.actionJson());
                if (appSteps.isEmpty()) {
                    if (script == null || script.actionJson() == null
                            || script.actionJson().isBlank()) {
                        noScript++;
                    } else {
                        appStepSkips++;
                        appSkipCaseIds.add(caseId);
                    }
                    continue;
                }
                Map<String, Object> envelope = appAutoDispatchSupport.buildEnvelope(
                        batch.projectId(), target.appautoEngine(),
                        target.automationTarget(), target.appautoEnvProfileId(), appSteps);
                long taskId = execTaskService.createAppAutoRun(batch.projectId(), caseId, id,
                        toJson(envelope));
                if (appQueueNote == null) {
                    appQueueNote = appAutoDispatchSupport.explainNoCapableExecutor(taskId);
                }
                appQueued++;
                continue;
            }
            if (script == null || script.actionJson() == null || script.actionJson().isBlank()) {
                noScript++;
                continue;
            }
            execTaskService.create(new ExecTaskCreateInput(batch.projectId(), caseId,
                    script.schemaVersion(), script.actionJson(), env.id(), id, urlOverride,
                    batch.browser()));
            created++;
        }
        created += appQueued;
        queueEstimateSupport.lockQueuePosition();
        long ahead = queueEstimateSupport.pendingAheadCount(id);
        long capacity = queueEstimateSupport.idleCapacity();
        long estMinutes = queueEstimateSupport.estimateMinutes(ahead, capacity);
        jdbcTemplate.update("""
                UPDATE tp_exec_batch SET status = ?, queue_pos = ?, started_at = now(),
                       case_version_snapshot = ?::jsonb, env_version_snapshot = ?,
                       skip_cases_json = ?, version = version + 1, updated_at = now()
                WHERE id = ?
                """, STATUS_QUEUED, (int) ahead + 1, toJson(snap), env.versionSnapshot(),
                skips.isEmpty() ? null : toJson(skips), id);
        batchParamSnapshotStore.save(id, 1, batchParamResolver.toEnvelope(
                batchParamResolver.assembleSections(batch.projectId(), env.baseUrl())));
        // R62 B-face: kickoff frame into the shared process-event ledger (kind
        // batch-exec, keyed by batch id) so the batch tail window shows the
        // run as it progresses without any manual refresh.
        batchFinalizeSupport.recordStartFrame(id, created);
        String message = "已生成 " + created + " 个执行任务，批次进入待执行队列"
                + (noScript > 0 ? "，" + noScript + " 条用例无脚本跳过（可补脚本后重新生成）" : "")
                + appStepSkipPhrase(appSkipCaseIds)
                + appQueuePhrase(appQueueNote)
                + BatchPrecheckSupport.skipPhrase(deletedSkips, "已删除用例")
                + BatchPrecheckSupport.skipPhrase(pre.unevaluatedSkips(), "未通过评审用例")
                + BatchPrecheckSupport.skipPhrase(pre.flakySkips(), "不稳定隔离用例")
                + SessionPreflightSupport.warningPhrase(sessionPreflight);
        if (urlOverride != null || (envOverrides != null && envOverrides.accountRef() != null)) {
            auditLogService.record(new AuditLogInput(operatorId, batch.projectId(), "exec_batch",
                    String.valueOf(id), "CI_RUN", null,
                    Map.of("url", urlOverride == null ? "" : urlOverride,
                            "accountRef", envOverrides.accountRef() == null ? "" : envOverrides.accountRef())));
        }
        return new BatchStartResult(id, STATUS_QUEUED, "executing",
                BatchInfo.statusLabel(STATUS_QUEUED), (int) ahead + 1,
                OffsetDateTime.now(ZoneId.systemDefault()).plusMinutes(estMinutes).toString(),
                skips, message);
    }

    /**
     * Assemble one login-state preflight row per approved case whose action JSON
     * declares a sessionState. Cases without a declaration are skipped so legacy
     * batches keep their exact start behaviour (zero drift). A resolution error
     * (missing/disabled/ambiguous ledger row) becomes a blocked row instead of
     * escaping early, so a single start attempt lists every offending case; the
     * classification itself is owned by SessionPreflightSupport (no re-deriving
     * of expiry lights here — red line 6 / single-source rule).
     */
    private SessionPreflightOutcome sessionStatePreflight(long projectId,
                                                          List<BatchPrecheckRow> alive,
                                                          List<Long> approvedCaseIds) {
        int renewLead = sessionGovernanceKeys.renewLeadMinutes();
        LocalDateTime now = LocalDateTime.now();
        Map<Long, BatchPrecheckRow> metaOf = new java.util.HashMap<>();
        for (BatchPrecheckRow row : alive) {
            metaOf.put(row.caseId(), row);
        }
        List<SessionPreflightRow> rows = new ArrayList<>();
        for (Long caseId : approvedCaseIds) {
            BatchPrecheckRow meta = metaOf.get(caseId);
            if (meta == null) {
                continue;
            }
            ScriptInfo script = scriptService.getByCaseId(caseId);
            if (script == null || script.actionJson() == null) {
                continue;
            }
            SessionStateDeclaration declaration =
                    sessionStateDeclarationSupport.declaration(script.actionJson());
            if (declaration == null) {
                continue;
            }
            LoginState state = null;
            try {
                state = sessionStateSupport.resolve(projectId,
                        declaration.name(), declaration.roleScope());
            } catch (ServiceException unresolvable) {
                // null resolved name -> blocked line naming the declared selector
            }
            rows.add(new SessionPreflightRow(meta.caseNo(), meta.title(), declaration,
                    state == null ? null : state.getStateName(),
                    state == null ? null
                            : sessionStateSupport.expiryState(state.getExpiresAt(), now, renewLead)));
        }
        return SessionPreflightSupport.classify(rows, renewLead);
    }


    
    @Override
    public BatchStartResult quickExecute(long operatorId, long caseId, Long envId) {
        return quickExecute(operatorId, caseId, envId, null);
    }

    @Override
    public BatchStartResult quickExecute(long operatorId, long caseId, Long envId, String browser) {
        CaseInfo caseInfo = caseService.get(caseId);
        BatchInfo batch = self.createTempBatch(operatorId, caseId, envId, caseInfo, browser);
        return self.start(operatorId, batch.id());
    }

    
    @Transactional
    @Audit(objectType = "exec_batch", action = "batch_create", objectId = "#result.id", after = "#result")
    public BatchInfo createTempBatch(long operatorId, long caseId, Long envId, CaseInfo caseInfo) {
        return createTempBatch(operatorId, caseId, envId, caseInfo, null);
    }

    
    @Transactional
    @Audit(objectType = "exec_batch", action = "batch_create", objectId = "#result.id", after = "#result")
    public BatchInfo createTempBatch(long operatorId, long caseId, Long envId, CaseInfo caseInfo,
                                     String browser) {
        ProjectEnvInfo env = resolveEnvOfProject(envId, caseInfo.projectId());
        List<CaseInfo> cases = loadOwnedCases(caseInfo.projectId(), List.of(caseId));
        if (env != null) {
            assertEndTypesMatch(env, cases);
        }
        String batchBrowser = browser == null || browser.isBlank()
                ? null : BrowserKernelSupport.resolve(browser, env);
        String batchNo = "B-" + UUID.randomUUID().toString().substring(0, 8);
        long batchId = jdbcTemplate.queryForObject("""
                INSERT INTO tp_exec_batch (project_id, env_id, batch_no, name, status,
                                           type, template_id, schedule_type, created_by, browser)
                VALUES (?, ?, ?, ?, ?, ?, NULL, 0, ?, ?) RETURNING id
                """, Long.class, caseInfo.projectId(),
                env == null ? null : env.id(), batchNo,
                "快速执行-" + caseInfo.title(), STATUS_DRAFT, TYPE_TEMP, operatorId, batchBrowser);
        insertBatchCases(batchId, cases);
        return get(batchId);
    }

    @Override
    @Transactional
    @Audit(objectType = "exec_batch", action = "batch_pause", objectId = "#id")
    public BatchInfo pause(long operatorId, long id) {
        return pause(operatorId, id, lockAndGet(id).version());
    }

    @Override
    @Transactional
    @Audit(objectType = "exec_batch", action = "batch_pause", objectId = "#id")
    public BatchInfo pause(long operatorId, long id, int expectedVersion) {
        BatchInfo batch = lockAndGet(id);
        assertOwnerLevel(batch, operatorId, "暂停");
        if (batch.status() != STATUS_RUNNING) {
            throw new ServiceException("仅执行中批次可暂停（当前状态：" + batch.statusLabel()
                    + "；请在「执行批次」列表选择执行中的批次点击暂停（暂停只停止派发，不强杀在跑任务））", BizErrorCode.STATE_MACHINE_REJECT);
        }
        assertVersionFresh(batch, expectedVersion);
        jdbcTemplate.update("UPDATE tp_exec_batch SET status = ?, version = version + 1,"
                        + " updated_at = now() WHERE id = ? AND version = ?",
                STATUS_PAUSED, id, expectedVersion);
        return get(id);
    }

    @Override
    @Transactional
    @Audit(objectType = "exec_batch", action = "batch_resume", objectId = "#id")
    public BatchInfo resume(long operatorId, long id) {
        return resume(operatorId, id, lockAndGet(id).version());
    }

    @Override
    @Transactional
    @Audit(objectType = "exec_batch", action = "batch_resume", objectId = "#id")
    public BatchInfo resume(long operatorId, long id, int expectedVersion) {
        BatchInfo batch = lockAndGet(id);
        assertOwnerLevel(batch, operatorId, "续跑");
        if (batch.status() != STATUS_PAUSED) {
            String suffix = batch.status() == STATUS_CANCELLED
                    ? "；已取消为终态，请在「执行批次」新建批次重建执行集" : "";
            throw new ServiceException("仅已暂停批次可续跑——请在「执行批次」列表选择「已暂停」状态的批次（当前状态：" + batch.statusLabel()
                    + suffix, BizErrorCode.STATE_MACHINE_REJECT);
        }
        assertVersionFresh(batch, expectedVersion);
        jdbcTemplate.update("UPDATE tp_exec_batch SET status = ?, version = version + 1,"
                        + " updated_at = now() WHERE id = ? AND version = ?",
                STATUS_RUNNING, id, expectedVersion);
        return get(id);
    }

    @Override
    @Transactional
    @Audit(objectType = "exec_batch", action = "batch_recover", objectId = "#batchId")
    public BatchRecoveryView recover(long operatorId, long batchId) {
        BatchInfo batch = lockAndGet(batchId);
        assertOwnerLevel(batch, operatorId, "崩溃恢复");
        if (batch.status() != STATUS_RUNNING && batch.status() != STATUS_PAUSED) {
            throw new ServiceException("仅执行中/已暂停批次可崩溃恢复——请在「执行批次」列表选择这两种状态的批次（当前状态：" + batch.statusLabel()
                    + "；待执行请直接启动，已完成/部分失败/已取消为终态不可恢复）",
                    BizErrorCode.STATE_MACHINE_REJECT);
        }
        int recovered = execTaskService.redistributeTasksOfBatch(batchId);
        batchFinalizeSupport.onTasksTimedOut(java.util.List.of(batchId));
        int running = count("SELECT count(*) FROM tp_exec_task WHERE batch_id = ? AND status = ?",
                batchId, TASK_RUNNING);
        int pending = count("SELECT count(*) FROM tp_exec_task WHERE batch_id = ? AND status = ?",
                batchId, TASK_PENDING);
        int terminal = count("SELECT count(*) FROM tp_exec_task WHERE batch_id = ?"
                + " AND status IN (3, 4, 5, 6, 7)", batchId);
        // Sweep-vs-manual reconciliation: tasks the 15s offline sweep reclaimed before
        // this click and that are still queued (V270 trace). The two reclaim paths race
        // on the same candidate set, so without this the report would say "nothing to
        // reclaim" while the user's dangling tasks quietly sat in the pending queue.
        int sweepReclaimed = execTaskService.countSweepReclaimedOfBatch(batchId);
        BatchInfo after = get(batchId);
        StringBuilder msg = new StringBuilder("崩溃恢复完成：");
        if (recovered > 0) {
            msg.append("回收失联任务 ").append(recovered).append(" 个已重新排队");
        } else {
            msg.append(sweepReclaimed > 0
                    ? "无需本次回收：失联任务 " + sweepReclaimed + " 个已被自动巡检回收并仍在排队等待派发"
                    : "无需回收的失联任务");
        }
        if (running > 0) {
            msg.append("；在跑 ").append(running).append(" 个（执行器在线，等待结果回传，不受影响）");
        }
        msg.append("；已完成结果保留 ").append(terminal).append(" 个")
                .append("；排队等待 ").append(pending).append(" 个");
        if (after.status() == STATUS_DONE || after.status() == STATUS_PARTIAL_FAILED) {
            msg.append("；全部任务已结束，批次已补记为").append(after.statusLabel());
        }
        return new BatchRecoveryView(batchId, after.status(), after.statusLabel(),
                recovered, sweepReclaimed, running, terminal, pending, msg.toString());
    }

    @Override
    @Transactional
    @Audit(objectType = "exec_batch", action = "batch_cancel", objectId = "#id")
    public BatchInfo cancel(long operatorId, long id) {
        return cancel(operatorId, id, lockAndGet(id).version());
    }

    @Override
    @Transactional
    @Audit(objectType = "exec_batch", action = "batch_cancel", objectId = "#id")
    public BatchInfo cancel(long operatorId, long id, int expectedVersion) {
        BatchInfo batch = lockAndGet(id);
        assertOwnerLevel(batch, operatorId, "取消");
        if (batch.status() != STATUS_QUEUED && batch.status() != STATUS_ENV_UNAVAILABLE
                && batch.status() != STATUS_RUNNING && batch.status() != STATUS_PAUSED) {
            throw new ServiceException("仅待执行/环境不可用/执行中/已暂停批次可取消——请在「执行批次」列表选择上述状态的批次（当前状态："
                    + batch.statusLabel() + "）", BizErrorCode.STATE_MACHINE_REJECT);
        }
        assertVersionFresh(batch, expectedVersion);
        int released = jdbcTemplate.update(
                "UPDATE tp_exec_task SET status = ?, finished_at = now(), updated_at = now()"
                        + " WHERE batch_id = ? AND status = ?", TASK_SKIPPED, id, TASK_PENDING);
        jdbcTemplate.update("UPDATE tp_exec_batch SET status = ?, finished_at = now(), "
                + "version = version + 1, updated_at = now() WHERE id = ? AND version = ?",
                STATUS_CANCELLED, id, expectedVersion);
        envExecutionLock.releaseByBatch(id);
 // R62③: cancel lands terminal outside the finalize guard — seal the tail frame
 // so the batch detail tail collapses on a terminal row instead of degrading.
        batchFinalizeSupport.recordCancelledFrame(id, released);
        return withMessage(get(id), "批次已取消（" + released + " 个未派发任务出队，已完成的执行结果保留）");
    }

    @Override
    @Transactional
    @Audit(objectType = "exec_batch", action = "batch_skip_case", objectId = "#id")
    public String skipCase(long operatorId, long id) {
        BatchInfo batch = get(id);
        assertOwnerLevel(batch, operatorId, "跳过用例");
        if (batch.status() != STATUS_RUNNING && batch.status() != STATUS_PAUSED) {
            throw new ServiceException("仅执行中/已暂停批次可跳过当前用例——请在「执行批次」详情页对这两种状态的批次点击「跳过当前用例」（当前状态："
                    + batch.statusLabel() + "）", BizErrorCode.STATE_MACHINE_REJECT);
        }
        List<Map<String, Object>> running = jdbcTemplate.queryForList("""
                SELECT t.id, t.executor_id, c.title FROM tp_exec_task t
                JOIN tp_test_case c ON c.id = t.case_id
                WHERE t.batch_id = ? AND t.status = ? ORDER BY t.id LIMIT 1
                """, id, TASK_RUNNING);
        if (running.isEmpty()) {
            throw new ServiceException("当前没有执行中的用例可跳过（可能刚派发完或已全部结束），"
                    + "请稍后刷新查看进度——请在「执行批次」详情页刷新「执行进度」后重试", BizErrorCode.STATE_MACHINE_REJECT);
        }
        Map<String, Object> task = running.get(0);
        long taskId = ((Number) task.get("id")).longValue();
        int marked = jdbcTemplate.update("UPDATE tp_exec_task SET status = ?, finished_at = now(),"
                + " updated_at = now() WHERE id = ? AND status = ?", TASK_SKIPPED, taskId, TASK_RUNNING);
        if (marked == 0) {
            return "该用例刚好执行完成，跳过未生效，以执行结果为准";
        }
        jdbcTemplate.update("""
                UPDATE tp_batch_case SET manual_skip = 1, result = 3
                WHERE batch_id = ? AND case_id = (SELECT case_id FROM tp_exec_task WHERE id = ?)
                """, id, taskId);
        Object executorId = task.get("executor_id");
        if (executorId != null) {
            jdbcTemplate.update("UPDATE tp_executor SET running_cnt = GREATEST(running_cnt - 1, 0),"
                    + " updated_at = now() WHERE id = ?", ((Number) executorId).longValue());
        }
        batchFinalizeSupport.onCaseSkipped(id);
        return "已跳过用例「" + task.get("title") + "」，下一条继续派发";
    }

    @Override
    @Transactional
    @Audit(objectType = "exec_batch", action = "batch_rerun_failed", objectId = "#id")
    public int rerunFailed(long operatorId, long id) {
        BatchInfo batch = lockAndGet(id);
        assertMemberLevel(batch, operatorId, "重跑失败集");
        if (batch.status() != STATUS_PARTIAL_FAILED && batch.status() != STATUS_DONE) {
            throw new ServiceException("仅部分失败/已完成批次可重跑失败集（当前状态："
                    + batch.statusLabel() + "；执行中批次请先暂停或等待结束，其余状态请在「执行批次」列表选择对应批次操作）",
                    BizErrorCode.STATE_MACHINE_REJECT);
        }
        List<Long> failedTaskIds = jdbcTemplate.query(
                "SELECT id FROM tp_exec_task WHERE batch_id = ? AND status IN (?, ?) ORDER BY id",
                (rs, n) -> rs.getLong("id"), id, TASK_FAILED, TASK_TIMEOUT);
        int created = 0;
        for (long taskId : failedTaskIds) {
            BatchCaseRerunResult r = self.rerunOneInNewTx(taskId, operatorId);
            if (r.created()) {
                created++;
            }
        }
        jdbcTemplate.update("UPDATE tp_exec_batch SET status = ?, finished_at = NULL,"
                + " version = version + 1, updated_at = now() WHERE id = ?", STATUS_QUEUED, id);
        return created;
    }

    
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BatchCaseRerunResult rerunOneInNewTx(long taskId, long operatorId) {
        return execTaskService.rerunBatchCase(taskId, operatorId, null);
    }

    
    @Autowired
    @Lazy
    private BatchServiceImpl self;


    @Override
    @Transactional
    @Audit(objectType = "batch_template", action = "batch_save_template",
            objectId = "#result.id", after = "#result")
    public BatchTemplateInfo saveAsTemplate(long operatorId, long batchId, String templateName) {
        BatchInfo batch = get(batchId);
        if (templateName == null || templateName.isBlank()) {
            throw new ServiceException("模板名称不能为空——请在「执行批次」的「存为模板」弹窗填写「模板名称」后再保存", BizErrorCode.VALIDATION_ERROR);
        }
        List<Long> caseIds = jdbcTemplate.query(
                "SELECT case_id FROM tp_batch_case WHERE batch_id = ? ORDER BY sort_order, id",
                (rs, n) -> rs.getLong("case_id"), batchId);
        if (caseIds.isEmpty()) {
            throw new ServiceException("批次内无用例，无法保存为模板——请在「执行批次」建批表单勾选用例保存后再「存为模板」", BizErrorCode.VALIDATION_ERROR);
        }
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO tp_batch_template (project_id, name, case_ids, created_by, clean_app_data)
                VALUES (?, ?, ?::jsonb, ?, ?) RETURNING id
                """, Long.class, batch.projectId(), templateName.trim(), toJson(caseIds), operatorId,
                batch.cleanAppData());
        return templateRow(id);
    }

    @Override
    public List<BatchTemplateInfo> listTemplates(long projectId) {
        return jdbcTemplate.query("SELECT id FROM tp_batch_template WHERE project_id = ? ORDER BY id DESC",
                (rs, n) -> templateRow(rs.getLong("id")), projectId);
    }

    @Override
    public BatchTemplateInfo getTemplate(long templateId) {
        return templateRow(templateId);
    }

    @Override
    @Transactional
    @Audit(objectType = "exec_batch", action = "batch_from_template", objectId = "#result.id",
            after = "#result")
    public BatchInfo createFromTemplate(long operatorId, BatchFromTemplateInput input) {
        BatchTemplateInfo tpl = templateRow(input.templateId());
        ProjectEnvInfo env = resolveEnvOfProject(input.envId(), tpl.projectId());
        if (env == null) {
            throw new ServiceException("请选择执行环境（模板复用须指定本项目的目标环境）——请在「执行批次」「模板复用建批」弹窗选择「执行环境」（环境可在「项目环境」页维护）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        List<CaseInfo> cases = caseService.listByIds(tpl.caseIds()).stream()
                .filter(c -> c.projectId() == tpl.projectId()).toList();
        int pruned = tpl.caseIds().size() - cases.size();
        if (cases.isEmpty()) {
            throw new ServiceException("模板内用例已全部删除或失效，无法复用建批——请在「执行批次」建批表单从「用例库」重新勾选在用用例，或先在「执行批次」把有效用例「存为模板」",
                    BizErrorCode.VALIDATION_ERROR);
        }
        assertEndTypesMatch(env, cases);
        String name = input.name() == null || input.name().isBlank()
                ? tpl.name() + "（复用）" : input.name().trim();
        String batchNo = "B-" + UUID.randomUUID().toString().substring(0, 8);
        int tplCleanFlag = jdbcTemplate.queryForObject(
                "SELECT clean_app_data FROM tp_batch_template WHERE id = ?",
                Integer.class, tpl.id());
        long batchId = jdbcTemplate.queryForObject("""
                INSERT INTO tp_exec_batch (project_id, env_id, batch_no, name, status,
                                           type, template_id, schedule_type, created_by,
                                           clean_app_data)
                VALUES (?, ?, ?, ?, ?, ?, ?, 0, ?, ?) RETURNING id
                """, Long.class, tpl.projectId(), env.id(), batchNo, name, STATUS_DRAFT,
                TYPE_FORMAL, tpl.id(), operatorId, tplCleanFlag);
        insertBatchCases(batchId, cases);
        BatchInfo batch = get(batchId);
        return pruned > 0 ? withMessage(batch,
                "模板内 " + pruned + " 条用例已删除，复用建批时已自动剔除") : batch;
    }

    
    @Override
    @Transactional
    @Audit(objectType = "batch_template", action = "batch_template_schedule",
            objectId = "#templateId", after = "#result")
    public BatchTemplateInfo updateTemplateSchedule(long operatorId, long templateId,
                                                    TemplateScheduleInput input) {
        BatchTemplateInfo tpl = templateRow(templateId);
        boolean owner = tpl.createdBy() != null && tpl.createdBy() == operatorId
                || ProjectRoleResolver.isOwnerRole(
                        projectAccessService.getMemberRole(tpl.projectId(), operatorId));
        if (!owner) {
            throw new ServiceException("仅模板创建人或项目负责人可配置定时复用——请在「执行批次」模板列表用创建人或负责人账号点击「定时复用」配置", BizErrorCode.FORBIDDEN);
        }
        boolean enable = input.enabled() != null && input.enabled() == 1;
        ScheduleCronTimes.validate(input.cronExpr());
        if (enable) {
            if (input.cronExpr() == null || input.cronExpr().isBlank()) {
                throw new ServiceException("启用定时复用必须填写 cron 表达式——请在「执行批次」模板「定时复用」表单填写「cron 表达式」（6 段格式：秒 分 时 日 月 周，"
                        + "例如 0 30 2 * * * 表示每日 02:30）", BizErrorCode.VALIDATION_ERROR);
            }
            if (input.scheduleEnvId() == null) {
                throw new ServiceException("启用定时复用必须选择目标环境（无人值守执行须预先钉住）——请在「执行批次」模板「定时复用」表单选择「目标环境」（环境可在「项目环境」页维护）",
                        BizErrorCode.VALIDATION_ERROR);
            }
            resolveEnvOfProject(input.scheduleEnvId(), tpl.projectId());
        }
        jdbcTemplate.update("""
                UPDATE tp_batch_template SET cron_expr = ?, enabled = ?, schedule_env_id = ?
                WHERE id = ?
                """, enable ? input.cronExpr().trim() : null,
                enable ? 1 : 0, enable ? input.scheduleEnvId() : null, templateId);
        return templateRow(templateId);
    }

    @Override
    @Transactional
    @Audit(objectType = "batch_template", action = "batch_template_delete", objectId = "#templateId")
    public void deleteTemplate(long operatorId, long templateId) {
        List<long[]> owners = jdbcTemplate.query(
                "SELECT project_id, COALESCE(created_by, 0) AS created_by FROM tp_batch_template "
                        + "WHERE id = ?",
                (rs, n) -> new long[]{rs.getLong("project_id"), rs.getLong("created_by")}, templateId);
        if (owners.isEmpty()) {
            throw new ServiceException("批次模板不存在——请在「执行批次」模板列表刷新确认（可能已被删除）", BizErrorCode.NOT_FOUND);
        }
        boolean owner = owners.get(0)[1] == operatorId
                || ProjectRoleResolver.isOwnerRole(
                        projectAccessService.getMemberRole(owners.get(0)[0], operatorId));
        if (!owner) {
            throw new ServiceException("仅模板创建人或项目负责人可删除模板——请在「执行批次」模板列表用创建人或负责人账号点击删除", BizErrorCode.FORBIDDEN);
        }
        jdbcTemplate.update("DELETE FROM tp_batch_template WHERE id = ?", templateId);
    }

    
    @Override
    @Transactional
    public int sweepWatchdogTimeoutBatches() {
        int timeoutHours = timeoutConfigSupport.batchTimeoutHours();
        List<Long> overdue = jdbcTemplate.query("""
                SELECT id FROM tp_exec_batch
                WHERE status IN (?, ?, ?) AND started_at < now() - make_interval(hours => ?)
                ORDER BY id
                """, (rs, n) -> rs.getLong("id"),
                STATUS_QUEUED, STATUS_RUNNING, STATUS_PAUSED, timeoutHours);
        int swept = 0;
        for (long batchId : overdue) {
            int released = jdbcTemplate.update("UPDATE tp_exec_task SET status = ?, finished_at = now(),"
                            + " updated_at = now() WHERE batch_id = ? AND status = ?",
                    TASK_SKIPPED, batchId, TASK_PENDING);
            int marked = jdbcTemplate.update("UPDATE tp_exec_batch SET status = ?, finished_at = now(),"
                            + " version = version + 1, updated_at = now() WHERE id = ? AND status IN (?, ?, ?)",
                    STATUS_CANCELLED, batchId, STATUS_QUEUED, STATUS_RUNNING, STATUS_PAUSED);
            if (marked > 0) {
                swept++;
                envExecutionLock.releaseByBatch(batchId);
 // R62③: the sweep lands terminal with no other trigger source — seal the tail.
                batchFinalizeSupport.recordWatchdogSealedFrame(batchId, released);
            }
        }
        return swept;
    }


    
    private static void assertEnvOverrides(EnvOverrideInput overrides) {
        if (overrides == null) {
            return;
        }
        overrideField(overrides.url(), "覆盖地址", 512, true);
        overrideField(overrides.accountRef(), "凭据引用", 128, false);
    }

    
    private static void overrideField(String value, String label, int maxLen, boolean requireHttp) {
        if (value == null) {
            return;
        }
        if (value.isBlank()) {
            throw new ServiceException(label + "不能为空串（不需要覆盖请传 null）——请在「执行批次」覆盖项里填写",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (value.length() > maxLen) {
            throw new ServiceException(label + "过长（上限 " + maxLen + " 字符）——请在「执行批次」覆盖项里把该值缩短到 " + maxLen + " 字符以内再提交",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (value.chars().anyMatch(Character::isWhitespace)) {
            throw new ServiceException(label + "含空白字符（空格/换行/制表符），已拒绝——请在「执行批次」覆盖项里删除该值中的空格/换行/制表符后重新填写",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (value.contains("{{") || value.contains("}}") || value.contains("${")) {
            throw new ServiceException(label + "含占位符/表达式语法，已拒绝（防止模板注入）——请在「执行批次」覆盖项里把该值改成真实值（正确写法：不含 {{ 或 ${ 符号）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (requireHttp && !value.regionMatches(true, 0, "http://", 0, 7)
                && !value.regionMatches(true, 0, "https://", 0, 8)) {
            throw new ServiceException(label + "必须以 http:// 或 https:// 开头——请在「执行批次」覆盖项里把该值改为完整链接（示例：https://api.example.com/health）",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    
    private int count(String sql, Object... args) {
        Long n = jdbcTemplate.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n.intValue();
    }

 
    private BatchInfo lockAndGet(long id) {
        List<Integer> locked = jdbcTemplate.query(
                "SELECT status FROM tp_exec_batch WHERE id = ? FOR UPDATE",
                (rs, n) -> rs.getInt(1), id);
        if (locked.isEmpty()) {
            throw new ServiceException("批次不存在——请在「执行批次」刷新列表确认该批次仍在（可能已被删除）", BizErrorCode.NOT_FOUND);
        }
        return get(id);
    }

 
    private void assertOwnerLevel(BatchInfo batch, long operatorId, String action) {
        if (!ProjectRoleResolver.isOwnerRole(
                projectAccessService.getMemberRole(batch.projectId(), operatorId))) {
            throw new ServiceException("仅项目负责人或创建人可" + action
                    + "——请在「执行批次」用项目负责人或创建人账号操作（普通成员可重跑失败集，只读查看进度）", BizErrorCode.FORBIDDEN);
        }
    }

 
    private static void assertVersionFresh(BatchInfo batch, int expectedVersion) {
        if (batch.version() != expectedVersion) {
            throw new OptimisticLockingException("批次已被他人修改，请刷新后重试（当前版本 "
                    + batch.version() + "）");
        }
    }

 
    private void assertMemberLevel(BatchInfo batch, long operatorId, String action) {
        if (!ProjectRoleResolver.isMemberRole(
                projectAccessService.getMemberRole(batch.projectId(), operatorId))) {
            throw new ServiceException("仅项目成员可" + action + "——请在顶栏「项目切换」进入本项目操作；如仍被拒，请在「项目管理」中申请加入该项目后重试", BizErrorCode.FORBIDDEN);
        }
    }

    private static final String BATCH_COLUMNS = """
            SELECT b.id, b.project_id, b.batch_no, b.name, b.env_id, b.status, b.type,
                   b.template_id, b.queue_pos, b.created_by, b.version, b.notify_feishu,
                   b.pool_tag_id,
                   b.browser,
                   b.clean_app_data,
                   COALESCE(e.name, '') AS env_name,
                   to_char(b.created_at, 'YYYY-MM-DD HH24:MI:SS') AS created_at,
                   to_char(b.started_at, 'YYYY-MM-DD HH24:MI:SS') AS started_at,
                   to_char(b.finished_at, 'YYYY-MM-DD HH24:MI:SS') AS finished_at,
                   (SELECT count(*) FROM tp_batch_case bc WHERE bc.batch_id = b.id) AS case_count,
                   (SELECT count(*) FROM tp_batch_runtime_change rc WHERE rc.batch_id = b.id) AS runtime_change_count
            FROM tp_exec_batch b
            LEFT JOIN tp_project_env e ON e.id = b.env_id
            """;

    private BatchInfo mapBatch(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new BatchInfo(rs.getLong("id"), rs.getLong("project_id"),
                rs.getString("batch_no"), rs.getString("name"),
                (Long) rs.getObject("env_id"), rs.getString("env_name"),
                rs.getInt("status"), BatchInfo.statusLabel(rs.getInt("status")),
                rs.getInt("type"), (Long) rs.getObject("template_id"),
                rs.getLong("case_count"), (Integer) rs.getObject("queue_pos"),
                (Long) rs.getObject("created_by"), rs.getString("created_at"),
                rs.getString("started_at"), rs.getString("finished_at"),
                rs.getInt("version"), null, rs.getInt("notify_feishu"),
                (Long) rs.getObject("pool_tag_id"), rs.getString("browser"),
                rs.getInt("runtime_change_count"), null, rs.getInt("clean_app_data"));
    }

    
    private static BatchInfo withMessage(BatchInfo b, String message) {
        return new BatchInfo(b.id(), b.projectId(), b.batchNo(), b.name(), b.envId(), b.envName(),
                b.status(), b.statusLabel(), b.type(), b.templateId(), b.caseCount(), b.queuePos(),
                b.createdBy(), b.createdAt(), b.startedAt(), b.finishedAt(), b.version(), message,
                b.notifyFeishu(), b.poolTagId(), b.browser(), b.runtimeChangeCount(),
                b.envOngoingEpisode(), b.cleanAppData());
    }

    private ProjectEnvInfo batchEnv(long batchId) {
        List<Long> envIds = jdbcTemplate.query(
                "SELECT env_id FROM tp_exec_batch WHERE id = ?",
                (rs, n) -> (Long) rs.getObject("env_id"), batchId);
        Long envId = envIds.isEmpty() ? null : envIds.get(0);
        return envId == null ? null : projectEnvService.getByEnvId(envId);
    }

    
    private void assertPoolTagOfProject(Long poolTagId, long projectId) {
        if (poolTagId == null) {
            return;
        }
        List<com.sixbean.testpike.core.tags.api.dto.TagInfo> tags = jdbcTemplate.query(
                "SELECT id, project_id, name, type FROM tp_tag_dict WHERE id = ?",
                (rs, n) -> new com.sixbean.testpike.core.tags.api.dto.TagInfo(
                        rs.getLong("id"), rs.getLong("project_id"),
                        rs.getString("name"), rs.getInt("type"), 0),
                poolTagId);
        if (tags.isEmpty()) {
            throw new ServiceException("资源池标签不存在（可能已被删除，请重新选择）——请在「执行批次」建批表单重新选择「资源池」（标签可在「用例标签」页维护）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (tags.get(0).type() != 7) {
            throw new ServiceException("批次资源池仅可选择「资源池」类型标签（当前所选为普通标签）——请在「执行批次」建批表单把「资源池」改成资源池类型标签（可在「用例标签」页新建）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (tags.get(0).projectId() != projectId) {
            throw new ServiceException("资源池标签不属于该项目（跨项目引用已拒绝）——请在「执行批次」把「资源池」改为当前项目下的资源池标签",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    
    private ProjectEnvInfo resolveEnvOfProject(Long envId, long projectId) {
        if (envId == null) {
            return null;
        }
        ProjectEnvInfo env = projectEnvService.getByEnvId(envId);
        if (env.projectId() == null || env.projectId() != projectId) {
            throw new ServiceException("执行环境不属于该项目（跨项目引用已拒绝）——请在「执行批次」建批表单把「执行环境」改为当前项目的环境（环境可在「项目环境」页维护）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return env;
    }

    
    private List<CaseInfo> loadOwnedCases(long projectId, List<Long> caseIds) {
        List<Long> distinct = new ArrayList<>(new LinkedHashSet<>(caseIds));
        List<CaseInfo> found = caseService.listByIds(distinct);
        if (found.size() != distinct.size()) {
            throw new ServiceException("存在已删除或不存在的用例，请刷新用例库后重试——请在「执行批次」建批表单刷新用例列表后重新勾选",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (found.stream().anyMatch(c -> c.projectId() != projectId)) {
            throw new ServiceException("存在不属于该项目的用例，已拒绝跨项目引用——请在顶栏「项目切换」确认当前项目，并在「执行批次」重新勾选本项目用例",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return found;
    }

    
    private static void assertEndTypesMatch(ProjectEnvInfo env, List<CaseInfo> cases) {
        if (env.endTypes() == null || env.endTypes().isBlank()) {
            return;
        }
        List<String> supported = List.of(env.endTypes().split(","));
        List<String> bad = cases.stream()
                .filter(c -> !"multi".equals(c.endType()) && !supported.contains(c.endType()))
                .map(CaseInfo::title).toList();
        if (!bad.isEmpty()) {
            String titles = String.join("」「", bad.stream().limit(3).toList());
            throw new ServiceException("用例端属性与环境支持端不匹配：「" + titles
                    + (bad.size() > 3 ? "」等 " + bad.size() + " 条" : "」")
                    + "（环境「" + env.name() + "」支持端：" + env.endTypes()
                    + "），请调整用例集或更换环境", BizErrorCode.VALIDATION_ERROR);
        }
    }

    
    private void insertBatchCases(long batchId, List<CaseInfo> cases) {
        int base = jdbcTemplate.queryForObject(
                "SELECT COALESCE(max(sort_order), -1) FROM tp_batch_case WHERE batch_id = ?",
                Integer.class, batchId);
        int i = 0;
        for (CaseInfo c : cases) {
            jdbcTemplate.update("""
                    INSERT INTO tp_batch_case (batch_id, case_id, case_version, sort_order, result)
                    VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT (batch_id, case_id) DO NOTHING
                    """, batchId, c.id(), c.version(), base + 1 + i++, RESULT_NOT_RUN);
        }
    }

    private BatchTemplateInfo templateRow(long id) {
        List<BatchTemplateInfo> rows = jdbcTemplate.query("""
                SELECT t.id, t.project_id, t.name, t.case_ids::text AS case_ids,
                       t.env_policy, t.created_by,
                       to_char(t.created_at, 'YYYY-MM-DD HH24:MI:SS') AS created_at,
                       t.cron_expr, t.enabled, t.schedule_env_id, t.clean_app_data
                FROM tp_batch_template t WHERE t.id = ?
                """, (rs, n) -> new BatchTemplateInfo(rs.getLong("id"), rs.getLong("project_id"),
                rs.getString("name"), fromJson(rs.getString("case_ids")), rs.getString("env_policy"),
                (Long) rs.getObject("created_by"), rs.getString("created_at"),
                rs.getString("cron_expr"), rs.getInt("enabled"),
                (Long) rs.getObject("schedule_env_id"), rs.getInt("clean_app_data")), id);
        if (rows.isEmpty()) {
            throw new ServiceException("批次模板不存在——请在「执行批次」模板列表刷新确认（可能已被删除）", BizErrorCode.NOT_FOUND);
        }
        return rows.get(0);
    }

    /**
 * <p>api targets stay out of this map: promoted sandbox cases (B11 inline http.request)
 * dispatch through the regular webdriver branch below.
 */
    private Map<Long, com.sixbean.testpike.core.executor.domain.component.AppAutoCaseTarget>
            appAutoTargetsOf(List<Long> caseIds) {
        Map<Long, com.sixbean.testpike.core.executor.domain.component.AppAutoCaseTarget> out =
                new java.util.LinkedHashMap<>();
        if (caseIds == null || caseIds.isEmpty()) {
            return out;
        }
        String placeholders = caseIds.stream().map(x -> "?")
                .collect(java.util.stream.Collectors.joining(","));
        // api targets stay out of the APP branch: a promoted sandbox case carries one
        // http.request step (B11 inline form) and belongs to the regular webdriver task.
        // Sweeping api in here made extractAppSteps return empty (no app.* step) and the
        // batch silently built zero tasks ("not yet orchestrated" skip) — journey blocker.
        jdbcTemplate.query("SELECT id, automation_target, appauto_engine, appauto_env_profile_id"
                        + " FROM tp_test_case WHERE id IN (" + placeholders + ")"
                        + " AND automation_target IS NOT NULL"
                        + " AND automation_target NOT IN ('web', 'api')",
                rs -> {
                    Long profileId = rs.getObject("appauto_env_profile_id") == null
                            ? null : rs.getLong("appauto_env_profile_id");
                    out.put(rs.getLong("id"),
                            new com.sixbean.testpike.core.executor.domain.component.AppAutoCaseTarget(
                                    rs.getString("automation_target"),
                                    rs.getString("appauto_engine"), profileId));
                }, caseIds.toArray());
        return out;
    }

    
    private String appStepSkipPhrase(List<Long> caseIds) {
        if (caseIds == null || caseIds.isEmpty()) {
            return "";
        }
        String placeholders = caseIds.stream().map(x -> "?")
                .collect(java.util.stream.Collectors.joining(","));
        List<String> titles = jdbcTemplate.query(
                "SELECT title FROM tp_test_case WHERE id IN (" + placeholders + ")",
                (rs, n) -> rs.getString(1), caseIds.toArray());
        String joined = titles.stream().limit(3)
                .collect(java.util.stream.Collectors.joining("」「"));
        return "，" + caseIds.size() + " 条 APP/小程序用例未编排 app.* 步骤跳过"
                + "（「" + joined + (titles.size() > 3 ? "」等" : "」")
                + "；请在脚本中编排 App 动作后重新生成）";
    }

    
    private String appQueuePhrase(String queueNote) {
        return queueNote == null ? "" : "；" + queueNote;
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new ServiceException("参数序列化失败——请在「执行批次」重试保存；持续异常请携带批次号反馈", BizErrorCode.INTERNAL_ERROR);
        }
    }

    private List<Long> fromJson(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<Long>>() {
            });
        } catch (Exception e) {
            throw new ServiceException("模板数据解析失败——请在「执行批次」模板列表刷新重试；持续异常请删除该模板后重新「存为模板」", BizErrorCode.INTERNAL_ERROR);
        }
    }

    private static Object[] concat(long batchId, List<Long> ids) {
        Object[] args = new Object[ids.size() + 1];
        args[0] = batchId;
        for (int i = 0; i < ids.size(); i++) {
            args[i + 1] = ids.get(i);
        }
        return args;
    }
}
