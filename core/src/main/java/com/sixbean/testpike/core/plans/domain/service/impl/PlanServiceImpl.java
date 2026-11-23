package com.sixbean.testpike.core.plans.domain.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.audit.domain.component.Audit;
import com.sixbean.testpike.core.batch.domain.component.EnvExecutionLock;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.common.domain.component.SortColumnPolicy;
import com.sixbean.testpike.core.executor.api.dto.ExecTaskCreateInput;
import com.sixbean.testpike.core.executor.domain.service.ExecTaskService;
import com.sixbean.testpike.core.judge.api.dto.CalibrationGroupView;
import com.sixbean.testpike.core.judge.api.dto.RunView;
import com.sixbean.testpike.core.judge.api.dto.ScorecardTrendPointView;
import com.sixbean.testpike.core.judge.domain.service.JudgeCalibrationProfileService;
import com.sixbean.testpike.core.judge.domain.service.JudgeRunService;
import com.sixbean.testpike.core.judge.domain.service.JudgeScorecardService;
import com.sixbean.testpike.core.plans.api.dto.CaseScopeRule;
import com.sixbean.testpike.core.plans.api.dto.CompletionCheckView;
import com.sixbean.testpike.core.plans.api.dto.EntryCriteria;
import com.sixbean.testpike.core.plans.api.dto.ExitCriteria;
import com.sixbean.testpike.core.plans.api.dto.GenerateBatchResult;
import com.sixbean.testpike.core.plans.api.dto.PlanAssignInput;
import com.sixbean.testpike.core.plans.api.dto.PlanCaseInfo;
import com.sixbean.testpike.core.plans.api.dto.PlanCreateInput;
import com.sixbean.testpike.core.plans.api.dto.PlanDeviationInfo;
import com.sixbean.testpike.core.plans.api.dto.PlanInfo;
import com.sixbean.testpike.core.plans.api.dto.PlanProgressView;
import com.sixbean.testpike.core.plans.api.dto.PlanQuery;
import com.sixbean.testpike.core.plans.api.dto.PlanSummaryRow;
import com.sixbean.testpike.core.plans.api.dto.PlanUpdateInput;
import com.sixbean.testpike.core.plans.api.dto.SkipItem;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import com.sixbean.testpike.core.spec.api.dto.InventoryCoverageSummary;
import com.sixbean.testpike.core.spec.domain.service.ApiInventoryQueryService;
import com.sixbean.testpike.core.defects.domain.service.DefectLibraryService;
import com.sixbean.testpike.core.plans.domain.service.PlanService;
import com.sixbean.testpike.core.common.api.dto.DeleteCheckResult;
import com.sixbean.testpike.core.common.domain.component.DeleteGuard;
import com.sixbean.testpike.core.common.domain.component.SqlPlaceholders;
import com.sixbean.testpike.core.project.api.dto.MemberInfo;
import com.sixbean.testpike.core.project.api.dto.ProjectEnvInfo;
import com.sixbean.testpike.core.project.domain.service.EnvHealthService;
import com.sixbean.testpike.core.project.domain.service.MemberService;
import com.sixbean.testpike.core.project.domain.service.ProjectAccessService;
import com.sixbean.testpike.core.tags.domain.component.TagQuerySupport;
import com.sixbean.testpike.core.project.domain.service.ProjectEnvService;
import com.sixbean.testpike.core.script.api.dto.ScriptInfo;
import com.sixbean.testpike.core.script.domain.service.ScriptService;
import com.sixbean.testpike.core.spec.domain.service.ApiSpecChangeAnalysisService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;
import java.util.Map;
import java.util.UUID;
import com.sixbean.testpike.core.audit.domain.component.AuditLogContext;
import com.sixbean.testpike.core.audit.domain.component.AuditSnapshot;


@Service
public class PlanServiceImpl implements PlanService {

    private static final int STATUS_DRAFT = 1;
    private static final int STATUS_PUBLISHED = 2;
    private static final int STATUS_RUNNING = 3;
    private static final int STATUS_COMPLETED = 4;
    private static final int STATUS_CANCELLED = 5;

    private static final int PC_NOT_RUN = 1;
    private static final int PC_PASSED = 3;
    private static final int PC_FAILED = 4;

    private static final int BATCH_CANCELLED = 8;

    private static final List<String> EXECUTOR_ROLES = List.of("tester", "test_lead");

    private static final String CFG_UNEVALUATED_MODE = "testpike.batch.unevaluated-mode";
    // Plan-exit hint slot: when on, completionCheck reports how many contract
    // change-analysis rows in this project still await review (hint only, never blocks).
    private static final String CFG_SPEC_EXIT_REVIEW_WARN = "api-spec.exit.change-review.warn";
    // Plan-exit contract-coverage gate (W-c5 closing): floor percent 0~100,
    // 0/absent/unparseable = gate off (existing verdicts stay byte-identical).
    private static final String CFG_APITEST_MIN_COVERAGE = "plan.exit.apitest.min-coverage";
    
    private static final int CASE_APPROVED = 3;
    
    private static final String FLAKY_SKIP_REASON = "已隔离（不稳定）";

    private final JdbcTemplate jdbcTemplate;
    private final ProjectAccessService projectAccessService;
    private final ProjectEnvService projectEnvService;
    private final EnvHealthService envHealthService;
    private final MemberService memberService;
    private final CaseService caseService;
    private final ScriptService scriptService;
    private final ExecTaskService execTaskService;
    private final DefectLibraryService defectLibraryService;
    private final ObjectMapper objectMapper;
    private final DeleteGuard deleteGuard;
    private final ConfigCacheHolder configCacheHolder;
    private final EnvExecutionLock envExecutionLock;
    private final JudgeScorecardService judgeScorecardService;
    private final JudgeCalibrationProfileService judgeCalibrationProfileService;
    private final JudgeRunService judgeRunService;
    private final ApiSpecChangeAnalysisService changeAnalysisService;
    private final ApiInventoryQueryService inventoryQueryService;

    public PlanServiceImpl(JdbcTemplate jdbcTemplate, ProjectAccessService projectAccessService,
                           ProjectEnvService projectEnvService, EnvHealthService envHealthService,
                           MemberService memberService,
                           CaseService caseService, ScriptService scriptService,
                           ExecTaskService execTaskService, DefectLibraryService defectLibraryService,
                           ObjectMapper objectMapper, DeleteGuard deleteGuard,
                           ConfigCacheHolder configCacheHolder,
                           EnvExecutionLock envExecutionLock,
                           JudgeScorecardService judgeScorecardService,
                           JudgeCalibrationProfileService judgeCalibrationProfileService,
                           JudgeRunService judgeRunService,
                           ApiSpecChangeAnalysisService changeAnalysisService,
                           ApiInventoryQueryService inventoryQueryService) {
        this.jdbcTemplate = jdbcTemplate;
        this.projectAccessService = projectAccessService;
        this.projectEnvService = projectEnvService;
        this.envHealthService = envHealthService;
        this.memberService = memberService;
        this.caseService = caseService;
        this.scriptService = scriptService;
        this.execTaskService = execTaskService;
        this.defectLibraryService = defectLibraryService;
        this.deleteGuard = deleteGuard;
        this.objectMapper = objectMapper;
        this.configCacheHolder = configCacheHolder;
        this.envExecutionLock = envExecutionLock;
        this.judgeScorecardService = judgeScorecardService;
        this.judgeCalibrationProfileService = judgeCalibrationProfileService;
        this.judgeRunService = judgeRunService;
        this.changeAnalysisService = changeAnalysisService;
        this.inventoryQueryService = inventoryQueryService;
    }

    @Override
    @Transactional
    @Audit(objectType = "exec_plan", action = "plan_create", objectId = "#result.id", after = "#result")
    public PlanInfo create(long operatorId, PlanCreateInput input) {
        if (input.name() == null || input.name().isBlank()) {
            throw new ServiceException("计划名称不能为空——请在「新建计划」向导第 1 步「基本信息」填写「计划名称」后保存", BizErrorCode.VALIDATION_ERROR);
        }
        if (input.name().length() > 100) {
            throw new ServiceException("计划名称长度不能超过 100 个字符——请在「新建计划」向导第 1 步「基本信息」把「计划名称」缩短到 100 个字符以内再保存", BizErrorCode.VALIDATION_ERROR);
        }
        if (input.envIds() == null || input.envIds().isEmpty()) {
            throw new ServiceException("请选择执行环境集合（生成批次将按环境分批执行）——请在「新建计划」向导第 2 步「范围与环境」的「执行环境」中至少勾选 1 个环境",
                    BizErrorCode.VALIDATION_ERROR);
        }
        CaseScopeRule scope = input.caseScope() == null
                ? CaseScopeRule.of(List.of(), List.of(), null, List.of()) : input.caseScope();
        if (scope.isEmptyRule()) {
            throw new ServiceException("请至少指定一个范围维度（阶段/优先级/关键词/标签），防止误拉全量用例——请在「新建计划」向导第 2 步「范围与环境」为「用例阶段」「用例优先级」「标题关键词」「用例标签」至少配置一项",
                    BizErrorCode.VALIDATION_ERROR);
        }
        validateEnvs(input.projectId(), input.envIds());
        int lockMode = input.lockMode() == null ? 1 : input.lockMode();
        if (lockMode != 1 && lockMode != 2) {
            throw new ServiceException("锁定模式仅支持 1=宽松/2=严格——请在「新建计划」向导第 3 步「准入准出」把「锁定模式」选为「宽松」或「严格」", BizErrorCode.VALIDATION_ERROR);
        }

        long planId = jdbcTemplate.queryForObject("""
                INSERT INTO tp_exec_plan (project_id, name, target_version, status, owner_id,
                                          env_ids_json, case_scope_json, entry_criteria_json,
                                          exit_criteria_json, plan_start, plan_end, created_by,
                                          lock_mode)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, input.projectId(), input.name().trim(),
                trimToNull(input.targetVersion()), STATUS_DRAFT,
                input.ownerId() == null ? operatorId : input.ownerId(),
                toJson(input.envIds()), toJson(scope),
                toJson(input.entryCriteria() == null ? EntryCriteria.of(true) : input.entryCriteria()),
                toJson(input.exitCriteria() == null ? ExitCriteria.of(null, null) : input.exitCriteria()),
                trimToNull(input.planStart()), trimToNull(input.planEnd()), operatorId, lockMode);

        pullCases(planId, input.projectId(), scope);
        return get(planId);
    }

    @Override
    @Transactional
    @Audit(objectType = "exec_plan", action = "plan_update", objectId = "#id", after = "#result")
    public PlanInfo update(long operatorId, long id, PlanUpdateInput input) {
        PlanInfo plan = get(id);
        AuditLogContext.setBefore(AuditSnapshot.snapshot(plan));
        requireDraft(plan, "编辑");
        if (input.name() == null || input.name().isBlank()) {
            throw new ServiceException("计划名称不能为空——请在「新建计划」向导第 1 步「基本信息」填写「计划名称」后保存", BizErrorCode.VALIDATION_ERROR);
        }
        if (input.envIds() == null || input.envIds().isEmpty()) {
            throw new ServiceException("请选择执行环境集合（生成批次将按环境分批执行）——请在「新建计划」向导第 2 步「范围与环境」的「执行环境」中至少勾选 1 个环境",
                    BizErrorCode.VALIDATION_ERROR);
        }
        CaseScopeRule scope = input.caseScope() == null
                ? CaseScopeRule.of(List.of(), List.of(), null, List.of()) : input.caseScope();
        if (scope.isEmptyRule()) {
            throw new ServiceException("请至少指定一个范围维度（阶段/优先级/关键词/标签），防止误拉全量用例——请在「新建计划」向导第 2 步「范围与环境」为「用例阶段」「用例优先级」「标题关键词」「用例标签」至少配置一项",
                    BizErrorCode.VALIDATION_ERROR);
        }
        validateEnvs(plan.projectId(), input.envIds());

        jdbcTemplate.update("""
                UPDATE tp_exec_plan SET name = ?, target_version = ?, owner_id = ?,
                       env_ids_json = ?, case_scope_json = ?, entry_criteria_json = ?,
                       exit_criteria_json = ?, plan_start = ?, plan_end = ?,
                       lock_mode = COALESCE(?, lock_mode), updated_at = now()
                WHERE id = ?
                """, input.name().trim(), trimToNull(input.targetVersion()),
                input.ownerId() == null ? operatorId : input.ownerId(),
                toJson(input.envIds()), toJson(scope),
                toJson(input.entryCriteria() == null ? EntryCriteria.of(true) : input.entryCriteria()),
                toJson(input.exitCriteria() == null ? ExitCriteria.of(null, null) : input.exitCriteria()),
                trimToNull(input.planStart()), trimToNull(input.planEnd()),
                input.lockMode(), id);

        jdbcTemplate.update("DELETE FROM tp_plan_case WHERE plan_id = ?", id);
        pullCases(id, plan.projectId(), scope);
        return get(id);
    }

    @Override
    public PlanInfo get(long id) {
        List<PlanInfo> rows = jdbcTemplate.query("""
                SELECT p.*, to_char(p.created_at, 'YYYY-MM-DD HH24:MI:SS') AS created_at,
                       (SELECT count(*) FROM tp_plan_case pc WHERE pc.plan_id = p.id) AS case_count,
                       (SELECT count(*) FROM tp_plan_case pc WHERE pc.plan_id = p.id AND pc.assignee_id IS NOT NULL) AS assignee_count
                FROM tp_exec_plan p WHERE p.id = ?
                """, (rs, rowNum) -> toPlanInfo(rs), id);
        if (rows.isEmpty()) {
            throw new ServiceException("执行计划不存在——请返回「执行计划」列表刷新后重新打开该计划；已删除的计划不再可见", BizErrorCode.NOT_FOUND);
        }
        return rows.get(0);
    }

    /** Server-side sort whitelist (plan list; frontend column props map onto these
     * keys via SORT_KEY_MAP). Values are physical columns or select aliases —
     * case_count/assignee_count are the subquery aliases from the list SELECT, never user input. */
    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "name", "p.name",
            "target_version", "p.target_version",
            "status", "p.status",
            "case_count", "case_count",
            "assignee_count", "assignee_count",
            "created_at", "p.created_at");

    @Override
    public PageResult<PlanInfo> list(PlanQuery query) {
        int page = Math.max(1, query.page());
        int size = Math.min(200, Math.max(1, query.size() == 0 ? 20 : query.size()));
 // Sort keys must resolve to a physical column (or select alias) through the
 // shared whitelist — user input never reaches the SQL string (SortColumnPolicy.require,
 // same posture as the batch/mcp/skill/tool domains; off-list key = fail-closed 400).
        String sortColumn = SortColumnPolicy.require(query.sortBy(), SORT_COLUMNS, "计划");
        String order = sortColumn == null
                ? " ORDER BY p.id DESC"
                : " ORDER BY " + sortColumn
                        + ("asc".equalsIgnoreCase(query.sortDir()) ? " ASC" : " DESC") + ", p.id DESC";
        StringBuilder where = new StringBuilder(" WHERE p.project_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(query.projectId());
        if (query.status() != null) {
            where.append(" AND p.status = ?");
            args.add(query.status());
        }
        if (query.keyword() != null && !query.keyword().isBlank()) {
            where.append(" AND p.name ILIKE ?");
            args.add("%" + query.keyword().trim() + "%");
        }
        long total = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_exec_plan p" + where, Long.class, args.toArray());
        args.add(size);
        args.add((page - 1) * size);
        List<PlanInfo> list = jdbcTemplate.query("""
                SELECT p.*, to_char(p.created_at, 'YYYY-MM-DD HH24:MI:SS') AS created_at,
                       (SELECT count(*) FROM tp_plan_case pc WHERE pc.plan_id = p.id) AS case_count,
                       (SELECT count(*) FROM tp_plan_case pc WHERE pc.plan_id = p.id AND pc.assignee_id IS NOT NULL) AS assignee_count
                FROM tp_exec_plan p""" + where + order + " LIMIT ? OFFSET ?",
                (rs, rowNum) -> toPlanInfo(rs), args.toArray());
        return new PageResult<>(list, total);
    }

    @Override
    public List<PlanCaseInfo> listCases(long planId, Integer status) {
        StringBuilder sql = new StringBuilder("""
                SELECT pc.id, pc.plan_id, pc.case_id, pc.assignee_id, pc.status, pc.batch_case_ref,
                       c.case_no, c.title, c.phase, COALESCE(u.display_name, '') AS assignee_name
                FROM tp_plan_case pc
                JOIN tp_test_case c ON c.id = pc.case_id
                LEFT JOIN tp_user u ON u.id = pc.assignee_id
                WHERE pc.plan_id = ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(planId);
        if (status != null) {
            sql.append(" AND pc.status = ?");
            args.add(status);
        }
        sql.append(" ORDER BY pc.id");
        return jdbcTemplate.query(sql.toString(),
                (rs, rowNum) -> new PlanCaseInfo(rs.getLong("id"), rs.getLong("plan_id"),
                        rs.getLong("case_id"), rs.getString("case_no"), rs.getString("title"),
                        (Integer) rs.getObject("phase"), (Long) rs.getObject("assignee_id"),
                        rs.getString("assignee_name"), rs.getInt("status"),
                        PlanCaseInfo.statusLabel(rs.getInt("status")),
                        (Long) rs.getObject("batch_case_ref")), args.toArray());
    }

    @Override
    @Transactional
    @Audit(objectType = "exec_plan", action = "plan_assign", objectId = "#planId", after = "#result")
    public int assignCases(long operatorId, long planId, PlanAssignInput input) {
        PlanInfo plan = get(planId);
        requireDraft(plan, "分配执行人");
        if (input.caseIds() == null || input.caseIds().isEmpty()) {
            throw new ServiceException("请选择要分配的用例——请在计划详情「用例明细」点「分配执行人」，在弹窗列表中先勾选要分配的用例", BizErrorCode.VALIDATION_ERROR);
        }
        if (input.assigneeId() == null) {
            throw new ServiceException("请选择执行人——请在「分配执行人」弹窗的「执行人」下拉选择项目内测试人员后再确认", BizErrorCode.VALIDATION_ERROR);
        }
        MemberInfo member = memberService.list(plan.projectId()).stream()
                .filter(m -> input.assigneeId().equals(m.userId())).findFirst().orElse(null);
        boolean executable = member != null
                && member.roles().stream().anyMatch(EXECUTOR_ROLES::contains);
        if (!executable) {
            throw new ServiceException("执行人必须属该项目且为测试人员/测试负责人（40301）——请先在「项目管理」的「成员」中把该成员加为「测试人员」或「测试负责人」后重新分配",
                    BizErrorCode.FORBIDDEN);
        }
        String placeholders = String.join(",", input.caseIds().stream().map(x -> "?").toList());
        Long matched = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_plan_case WHERE plan_id = ? AND case_id IN (" + placeholders + ")",
                Long.class, concat(List.of(planId), input.caseIds()));
        if (matched == null || matched != input.caseIds().size()) {
            throw new ServiceException("存在不属于该计划的用例，分配失败——请在「分配执行人」弹窗列表（仅含本计划用例）内勾选；确需执行计划外用例请先在计划详情「登记偏差」（「偏差类型」选「计划外用例执行」）后再执行", BizErrorCode.VALIDATION_ERROR);
        }
        return jdbcTemplate.update(
                "UPDATE tp_plan_case SET assignee_id = ? WHERE plan_id = ? AND case_id IN (" + placeholders + ")",
                concat(List.of(input.assigneeId(), planId), input.caseIds()));
    }

    @Override
    @Transactional
    @Audit(objectType = "exec_plan", action = "plan_publish", objectId = "#id", after = "#result")
    public PlanInfo publish(long operatorId, long id) {
        PlanInfo plan = get(id);
        requireDraft(plan, "发布");
        Long caseCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_plan_case WHERE plan_id = ?", Long.class, id);
        if (caseCount == null || caseCount == 0) {
            throw new ServiceException("计划内无用例，请先调整范围规则再发布——请在「新建计划」向导第 2 步「范围与环境」放宽「用例阶段」「用例优先级」等范围维度后重新创建计划",
                    BizErrorCode.VALIDATION_ERROR);
        }
        Long unassigned = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_plan_case WHERE plan_id = ? AND assignee_id IS NULL",
                Long.class, id);
        if (unassigned != null && unassigned > 0) {
            throw new ServiceException("还有 " + unassigned + " 条用例未分配执行人，请先完成人员分配——请在计划详情「用例明细」勾选未分配用例后点「分配执行人」",
                    BizErrorCode.VALIDATION_ERROR);
        }
        jdbcTemplate.update("UPDATE tp_exec_plan SET status = ?, updated_at = now() WHERE id = ?",
                STATUS_PUBLISHED, id);
        return get(id);
    }

    @Override
    @Transactional
    @Audit(objectType = "exec_plan", action = "plan_generate_batches", objectId = "#id", after = "#result")
    public GenerateBatchResult generateBatches(long operatorId, long id) {
        PlanInfo plan = get(id);
        if (plan.status() != STATUS_PUBLISHED && plan.status() != STATUS_RUNNING) {
            throw new ServiceException("仅已发布/执行中的计划可生成批次——草稿计划请先在「执行计划」列表该行点「发布」；已取消/已完成的计划不可生成批次", BizErrorCode.STATE_MACHINE_REJECT);
        }
        if (plan.entryCriteria() != null && plan.entryCriteria().requireSmokePass()) {
            List<String> smokeFailed = jdbcTemplate.query("""
                    SELECT c.title FROM tp_plan_case pc
                    JOIN tp_test_case c ON c.id = pc.case_id
                    WHERE pc.plan_id = ? AND c.phase = 1
                      AND (c.last_exec_result IS NULL OR c.last_exec_result <> 1) AND c.deleted = 0
                    ORDER BY c.id LIMIT 5
                    """, (rs, rowNum) -> rs.getString("title"), id);
            if (!smokeFailed.isEmpty()) {
                throw new ServiceException("准入未过：冒烟用例「" + String.join("」「", smokeFailed)
                        + "」最近执行未通过，请先执行通过后再生成批次——可在「用例管理」页逐条执行上述冒烟用例至通过，或在「新建计划」向导第 3 步「准入准出」取消「准入：冒烟用例最近执行须全部通过」后重建计划",
                        BizErrorCode.STATE_MACHINE_REJECT);
            }
        }
        int batchCount = 0;
        int taskCount = 0;
        int skippedNoScript = 0;
        List<Long> batchIds = new ArrayList<>();
        for (Long envId : plan.envIds()) {
            ProjectEnvInfo env = projectEnvService.get(plan.projectId(), envId);
            String batchNo = "PLAN-" + id + "-" + envId + "-" + UUID.randomUUID().toString().substring(0, 6);
            long batchId = jdbcTemplate.queryForObject("""
                    INSERT INTO tp_exec_batch (project_id, env_id, batch_no, name, status,
                                               type, schedule_type, plan_id, created_by)
                    VALUES (?, ?, ?, ?, ?, 1, 0, ?, ?) RETURNING id
                    """, Long.class, plan.projectId(), envId, batchNo,
                    plan.name() + "-" + env.name(), 2, id, operatorId);
            batchIds.add(batchId);
            batchCount++;
        }
        List<Long> pendingCaseIds = jdbcTemplate.query(
                "SELECT pc.case_id FROM tp_plan_case pc WHERE pc.plan_id = ? AND pc.batch_case_ref IS NULL "
                        + "AND pc.status = ? ORDER BY pc.case_id",
                (rs, rowNum) -> rs.getLong("case_id"), id, PC_NOT_RUN);
        List<SkipItem> skippedCases = new ArrayList<>();
        List<SkipItem> unevaluatedSkips = new ArrayList<>();
        List<SkipItem> flakySkips = new ArrayList<>();
        List<Long> approvedCaseIds = new ArrayList<>();
        if (!pendingCaseIds.isEmpty()) {
            jdbcTemplate.query("""
                    SELECT c.id, c.case_no, c.title, c.status, c.flaky FROM tp_test_case c
                    WHERE c.id IN (%s) AND c.deleted = 0
                    """.formatted(pendingCaseIds.stream().map(String::valueOf)
                            .collect(Collectors.joining(","))),
                    rs -> {
                        if (rs.getInt("flaky") == 1) {
                            skippedCases.add(new SkipItem(rs.getLong("id"), rs.getString("case_no"),
                                    rs.getString("title"), rs.getInt("status"), "已隔离（不稳定）"));
                        } else if (rs.getInt("status") == CASE_APPROVED) {
                            approvedCaseIds.add(rs.getLong("id"));
                        } else {
                            skippedCases.add(new SkipItem(rs.getLong("id"), rs.getString("case_no"),
                                    rs.getString("title"), rs.getInt("status")));
                        }
                    });
            unevaluatedSkips = skippedCases.stream()
                    .filter(s -> !FLAKY_SKIP_REASON.equals(s.reason())).toList();
            flakySkips = skippedCases.stream()
                    .filter(s -> FLAKY_SKIP_REASON.equals(s.reason())).toList();
            if (!unevaluatedSkips.isEmpty()) {
                String mode = configCacheHolder.get(CFG_UNEVALUATED_MODE);
                if ("block".equalsIgnoreCase(mode)) {
                    String titles = unevaluatedSkips.stream().limit(3)
                            .map(SkipItem::title).collect(Collectors.joining("」「"));
                    throw new ServiceException("存在 " + unevaluatedSkips.size()
                            + " 条未通过评审用例（「" + titles + (unevaluatedSkips.size() > 3 ? "」等" : "」")
                            + "），已配置为阻止模式——请先在「评审工作台」完成上述用例评审，或在「系统设置」把未通过评审用例处理模式（参数 testpike.batch.unevaluated-mode）调整为 skip=跳过执行后重试",
                            BizErrorCode.VALIDATION_ERROR);
                }
            }
            if (!skippedCases.isEmpty()) {
                String skipJson;
                try {
                    skipJson = objectMapper.writeValueAsString(skippedCases);
                } catch (Exception e) {
                    throw new ServiceException("跳过清单序列化失败——请重新点「生成批次」重试；持续失败时请携带「执行计划」名称联系管理员排查批次记录", BizErrorCode.VALIDATION_ERROR);
                }
                for (Long batchId : batchIds) {
                    jdbcTemplate.update("UPDATE tp_exec_batch SET skip_cases_json = ? WHERE id = ?",
                            skipJson, batchId);
                }
            }
        }
        int envIdx = 0;
        for (Long caseId : approvedCaseIds) {
            ScriptInfo script = scriptService.getByCaseId(caseId);
            if (script == null || script.actionJson() == null || script.actionJson().isBlank()) {
                skippedNoScript++;
                continue;
            }
            long envId = plan.envIds().get(envIdx % batchIds.size());
            long batchId = batchIds.get(envIdx % batchIds.size());
            long taskId = execTaskService.create(new ExecTaskCreateInput(
                    plan.projectId(), caseId, script.schemaVersion(), script.actionJson(), envId, batchId));
            jdbcTemplate.update("UPDATE tp_plan_case SET batch_case_ref = ? WHERE plan_id = ? AND case_id = ?",
                    taskId, id, caseId);
            taskCount++;
            envIdx++;
        }
        jdbcTemplate.update("UPDATE tp_exec_plan SET status = ?, updated_at = now() WHERE id = ?",
                STATUS_RUNNING, id);
        String message = "已按 " + plan.envIds().size() + " 个环境生成 " + batchCount + " 个批次，共 "
                + taskCount + " 个执行任务"
                + (skippedNoScript > 0 ? "，" + skippedNoScript + " 条用例无脚本跳过（可补脚本后重新生成）" : "")
                + skipPhrase(unevaluatedSkips, "未通过评审用例")
                + skipPhrase(flakySkips, "不稳定隔离用例");
        List<String> envWarnings = new ArrayList<>();
        for (Long envId : plan.envIds()) {
            String warning = envHealthService.envHealthWarning(envId);
            if (warning != null && !envWarnings.contains(warning)) {
                envWarnings.add(warning);
            }
        }
        return new GenerateBatchResult(id, batchCount, taskCount, skippedNoScript,
                skippedCases.size(), skippedCases, message,
                envWarnings.isEmpty() ? null : String.join("；", envWarnings));
    }

    
    private static String skipPhrase(List<SkipItem> items, String label) {
        if (items == null || items.isEmpty()) {
            return "";
        }
        String titles = items.stream().limit(3).map(SkipItem::title)
                .collect(Collectors.joining("」「"));
        return "，跳过 " + items.size() + " 条" + label + "（" + titles
                + (items.size() > 3 ? "」等" : "」") + "）";
    }

    @Override
    public PlanProgressView progress(long id) {
        Map<Integer, Integer> byStatus = new HashMap<>();
        jdbcTemplate.query("SELECT status, count(*) FROM tp_plan_case WHERE plan_id = ? GROUP BY status",
                (rs, rowNum) -> {
                    byStatus.put(rs.getInt("status"), rs.getInt("count"));
                    return null;
                }, id);
        int notRun = byStatus.getOrDefault(1, 0);
        int running = byStatus.getOrDefault(2, 0);
        int passed = byStatus.getOrDefault(3, 0);
        int failed = byStatus.getOrDefault(4, 0);
        int skipped = byStatus.getOrDefault(5, 0);
        int total = notRun + running + passed + failed + skipped;
        double passRate = (passed + failed) == 0 ? 0 : (double) passed / (passed + failed);

        List<PlanProgressView.DimRow> byAssignee = jdbcTemplate.query("""
                SELECT COALESCE(pc.assignee_id, 0) AS key_id, COALESCE(u.display_name, '未分配') AS label,
                       count(*) AS total,
                       sum(CASE WHEN pc.status = 3 THEN 1 ELSE 0 END) AS passed,
                       sum(CASE WHEN pc.status = 4 THEN 1 ELSE 0 END) AS failed,
                       sum(CASE WHEN pc.status = 1 THEN 1 ELSE 0 END) AS not_run,
                       sum(CASE WHEN pc.status = 2 THEN 1 ELSE 0 END) AS running,
                       sum(CASE WHEN pc.status = 5 THEN 1 ELSE 0 END) AS skipped
                FROM tp_plan_case pc
                LEFT JOIN tp_user u ON u.id = pc.assignee_id
                WHERE pc.plan_id = ?
                GROUP BY pc.assignee_id, u.display_name ORDER BY key_id
                """, (rs, rowNum) -> dimRow(rs), id);

        List<PlanProgressView.DimRow> byPhase = jdbcTemplate.query("""
                SELECT COALESCE(c.phase, 0) AS key_id, '' AS label,
                       count(*) AS total,
                       sum(CASE WHEN pc.status = 3 THEN 1 ELSE 0 END) AS passed,
                       sum(CASE WHEN pc.status = 4 THEN 1 ELSE 0 END) AS failed,
                       sum(CASE WHEN pc.status = 1 THEN 1 ELSE 0 END) AS not_run,
                       sum(CASE WHEN pc.status = 2 THEN 1 ELSE 0 END) AS running,
                       sum(CASE WHEN pc.status = 5 THEN 1 ELSE 0 END) AS skipped
                FROM tp_plan_case pc
                JOIN tp_test_case c ON c.id = pc.case_id
                WHERE pc.plan_id = ?
                GROUP BY c.phase ORDER BY key_id
                """, (rs, rowNum) -> {
            PlanProgressView.DimRow row = dimRow(rs);
            return new PlanProgressView.DimRow(row.key(), phaseLabel(rs.getInt("key_id")), row.total(),
                    row.passed(), row.failed(), row.notRun(), row.running(), row.skipped());
        }, id);

        List<PlanProgressView.DimRow> byEnv = jdbcTemplate.query("""
                SELECT COALESCE(b.env_id, 0) AS key_id, COALESCE(e.name, '未生成批次') AS label,
                       count(*) AS total,
                       sum(CASE WHEN pc.status = 3 THEN 1 ELSE 0 END) AS passed,
                       sum(CASE WHEN pc.status = 4 THEN 1 ELSE 0 END) AS failed,
                       sum(CASE WHEN pc.status = 1 THEN 1 ELSE 0 END) AS not_run,
                       sum(CASE WHEN pc.status = 2 THEN 1 ELSE 0 END) AS running,
                       sum(CASE WHEN pc.status = 5 THEN 1 ELSE 0 END) AS skipped
                FROM tp_plan_case pc
                LEFT JOIN tp_exec_task t ON t.id = pc.batch_case_ref
                LEFT JOIN tp_exec_batch b ON b.id = t.batch_id
                LEFT JOIN tp_project_env e ON e.id = b.env_id
                WHERE pc.plan_id = ?
                GROUP BY b.env_id, e.name ORDER BY key_id
                """, (rs, rowNum) -> dimRow(rs), id);

        return new PlanProgressView(total, notRun, running, passed, failed, skipped,
                round4(passRate), byAssignee, byPhase, byEnv);
    }

    @Override
    public List<PlanSummaryRow> progressSummary(long projectId) {
        return jdbcTemplate.query("""
                SELECT p.id, p.name, p.status, p.plan_start, p.plan_end,
                       count(pc.id) AS case_count,
                       sum(CASE WHEN pc.status = 3 THEN 1 ELSE 0 END) AS passed,
                       sum(CASE WHEN pc.status = 4 THEN 1 ELSE 0 END) AS failed,
                       sum(CASE WHEN pc.status = 1 THEN 1 ELSE 0 END) AS not_run,
                       sum(CASE WHEN pc.status = 2 THEN 1 ELSE 0 END) AS running,
                       sum(CASE WHEN pc.status = 5 THEN 1 ELSE 0 END) AS skipped
                FROM tp_exec_plan p
                LEFT JOIN tp_plan_case pc ON pc.plan_id = p.id
                WHERE p.project_id = ?
                GROUP BY p.id, p.name, p.status, p.plan_start, p.plan_end
                ORDER BY p.created_at DESC
                """, (rs, rowNum) -> {
            long passed = rs.getLong("passed");
            long failed = rs.getLong("failed");
            double passRate = (passed + failed) == 0 ? 0 : (double) passed / (passed + failed);
            return new PlanSummaryRow(rs.getLong("id"), rs.getString("name"), rs.getInt("status"),
                    PlanInfo.statusLabel(rs.getInt("status")), rs.getLong("case_count"), passed, failed,
                    rs.getLong("not_run"), rs.getLong("running"), rs.getLong("skipped"),
                    round4(passRate), rs.getString("plan_start"), rs.getString("plan_end"));
        }, projectId);
    }

    @Override
    @Transactional
    @Audit(objectType = "exec_plan", action = "plan_completion_check", objectId = "#id")
    public CompletionCheckView completionCheck(long operatorId, long id) {
        PlanInfo plan = get(id);
        PlanProgressView p = progress(id);
        ExitCriteria exit = plan.exitCriteria() == null ? ExitCriteria.of(null, null) : plan.exitCriteria();
        double minPassRate = exit.minPassRate();
        long openDefects = 0;
        long p0OpenDefects = 0;
        if (exit.noOpenDefects()) {
            Long ext = jdbcTemplate.queryForObject("""
                    SELECT count(*) FROM tp_case_defect d
                    JOIN tp_plan_case pc ON pc.case_id = d.case_id
                    WHERE pc.plan_id = ? AND d.defect_status <> 'closed'
                    """, Long.class, id);
            openDefects = (ext == null ? 0 : ext) + defectLibraryService.countOpenByPlan(id, false);
            p0OpenDefects = defectLibraryService.countOpenByPlan(id, true);
        }
        boolean rateOk = p.passed() + p.failed() == 0 ? false : p.passRate() >= minPassRate;
        boolean defectOk = !exit.noOpenDefects() || openDefects == 0;
        boolean allDone = p.notRun() == 0 && p.running() == 0;
        String evalGateBlock = evalGateMessage(exit);
        boolean evalOk = evalGateBlock == null;
        // Contract-coverage exit gate (AH closing key plan.exit.apitest.min-coverage):
        // floor percent from K8 cache, 0/absent/unparseable = off so every existing
        // plan's verdict stays byte-identical. When on, the measurement reuses the
        // inventory read path (one coverage spec, never a second denominator).
        int minApitestCoverage = parseCoverageFloor(configCacheHolder.get(CFG_APITEST_MIN_COVERAGE));
        int apitestCoveragePercent = -1;
        String coverageBlock = null;
        if (minApitestCoverage > 0) {
            InventoryCoverageSummary cov = inventoryQueryService.projectCoverageSummary(plan.projectId());
            apitestCoveragePercent = cov.coveragePercent();
            if (cov.total() == 0) {
                coverageBlock = "接口契约覆盖率门禁无法实测：本项目尚无接口契约（覆盖率分母为空），"
                        + "请先上传契约文档并维护接口清单后再完成计划";
            } else if (apitestCoveragePercent < minApitestCoverage) {
                coverageBlock = "接口契约端点覆盖率 " + apitestCoveragePercent + "% 低于准出下限 "
                        + minApitestCoverage + "%（" + cov.total() + " 个契约端点中 "
                        + cov.endpointsWithRequests() + " 个已建接口请求，"
                        + cov.uncoveredCount() + " 个未覆盖），请先补齐未覆盖端点后再完成计划";
            }
        }
        boolean coverageOk = coverageBlock == null;
        boolean passed = rateOk && defectOk && allDone && evalOk && coverageOk;
        String message;
        if (!rateOk) {
            message = String.format("通过率 %.1f%% 低于准出阈值 %.1f%%，暂不可完成（还需 %d 条用例通过）",
                    p.passRate() * 100, minPassRate * 100,
                    (long) Math.ceil(p.failed() * minPassRate / (1 - minPassRate) - p.passed()));
        } else if (!defectOk) {
            message = "存在 " + openDefects + " 条未关闭缺陷（其中 P0 " + p0OpenDefects
                    + " 条），请先关闭后再完成计划";
        } else if (!allDone) {
            message = "还有 " + p.notRun() + " 条用例未执行、" + p.running() + " 条执行中，暂不可完成";
        } else if (!evalOk) {
            message = evalGateBlock;
        } else if (!coverageOk) {
            message = coverageBlock;
        } else {
            message = "准出达标（通过率 " + String.format("%.1f%%", p.passRate() * 100)
                    + (exit.evalGateEnabled() ? "，AI 评测门禁达标" : "")
                    + (minApitestCoverage > 0 ? "，契约端点覆盖率 " + apitestCoveragePercent + "% 达标" : "")
                    + "，无未关闭缺陷），计划完成";
        }
        if (passed && plan.status() != STATUS_COMPLETED) {
            jdbcTemplate.update("UPDATE tp_exec_plan SET status = ?, updated_at = now() WHERE id = ?",
                    STATUS_COMPLETED, id);
        }
        // Hint slot (off by default): surface the pending contract change-review
        // count as plain language; it never blocks and never drifts the core message.
        String rawMode = configCacheHolder.get(CFG_SPEC_EXIT_REVIEW_WARN);
        long pendingSpecChanges = 0;
        String reviewWarn = null;
        if (rawMode != null && !rawMode.isBlank() && !"0".equals(rawMode.trim())) {
            pendingSpecChanges = changeAnalysisService.countPending(plan.projectId());
            if (pendingSpecChanges > 0) {
                reviewWarn = "契约变更待复核 " + pendingSpecChanges + " 条尚未处置，建议前往变更分析队列复核（不影响准出）";
            }
        }
        return new CompletionCheckView(passed, message, round4(p.passRate()), minPassRate,
                openDefects, p0OpenDefects, p.total(), p.passed() + p.failed(),
                p.passed(), p.failed(), p.skipped(), p.notRun(),
                pendingSpecChanges, reviewWarn, apitestCoveragePercent, minApitestCoverage);
    }

    /**
     * Coverage floor from the K8 value: positive int ≤ 100 activates the gate;
     * 0, blank, absent or unparseable = gate off (fail-open for existing plans —
     * a misconfigured key must never explode the exit check).
     */
    private int parseCoverageFloor(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return (value > 0 && value <= 100) ? value : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    
    private String evalGateMessage(ExitCriteria exit) {
        if (!exit.evalGateEnabled()) {
            return null;
        }
        List<ScorecardTrendPointView> latest = judgeScorecardService.trend(
                exit.evalRubricName(), exit.evalRubricDomain(), 1);
        if (latest.isEmpty()) {
            // Rejection guidance names the shipped page entries (eval-set and
            // run-history doors are live), so the operator knows where to act.
            return "AI 评测门禁未满足：评分标准族「" + exit.evalRubricName() + "×"
                    + exit.evalRubricDomain() + "」尚无已完成评测运行——"
                    + "请先到「评测集」页建卷并发布版本，再到「评测运行」页发起评测，"
                    + "出现已完成运行后即可重新申请准出";
        }
        ScorecardTrendPointView point = latest.get(0);
        double gate = exit.evalMinPassRate();
        if (point.passRate() == null || point.passRate() < gate) {
            return String.format("AI 评测门禁未满足：最近一次评测（Run %d）达线率 %s 低于门禁阈值 %.1f%%",
                    point.runId(),
                    point.passRate() == null ? "无法计算（尚无落分样本）"
                            : String.format("%.1f%%", point.passRate() * 100),
                    gate * 100);
        }
        RunView run = judgeRunService.detail(point.runId());
        Long llmConfigId = run == null ? null : run.llmConfigId();
        boolean qualified = judgeCalibrationProfileService
                .profile(exit.evalRubricName(), exit.evalRubricDomain(), llmConfigId).stream()
                .filter(g -> java.util.Objects.equals(g.llmConfigId(), llmConfigId))
                .anyMatch(CalibrationGroupView::qualified);
        if (!qualified) {
            return "AI 评测门禁未满足：该评测 judge 模型的人机一致率尚未达标（改判配对不足或 κ 未达线），"
                    + "单裁判分仅为参考分、禁入准出——请先在评审台补充人工改判完成校准";
        }
        return null;
    }

    @Override
    @Transactional
    @Audit(objectType = "exec_plan", action = "plan_cancel", objectId = "#id")
    public void cancel(long operatorId, long id) {
        PlanInfo plan = get(id);
        if (plan.status() != STATUS_DRAFT && plan.status() != STATUS_PUBLISHED
                && plan.status() != STATUS_RUNNING) {
            throw new ServiceException("仅草稿/已发布/执行中的计划可取消——可在「执行计划」列表查看该行「状态」；已完成或已取消的计划无需也无法再取消", BizErrorCode.STATE_MACHINE_REJECT);
        }
        jdbcTemplate.update("UPDATE tp_exec_plan SET status = ?, updated_at = now() WHERE id = ?",
                STATUS_CANCELLED, id);
        List<Long> cancelledBatches = jdbcTemplate.query(
                "UPDATE tp_exec_batch SET status = ?, version = version + 1, updated_at = now()"
                        + " WHERE plan_id = ? AND status IN (1, 2, 3) RETURNING id",
                (rs, n) -> rs.getLong(1), BATCH_CANCELLED, id);
        cancelledBatches.forEach(envExecutionLock::releaseByBatch);
    }

    @Override
    @Transactional
    @Audit(objectType = "exec_plan", action = "plan_delete", objectId = "#id")
    public void delete(long operatorId, long id) {
        delete(operatorId, id, false);
    }

    @Override
    @Transactional
    @Audit(objectType = "exec_plan", action = "plan_delete", objectId = "#id")
    public void delete(long operatorId, long id, boolean force) {
        PlanInfo plan = get(id);
        if (plan.status() != STATUS_DRAFT && plan.status() != STATUS_CANCELLED) {
            throw new ServiceException("仅草稿/已取消的计划可删除（已发布计划请先取消）——执行中的计划请先在计划详情页点「取消计划」；删除入口在「执行计划」该行「更多」菜单",
                    BizErrorCode.STATE_MACHINE_REJECT);
        }
        DeleteCheckResult check = deleteGuard.checkPlan(id);
        if (!check.deletable() && !force) {
            throw new ServiceException("该计划被 %s 引用，无法删除——确需级联清理请在该行「删除」触发的确认弹窗点「确认级联删除」；否则请先解除上述引用",
                    BizErrorCode.RESOURCE_CONFLICT, DeleteGuard.joinMessages(check.refs()));
        }
        jdbcTemplate.update("DELETE FROM tp_exec_task WHERE batch_id IN (SELECT id FROM tp_exec_batch WHERE plan_id = ?)", id);
        jdbcTemplate.update("DELETE FROM tp_batch_param_snapshot WHERE batch_id IN (SELECT id FROM tp_exec_batch WHERE plan_id = ?)", id);
        jdbcTemplate.update("DELETE FROM tp_batch_runtime_change WHERE batch_id IN (SELECT id FROM tp_exec_batch WHERE plan_id = ?)", id);
        jdbcTemplate.update("DELETE FROM tp_batch_case WHERE batch_id IN (SELECT id FROM tp_exec_batch WHERE plan_id = ?)", id);
        jdbcTemplate.update("DELETE FROM tp_batch_report WHERE batch_id IN (SELECT id FROM tp_exec_batch WHERE plan_id = ?)", id);
        jdbcTemplate.update("DELETE FROM tp_exec_batch WHERE plan_id = ?", id);
        jdbcTemplate.update("DELETE FROM tp_plan_deviation WHERE plan_id = ?", id);
        jdbcTemplate.update("DELETE FROM tp_plan_case WHERE plan_id = ?", id);
        jdbcTemplate.update("DELETE FROM tp_exec_plan WHERE id = ?", id);
    }


 
    private void pullCases(long planId, long projectId, CaseScopeRule scope) {
        if (!scope.explicitCaseIds().isEmpty()) {
            List<Long> validIds = jdbcTemplate.queryForList("""
                    SELECT id FROM tp_test_case
                    WHERE project_id = ? AND deleted = 0 AND status <> 9
                      AND id IN (%s)
                    """.formatted(SqlPlaceholders.in(scope.explicitCaseIds().size())),
                    Long.class, concat(List.of(projectId), scope.explicitCaseIds()));
            for (Long caseId : validIds) {
                jdbcTemplate.update("INSERT INTO tp_plan_case (plan_id, case_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                        planId, caseId);
            }
            return;
        }
        StringBuilder sql = new StringBuilder("""
                SELECT id FROM tp_test_case
                WHERE project_id = ? AND deleted = 0 AND status <> 9
                """);
        List<Object> args = new ArrayList<>();
        args.add(projectId);
        if (!scope.phases().isEmpty()) {
            sql.append(" AND phase IN (").append(SqlPlaceholders.in(scope.phases().size())).append(")");
            args.addAll(scope.phases());
        }
        if (!scope.priorities().isEmpty()) {
            sql.append(" AND priority IN (").append(SqlPlaceholders.in(scope.priorities().size())).append(")");
            args.addAll(scope.priorities());
        }
        if (scope.keyword() != null && !scope.keyword().isBlank()) {
            sql.append(" AND (title ILIKE ? OR precondition ILIKE ? OR steps_text ILIKE ?)");
            String kw = "%" + scope.keyword().trim() + "%";
            args.add(kw);
            args.add(kw);
            args.add(kw);
        }
        TagQuerySupport.appendCaseTagFilter(sql, args, projectId, scope.tagIds());
        List<Long> caseIds = jdbcTemplate.queryForList(sql.toString(), Long.class, args.toArray());
        for (Long caseId : caseIds) {
            jdbcTemplate.update("INSERT INTO tp_plan_case (plan_id, case_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                    planId, caseId);
        }
    }

    private void validateEnvs(long projectId, List<Long> envIds) {
        for (Long envId : envIds) {
            try {
                projectEnvService.get(projectId, envId);
            } catch (ServiceException e) {
                throw new ServiceException("环境不存在或不属于该项目（环境 ID=" + envId + "）——请在「新建计划」向导第 2 步「范围与环境」的「执行环境」重新选择本项目环境；环境需先在「项目管理」中配置",
                        BizErrorCode.VALIDATION_ERROR);
            }
        }
    }

    private void requireDraft(PlanInfo plan, String action) {
        if (plan.status() != STATUS_DRAFT) {
            throw new ServiceException("计划已发布，范围/人员/准入准出已锁定，禁止" + action
                    + "（如需变更请先取消计划）——可在计划详情页点「取消计划」后重新创建草稿计划（取消会同步取消关联未执行批次）", BizErrorCode.RESOURCE_CONFLICT);
        }
    }

    private PlanInfo toPlanInfo(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new PlanInfo(rs.getLong("id"), rs.getLong("project_id"), rs.getString("name"),
                rs.getString("target_version"), rs.getInt("status"),
                PlanInfo.statusLabel(rs.getInt("status")), (Long) rs.getObject("owner_id"),
                fromJsonLongs(rs.getString("env_ids_json")),
                fromJson(rs.getString("case_scope_json"), CaseScopeRule.class),
                fromJson(rs.getString("entry_criteria_json"), EntryCriteria.class),
                fromJson(rs.getString("exit_criteria_json"), ExitCriteria.class),
                rs.getString("plan_start"), rs.getString("plan_end"),
                rs.getLong("case_count"), rs.getLong("assignee_count"),
                rs.getLong("created_by"), rs.getString("created_at"),
                rs.getInt("lock_mode"));
    }

    private PlanProgressView.DimRow dimRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new PlanProgressView.DimRow(String.valueOf(rs.getLong("key_id")), rs.getString("label"),
                rs.getInt("total"), rs.getInt("passed"), rs.getInt("failed"),
                rs.getInt("not_run"), rs.getInt("running"), rs.getInt("skipped"));
    }

    private static String phaseLabel(int phase) {
        return switch (phase) {
            case 1 -> "冒烟";
            case 2 -> "功能";
            case 3 -> "回归";
            case 4 -> "发布验证";
            default -> "未归属";
        };
    }

    private static Object[] concat(List<Object> head, List<Long> tail) {
        List<Object> all = new ArrayList<>(head);
        all.addAll(tail);
        return all.toArray();
    }

    private static double round4(double v) {
        return Math.round(v * 10000) / 10000.0;
    }

    private static String trimToNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return s.trim();
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new ServiceException("参数序列化失败——请重试保存；持续失败时请携带「计划名称」联系管理员排查", BizErrorCode.INTERNAL_ERROR);
        }
    }

    private <T> T fromJson(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            return null;
        }
    }

    private List<Long> fromJsonLongs(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<List<Long>>() {
                    });
        } catch (Exception e) {
            return List.of();
        }
    }


    @Override
    @Transactional
    @Audit(objectType = "plan_deviation", action = "deviation_register", objectId = "#planId", after = "#result")
    public PlanDeviationInfo registerDeviation(long operatorId, long planId, long caseId,
                                               int deviationType, String reason) {
        PlanInfo plan = get(planId);
        if (deviationType != 1 && deviationType != 2) {
            throw new ServiceException("偏差类型仅支持 1=计划外用例执行/2=范围外变更——请在计划详情「登记偏差」弹窗的「偏差类型」下拉选择上述两项之一",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (reason == null || reason.isBlank()) {
            throw new ServiceException("请填写偏差原因（谁/为什么/影响）——请在「登记偏差」弹窗「偏差原因」写明谁、为什么、影响，示例：客户紧急补测，未纳入本期范围", BizErrorCode.VALIDATION_ERROR);
        }
        if (reason.length() > 500) {
            throw new ServiceException("偏差原因长度不能超过 500 个字符——请在「登记偏差」弹窗「偏差原因」缩短到 500 个字符以内后确认", BizErrorCode.VALIDATION_ERROR);
        }
        Long caseExists = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_test_case WHERE id = ? AND project_id = ? AND deleted = 0",
                Long.class, caseId, plan.projectId());
        if (caseExists == null || caseExists == 0) {
            throw new ServiceException("用例不存在或不属于该项目——请在「登记偏差」弹窗的「涉及用例」下拉重新选择本项目用例", BizErrorCode.VALIDATION_ERROR);
        }
        long devId = jdbcTemplate.queryForObject("""
                INSERT INTO tp_plan_deviation (plan_id, case_id, deviation_type, reason, created_by)
                VALUES (?, ?, ?, ?, ?) RETURNING id
                """, Long.class, planId, caseId, deviationType, reason.trim(), operatorId);
        return listDeviations(planId).stream()
                .filter(d -> d.id() == devId).findFirst().orElse(null);
    }

    @Override
    public List<PlanDeviationInfo> listDeviations(long planId) {
        get(planId);
        return jdbcTemplate.query("""
                SELECT d.id, d.plan_id, d.case_id, d.deviation_type, d.reason, d.created_by,
                       d.created_at, u.display_name AS created_by_name,
                       c.case_no, c.title AS case_title
                FROM tp_plan_deviation d
                LEFT JOIN tp_user u ON u.id = d.created_by
                LEFT JOIN tp_test_case c ON c.id = d.case_id
                WHERE d.plan_id = ?
                ORDER BY d.id DESC
                """, (rs, rowNum) -> new PlanDeviationInfo(
                rs.getLong("id"), rs.getLong("plan_id"), rs.getLong("case_id"),
                rs.getString("case_no"), rs.getString("case_title"),
                rs.getInt("deviation_type"), PlanDeviationInfo.typeLabel(rs.getInt("deviation_type")),
                rs.getString("reason"), rs.getLong("created_by"),
                rs.getString("created_by_name"),
                rs.getString("created_at")), planId);
    }

    @Override
    public void validateCaseAllowed(long planId, long caseId) {
        PlanInfo plan = get(planId);
        if (plan.lockMode() != 2) {
            return;
        }
        Long inPlan = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_plan_case WHERE plan_id = ? AND case_id = ?",
                Long.class, planId, caseId);
        if (inPlan != null && inPlan > 0) {
            return;
        }
        Long dev = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_plan_deviation WHERE plan_id = ? AND case_id = ? AND deviation_type = 1",
                Long.class, planId, caseId);
        if (dev != null && dev > 0) {
            return;
        }
        throw new ServiceException("严格模式：该用例不在计划范围内，请先登记偏差（类型=计划外用例执行）后再执行——可在计划详情「偏差记录」卡点「登记偏差」，「偏差类型」选「计划外用例执行」提交后重试",
                BizErrorCode.FORBIDDEN);
    }
}
