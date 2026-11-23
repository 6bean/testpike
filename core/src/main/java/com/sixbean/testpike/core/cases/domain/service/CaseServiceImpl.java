package com.sixbean.testpike.core.cases.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.OptimisticLockingException;
import com.sixbean.testpike.core.audit.domain.component.Audit;
import com.sixbean.testpike.core.common.component.dedup.DedupVerdict;
import com.sixbean.testpike.core.common.domain.component.ExcelExportSupport;
import com.sixbean.testpike.core.common.domain.component.ExportScopeValidator;
import com.sixbean.testpike.core.common.domain.component.PageQueryPolicy;
import com.sixbean.testpike.core.common.domain.component.VirtualScrollEndpoint;
import com.sixbean.testpike.core.common.domain.component.SqlPlaceholders;
import com.sixbean.testpike.core.cases.api.dto.AssignBatchResult;
import com.sixbean.testpike.core.cases.api.dto.AssignResult;
import com.sixbean.testpike.core.cases.api.dto.BatchEditFailureItem;
import com.sixbean.testpike.core.cases.api.dto.BatchEditRequest;
import com.sixbean.testpike.core.cases.api.dto.BatchEditResult;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseExportRequest;
import com.sixbean.testpike.core.cases.api.dto.CaseExportResult;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.api.dto.CaseQuery;
import com.sixbean.testpike.core.cases.api.dto.CaseRecycleInfo;
import com.sixbean.testpike.core.cases.api.dto.CaseReviewLogInfo;
import com.sixbean.testpike.core.cases.api.dto.CaseRollbackResult;
import com.sixbean.testpike.core.cases.api.dto.CaseSnapshot;
import com.sixbean.testpike.core.cases.api.dto.CaseStepInfo;
import com.sixbean.testpike.core.cases.api.dto.CaseStepInput;
import com.sixbean.testpike.core.cases.api.dto.CaseUpdateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseVersionDiff;
import com.sixbean.testpike.core.cases.api.dto.CaseVersionFieldChange;
import com.sixbean.testpike.core.cases.api.dto.CaseVersionInfo;
import com.sixbean.testpike.core.cases.api.dto.ReviewBatchRequest;
import com.sixbean.testpike.core.cases.domain.component.CaseExportRow;
import com.sixbean.testpike.core.cases.domain.component.CaseExportSupport;
import com.sixbean.testpike.core.script.domain.component.ScriptInvalidationSupport;
import com.sixbean.testpike.core.script.domain.component.ScriptVersionSupport;
import com.sixbean.testpike.core.cases.domain.component.CaseFilters;
import com.sixbean.testpike.core.cases.domain.component.CaseFieldUpdate;
import com.sixbean.testpike.core.cases.domain.component.CaseFilteredWhere;
import com.sixbean.testpike.core.cases.domain.component.CaseSnapshotCodec;
import com.sixbean.testpike.core.cases.domain.component.CaseVersionDiffCalculator;
import com.sixbean.testpike.core.cases.api.dto.ReviewBatchResult;
import com.sixbean.testpike.core.cases.api.dto.ReviewResult;
import com.sixbean.testpike.core.cases.api.dto.SkippedReviewItem;
import com.sixbean.testpike.core.cases.domain.component.CaseStatus;
import com.sixbean.testpike.core.cases.domain.component.CaseStatusMachine;
import com.sixbean.testpike.core.cases.domain.component.FeedbackType;
import com.sixbean.testpike.core.cases.domain.component.ReviewAction;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.common.domain.component.LikePattern;
import com.sixbean.testpike.core.events.api.dto.CaseEventMessage;
import com.sixbean.testpike.core.events.api.dto.CaseEventType;
import com.sixbean.testpike.core.events.domain.service.CaseEventPublisher;
import com.sixbean.testpike.core.tags.domain.component.TagQuerySupport;


import com.sixbean.testpike.common.exception.ServiceException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import com.sixbean.testpike.core.audit.domain.component.AuditLogContext;
import com.sixbean.testpike.core.audit.domain.component.AuditSnapshot;


@Service
public class CaseServiceImpl implements CaseService {

 
    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "priority", "priority",
            "created_at", "created_at",
            "confidence", "confidence",
            "case_no", "case_no",
            "updated_at", "updated_at");
 
    private static final int MAX_OFFSET = 100_000;
    
    private static final String EXPORT_FILE_BASE = "用例导出";
    private static final List<String> END_TYPES = List.of("web", "mobile", "pos", "multi");
 
    private static final int DIFF_SPAN_LIMIT = 20;
 
    private static final long CASE_NO_LOCK_KEY_BASE = 20260908_148L;

    private static final String COLUMNS = """
            id, case_no, project_id, title, precondition, expected_result, priority, confidence,
            end_type, status, phase, version, assignee_id, assigned_at, source, source_ref, created_by, created_at, updated_at,
            flaky, flaky_reason, flaky_marked_at, excellent_flag,
            automation_target, appauto_engine, appauto_env_profile_id, mock_space_id, evidence_grade
            """;

    private final JdbcTemplate jdbcTemplate;

 
    private final com.sixbean.testpike.core.cases.domain.component.FlakyRuleCalculator flakyRuleCalculator;

 
    @Value("${review.remark-required:true}")
    private boolean remarkRequired;

 
    private final CaseEventPublisher caseEventPublisher;

    /** Generic change-linkage bus publisher: every case save announces
     * {@code case.changed} with its field diff so registered edges, not
     * scattered hooks, decide what downstream rows a save invalidates. */
    private final com.sixbean.testpike.core.linkage.domain.service
            .ObjectChangedEventPublisher objectChangedEventPublisher;

 
    private final com.sixbean.testpike.core.rbac.domain.service.RoleService roleService;

 
    private final com.sixbean.testpike.core.cases.domain.component.CasePurgeSupport casePurgeSupport;

 
    private final com.sixbean.testpike.core.cases.domain.component.CaseExportSupport caseExportSupport;

 
    private final com.sixbean.testpike.core.report.domain.component.ExportTaskSupport exportTaskSupport;

 
    private final com.sixbean.testpike.core.audit.domain.service.AuditLogService auditLogService;

 
    private final ScriptVersionSupport scriptVersionSupport;

 
    private final ScriptInvalidationSupport scriptInvalidationSupport;

    
    private final com.sixbean.testpike.core.cases.domain.component.AutoAssignSupport autoAssignSupport;

 
    private final com.sixbean.testpike.core.executor.domain.component.AppAutoDispatchSupport appAutoDispatchSupport;

    /** Mock binding gate: published+own-project check for the declarative mock_space_id. */
    private final com.sixbean.testpike.core.mock.domain.component.MockSpaceBindingGate mockSpaceBindingGate;
    private final com.sixbean.testpike.core.common.component.dedup.DedupGate dedupGate;
    private final com.sixbean.testpike.core.behavior.domain.component.BehaviorOutcomeIntake behaviorOutcomeIntake;
    private final com.sixbean.testpike.core.program.domain.component.ProgramLensScope programLensScope;
    /** Adoption ledger observer for the edit/delete chains . */
    private final com.sixbean.testpike.core.cases.domain.component.AiOutputAdoptionObserver aiOutputAdoptionObserver;
    /** Single-source step-anchor normalizer (V624 column contract; R40 no second shape check). */
    private final com.sixbean.testpike.core.cases.domain.component.CaseStepAnchorNormalizer anchorNormalizer;

    public CaseServiceImpl(JdbcTemplate jdbcTemplate, CaseEventPublisher caseEventPublisher,
                           com.sixbean.testpike.core.linkage.domain.service
                                   .ObjectChangedEventPublisher objectChangedEventPublisher,
                           com.sixbean.testpike.core.rbac.domain.service.RoleService roleService,
                           com.sixbean.testpike.core.cases.domain.component.FlakyRuleCalculator flakyRuleCalculator,
                           com.sixbean.testpike.core.cases.domain.component.CasePurgeSupport casePurgeSupport,
                           com.sixbean.testpike.core.cases.domain.component.CaseExportSupport caseExportSupport,
                           com.sixbean.testpike.core.report.domain.component.ExportTaskSupport exportTaskSupport,
                           com.sixbean.testpike.core.audit.domain.service.AuditLogService auditLogService,
                           ScriptVersionSupport scriptVersionSupport,
                           ScriptInvalidationSupport scriptInvalidationSupport,
                           com.sixbean.testpike.core.cases.domain.component.AutoAssignSupport autoAssignSupport,
                           com.sixbean.testpike.core.executor.domain.component.AppAutoDispatchSupport appAutoDispatchSupport,
                           com.sixbean.testpike.core.mock.domain.component.MockSpaceBindingGate mockSpaceBindingGate,
                           com.sixbean.testpike.core.common.component.dedup.DedupGate dedupGate,
                           com.sixbean.testpike.core.behavior.domain.component.BehaviorOutcomeIntake behaviorOutcomeIntake,
                           com.sixbean.testpike.core.program.domain.component.ProgramLensScope programLensScope,
                           com.sixbean.testpike.core.cases.domain.component.AiOutputAdoptionObserver aiOutputAdoptionObserver,
                           com.sixbean.testpike.core.cases.domain.component.CaseStepAnchorNormalizer anchorNormalizer) {
        this.jdbcTemplate = jdbcTemplate;
        this.caseEventPublisher = caseEventPublisher;
        this.objectChangedEventPublisher = objectChangedEventPublisher;
        this.roleService = roleService;
        this.programLensScope = programLensScope;
        this.flakyRuleCalculator = flakyRuleCalculator;
        this.casePurgeSupport = casePurgeSupport;
        this.caseExportSupport = caseExportSupport;
        this.exportTaskSupport = exportTaskSupport;
        this.auditLogService = auditLogService;
        this.scriptVersionSupport = scriptVersionSupport;
        this.scriptInvalidationSupport = scriptInvalidationSupport;
        this.autoAssignSupport = autoAssignSupport;
        this.appAutoDispatchSupport = appAutoDispatchSupport;
        this.mockSpaceBindingGate = mockSpaceBindingGate;
        this.dedupGate = dedupGate;
        this.behaviorOutcomeIntake = behaviorOutcomeIntake;
        this.aiOutputAdoptionObserver = aiOutputAdoptionObserver;
        this.anchorNormalizer = anchorNormalizer;
    }

    
    @Audit(objectType = "case", action = "create",
            objectId = "#result?.id() ?: #input.projectId()", after = "#result")
    @Transactional
    public CaseInfo create(Long operatorId, CaseCreateInput input) {
        String code = projectCode(input.projectId());
        String title = trimToNull(input.title());
        if (title == null || title.length() > 300) {
            throw new ServiceException("用例标题（测试目标）不能为空且不超过 300 字——请在「用例库」新建表单填写「用例标题」（正确写法示例：登录成功后首页展示欢迎语）", BizErrorCode.VALIDATION_ERROR);
        }
        int priority = input.priority() == null ? 1 : input.priority();
        int confidence = input.confidence() == null ? 2 : input.confidence();
        String endType = input.endType() == null || input.endType().isBlank() ? "web" : input.endType().trim();
        validatePriority(priority);
        validateConfidence(confidence);
        validateEndType(endType);
        validatePhase(input.phase());
        int status = input.status() == null ? 1 : input.status();
        if (status < 1 || status > 9) {
            throw new ServiceException("用例状态仅支持 1~9（9 态状态机）——请改成 1~9 之间的值（正确写法示例：1=草稿 2=待评审；页面上状态由流转按钮驱动，可在「用例详情」点击「提交评审」等按钮推进）", BizErrorCode.VALIDATION_ERROR);
        }
        int source = input.source() == null ? 1 : input.source();
        if (source < 1 || source > 6) {
            throw new ServiceException("用例来源仅支持 1~6——请改成清单内的值（正确写法示例：1=AI生成 2=模板 3=存量导入 4=克隆 5=探索转用例 6=沙盒升格）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        com.sixbean.testpike.core.executor.domain.component.AppAutoCaseTarget caseTarget =
                appAutoDispatchSupport.validateCaseTargetFields(input.projectId(),
                        input.automationTarget(), input.appautoEngine(), input.appautoEnvProfileId());
        validateSteps(input.steps());
        String stepsText = aggregateSteps(normalizeStepAnchors(input.steps()));
        List<Long> tagIds = input.tagIds() == null ? List.of() : input.tagIds();
        validateProjectTags(input.projectId(), tagIds);
        Long assigneeId = input.assigneeId();
        if (assigneeId != null && assigneeId != 0L) {
            requireMember(input.projectId(), assigneeId);
            requireTester(assigneeId);
        } else {
            assigneeId = null;
        }

        // Declarative mock binding — 0/null = real environment;
        // existence / own-project / published gates are the single-point
        // MockSpaceBindingGate shared with the update and suite chains.
        Long mockSpaceId = (input.mockSpaceId() == null || input.mockSpaceId() == 0L)
                ? null : input.mockSpaceId();
        mockSpaceBindingGate.requireBindable(input.projectId(), mockSpaceId);

        java.time.LocalDateTime initialAssignedAt = assigneeId != null
                ? java.time.LocalDateTime.now() : null;
        String caseNo = null;
        long id;
        try {
            jdbcTemplate.queryForObject("SELECT pg_advisory_xact_lock(?)::text", String.class,
                    CASE_NO_LOCK_KEY_BASE + input.projectId());
            caseNo = generateCaseNo(input.projectId(), code);
            // Duplicate gate on the business key (design A7): a normalized clash with a
            // live row inside the lock window means numbering drifted (renamed/variant
            // holder) — refuse with the holder named instead of racing the constraint.
            DedupVerdict numberVerdict = dedupGate.evaluateCase(input.projectId(), caseNo);
            if (numberVerdict.blocksCreate()) {
                throw new ServiceException(String.format(
                        "用例编号 %s 与既有用例冲突（id=%d，编号《%s》），本次创建被检出拦截；请重试新建。"
                                + "该拦截可在「系统设置 → 系统配置」将 ai.dedup.enabled 设为关闭（修改即时生效）",
                        caseNo, numberVerdict.matched().id(), numberVerdict.matched().title()),
                        BizErrorCode.VALIDATION_ERROR);
            }
            id = Objects.requireNonNull(jdbcTemplate.queryForObject("""
                    INSERT INTO tp_test_case (case_no, project_id, title, precondition, expected_result,
                        priority, confidence, end_type, status, phase, source, source_ref, steps_text,
                        assignee_id, assigned_at, created_by,
                        automation_target, appauto_engine, appauto_env_profile_id, mock_space_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                    """, Long.class, caseNo, input.projectId(), title, trimToNull(input.precondition()),
                    trimToNull(input.expectedResult()), priority, confidence, endType, status,
                    input.phase(), source, trimToNull(input.sourceRef()), stepsText,
                    assigneeId, initialAssignedAt, operatorId,
                    caseTarget.automationTarget(), caseTarget.appautoEngine(),
                    caseTarget.appautoEnvProfileId(), mockSpaceId));
        } catch (DuplicateKeyException e) {
            // Name the current holder through the gate's write-instant re-check
            // instead of assuming it is still the number we tried (renamed row or
            // concurrent same-key create both speak honestly here).
            String holder = dedupGate.recheckCaseKeyAtWrite(caseNo)
                    .map(c -> "（当前持有者 id=" + c.id() + "，编号《" + c.title() + "》）")
                    .orElse("");
            throw new ServiceException("用例编号 %s 已存在%s（并发冲突）——请返回「用例库」刷新列表确认该编号归属后改用例编号重试", BizErrorCode.RESOURCE_CONFLICT, caseNo, holder);
        }
        replaceSteps(id, 1, input.steps());
        if (!tagIds.isEmpty()) {
            replaceCaseTags(id, tagIds);
        }
        CaseInfo created = get(id);
        writeSnapshot(created, CaseVersionInfo.CHANGE_EDIT, operatorId, null);
        return created;
    }

    
    public CaseInfo get(long id) {
        List<CaseInfo> rows = jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM tp_test_case WHERE id = ? AND deleted = 0", this::mapRow, id);
        if (rows.isEmpty()) {
            throw caseNotFound(id);
        }
        CaseInfo info = rows.get(0);
        List<CaseStepInfo> steps = jdbcTemplate.query(
                "SELECT id, step_no, description, is_assert, anchor::text AS anchor FROM tp_case_step WHERE case_id = ? ORDER BY step_no",
                (rs, rowNum) -> new CaseStepInfo(rs.getLong("id"), rs.getInt("step_no"),
                        rs.getString("description"), rs.getInt("is_assert"), rs.getString("anchor")), id);
        return info.withSteps(steps);
    }

    /**
 * Only roles with data scope set to all are allowed; otherwise return 403 with a plain-language message.
 * For data scope=self, apply AND (created_by=? OR assignee_id=?); when operatorId is null, do not apply the self filter.
 * Keyword search falls back to PostgreSQL LIKE with a pg_trgm index; sort fields are mapped through a whitelist to prevent injection.
 * Offset is clamped to MAX_OFFSET to protect against deep pagination.
 */
    public PageResult<CaseInfo> list(CaseQuery query, Long operatorId) {
        int safePage = Math.max(query.page(), 1);
        // Size is clamped to the whitelist range [1,1000], with virtual scrolling endpoint exceptions registered in the whitelist.
        int safeSize = PageQueryPolicy.clampSize(VirtualScrollEndpoint.CASES_LIST, query.size());
        int offset = (int) PageQueryPolicy.clampOffset(safePage, safeSize, MAX_OFFSET);

        long projectId = query.projectId();
        // Program lens : optional narrowing to lens projects ∩ caller visibility,
        // resolved by the ProgramLensScope single source — never re-implemented here.
        Set<Long> lensProjectIds = query.programId() > 0
                ? programLensScope.visibleProjectIds(query.programId(), operatorId)
                : null;
        CaseFilteredWhere where = CaseFilters.build(
                projectId, lensProjectIds,
                query.status(), query.keyword(), query.priority(), query.confidence(),
                query.assigneeId(), query.phase(), query.tagIds(), query.excellentOnly(),
                query.sourceFacet(), query.evidenceGrade(), operatorId, roleService);

        Long total = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_test_case" + where.sql(), Long.class, where.args().toArray());

        String sortColumn = SORT_COLUMNS.getOrDefault(query.sortBy() == null ? "" : query.sortBy(), "created_at");
        String sortDir = "asc".equalsIgnoreCase(query.sortDir()) ? "ASC" : "DESC";
        List<Object> listArgs = new ArrayList<>(where.args());
        listArgs.add(safeSize);
        listArgs.add(offset);
        List<CaseInfo> list = jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM tp_test_case" + where.sql()
                        + " ORDER BY " + sortColumn + " " + sortDir + ", id DESC LIMIT ? OFFSET ?",
                this::mapRow, listArgs.toArray());
        return new PageResult<>(list, total != null ? total : 0L);
    }

    /**
 * Cases are batch exported via /02 + /02.
     * The checkbox ids and filter flattened columns are validated as mutually exclusive one-of; ids mode uses the same source as listByIds.
     * Soft-deleted records are removed and counted; filter mode reuses {@link CaseFilters} and is fully consistent with the list criteria.
 * Data scope filtering uses the same source; upper-limit protection applies when ids exceed 5000 or filter results exceed 2000.
     * The export uses the asynchronous export queue, reuses tp_export_task, sends an EXPORT_DONE in-site message on completion, and follows the contract.
 * Cell formula injection escaping and filename sanitization are centralized in the shared {@link ExcelExportSupport} component for reuse.
     */
    @Override
    public CaseExportResult export(Long operatorId, CaseExportRequest request) {
        boolean hasFilter = request.status() != null || request.keyword() != null
                || request.priority() != null || request.confidence() != null
                || request.assigneeId() != null || request.phase() != null
                || (request.tagIds() != null && !request.tagIds().isEmpty());
 // ids/filter one-of and upper-limit validation are extracted into the shared ExportScopeValidator, with defect export using the same contract and source.
        // Three 400 plain-language messages are maintained at a single point and must not be copied.
        String mode = ExportScopeValidator.validate(request.ids(), hasFilter);

        List<CaseInfo> visible;
        int removed = 0;
        boolean hasIds = "ids".equals(mode);
 // Visibility criteria are fully sourced from the list, using CaseFilters with project scope, data scope, and deleted=0.
        CaseFilteredWhere where = CaseFilters.build(request.projectId(), null,
                hasIds ? null : request.status(), hasIds ? null : request.keyword(),
                hasIds ? null : request.priority(), hasIds ? null : request.confidence(),
                hasIds ? null : request.assigneeId(), hasIds ? null : request.phase(),
                hasIds ? null : request.tagIds(), null, operatorId, roleService);
        if (hasIds) {
 // ids mode narrows the visible set to the selected subset, with removed count equal to selected minus matched, including silent removal and a count hint.
            List<Object> args = new ArrayList<>(where.args());
            args.addAll(request.ids());
            visible = jdbcTemplate.query(
                    "SELECT " + COLUMNS + " FROM tp_test_case" + where.sql() + " AND id IN ("
                            + SqlPlaceholders.in(request.ids().size()) + ") ORDER BY id",
                    this::mapRow, args.toArray());
            removed = request.ids().size() - visible.size();
        } else {
            visible = jdbcTemplate.query(
                    "SELECT " + COLUMNS + " FROM tp_test_case" + where.sql() + " ORDER BY id",
                    this::mapRow, where.args().toArray());
        }
        int total = visible.size();
        recordExportAudit(operatorId, request.projectId(), mode, total, removed);

        if (total > CaseExportSupport.ASYNC_THRESHOLD) {
            // Asynchronous export for large data volumes reuses the existing export queue, returning a task id for the existing download endpoint and an in-site message.
            long operator = operatorId == null ? 0L : operatorId;
            long taskId = exportTaskSupport.createTask(operator, request.projectId(), "case_export", false, total);
            exportTaskSupport.startAsync(taskId, operator, request.projectId(), "case_export",
                    "用例导出完成", "用例导出完成（%d 条），请到导出任务列表下载。",
                    () -> new com.sixbean.testpike.core.report.domain.component.ExportArtifact(
                            buildExcel(visible), total));
            return new CaseExportResult(true, taskId, null, null, total, removed);
        }
        return new CaseExportResult(false, 0, ExportScopeValidator.exportFileName(EXPORT_FILE_BASE),
                buildExcel(visible), total, removed);
    }

    /** It exports Excel bytes by assembling rows and applying the shared formula escaping component. */
    private byte[] buildExcel(List<CaseInfo> visible) {
        List<CaseExportRow> rows = caseExportSupport.assembleRows(visible);
        List<List<Object>> data = new ArrayList<>(rows.size());
        for (CaseExportRow row : rows) {
            data.add(row.toExcelRow());
        }
        return ExcelExportSupport.toExcel(CaseExportSupport.sheetName(), CaseExportSupport.HEADERS, data);
    }

    /** Export behavior is audited with who, when, and scope; the after snapshot is a simplified scope snapshot without file bytes. */
    private void recordExportAudit(Long operatorId, long projectId, String mode, int total, int removed) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("mode", mode);
        after.put("projectId", projectId);
        after.put("total", total);
        after.put("removed", removed);
        auditLogService.record(new com.sixbean.testpike.core.audit.api.dto.AuditLogInput(
                operatorId, projectId, "case", String.valueOf(projectId), "export", null, after));
    }

    /**
     * Field-level update: null fields are not updated; non-null steps are replaced as a whole.
     * Version optimistic locking uses WHERE version = ?, and zero affected rows returns 40901 version conflict.
     */
    @Audit(objectType = "case", action = "update", objectId = "#id", after = "#result")
    public CaseInfo update(Long operatorId, long id, CaseUpdateInput input, int expectedVersion) {
        return updateWithChangeType(operatorId, id, input, expectedVersion, CaseVersionInfo.CHANGE_EDIT);
    }

    /**
     * The update implementation parameterizes changeType: edit=1 for edit snapshots and rollback=4 for rollback snapshots, using the same field-level replay logic.
     * Copy-paste forks are prohibited in accordance with the DRY principle.
     */
    private CaseInfo updateWithChangeType(Long operatorId, long id, CaseUpdateInput input,
                                          int expectedVersion, int changeType) {
        if (expectedVersion < 1) {
            throw new ServiceException("version 必须 ≥ 1（乐观锁）——请携带「用例详情」加载用例时返回的版本号提交；若刚被他人改动，请刷新后重试", BizErrorCode.VALIDATION_ERROR);
        }
        CaseInfo current = get(id); // Return 404 semantics and read the current row because case_version depends on it.
        AuditLogContext.setBefore(AuditSnapshot.snapshot(current)); // Take a before snapshot that includes the phase change diff.
        if (input.priority() != null) {
            validatePriority(input.priority());
        }
        if (input.confidence() != null) {
            validateConfidence(input.confidence());
        }
        if (input.endType() != null && !input.endType().isBlank()) {
            validateEndType(input.endType().trim());
        }
        validatePhase(input.phase());
        validateSteps(input.steps());
 // Validate that tags belong to the project and validate assignees, reusing existing logic; 0 means clear and is not validated.
        if (input.tagIds() != null) {
            if (input.tagIds().size() > 50) {
                throw new ServiceException("标签单次最多设置 50 个——请在「用例详情」标签区减少勾选后保存（单次上限 50）", BizErrorCode.VALIDATION_ERROR);
            }
            validateProjectTags(current.projectId(), input.tagIds());
        }
        Long assigneeId = input.assigneeId();
        if (assigneeId != null && assigneeId != 0L) {
            requireMember(current.projectId(), assigneeId);
            requireTester(assigneeId);
        }

 // For target three-column updates, if any column is provided, merge the current row with the current input into a view for validation.
        // The same-source gate uses AppAutoDispatchSupport.validateCaseTargetUpdate, and valid three columns are atomically overwritten as a whole.
        boolean targetTouched = input.automationTarget() != null
                || input.appautoEngine() != null || input.appautoEnvProfileId() != null;
        com.sixbean.testpike.core.executor.domain.component.AppAutoCaseTarget fuTarget =
                targetTouched
                        ? appAutoDispatchSupport.validateCaseTargetUpdate(current.projectId(),
                                current.automationTarget(), current.appautoEngine(),
                                current.appautoEnvProfileId(),
                                input.automationTarget(), input.appautoEngine(),
                                input.appautoEnvProfileId())
                        : null;
        // Declarative mock binding update: null = column untouched; 0 = unbind back
        // to the real environment (same encoding as assigneeId); otherwise the same
        // single-point MockSpaceBindingGate as the create chain guards ownership and
        // published state before anything lands.
        Long mockSpaceUpdate = input.mockSpaceId();
        if (mockSpaceUpdate != null && mockSpaceUpdate != 0L) {
            mockSpaceBindingGate.requireBindable(current.projectId(), mockSpaceUpdate);
        }
        CaseFieldUpdate fu = new CaseFieldUpdate(trimToNull(input.title()),
                trimToNull(input.precondition()), trimToNull(input.expectedResult()),
                input.priority(), input.confidence(),
                input.endType() == null ? null : input.endType().trim(), input.phase(), assigneeId,
                fuTarget, mockSpaceUpdate);
        int appliedVersion = applyFieldUpdate(id, expectedVersion, fu);
        if (appliedVersion < 0) {
 // Version CAS failure leads to column-level conflict classification based on the client-declared baseline no greater than expectedVersion.
            // Compare the columns to write against the latest snapshot; if not touched by others, retry once using the latest row version.
            // If different fields are written by both, both persist to the database and each version increments without clobber; if there is overlap, the baseline cannot be reconstructed, or the conservative side is used.
 // For assignee conflicts, return 40901 because concurrent same-field updates have conflict semantics and last-writer-wins silent overwrite is prohibited.
            appliedVersion = retryColumnLevelUpdate(id, expectedVersion, fu);
            if (appliedVersion < 0) {
                throw new OptimisticLockingException(
                        "用例已被他人修改（版本冲突），请刷新后重试（当前版本 " + current.version() + "）");
            }
        }
        if (input.steps() != null) {
            replaceSteps(id, appliedVersion, input.steps());
        }
        if (input.tagIds() != null) {
            replaceCaseTags(id, input.tagIds());
        }
 // In strict mode, cases referenced by a plan with lock_mode=2 and changed core fields such as title or expected result are subject to deviation handling.
        // Record a type=2 deviation for out-of-scope changes and make it visible in the deviation list.
        boolean coreChanged = (input.title() != null && !input.title().trim().equals(current.title()))
                || (input.expectedResult() != null
                && !input.expectedResult().trim().equals(current.expectedResult()));
        if (coreChanged) {
            jdbcTemplate.update("""
                    INSERT INTO tp_plan_deviation (plan_id, case_id, deviation_type, reason, created_by)
                    SELECT pc.plan_id, ?, 2, ?, ?
                    FROM tp_plan_case pc JOIN tp_exec_plan p ON p.id = pc.plan_id
                    WHERE pc.case_id = ? AND p.lock_mode = 2 AND p.status <> 5
                    """, id,
                    "用例核心字段变更（标题/预期结果），影响严格模式计划「"
                            + String.join("」「", checkReferences(id)) + "」", operatorId, id);
        }
 // In the invalidation linkage, actual changes to the four content fields in the edit chain trigger script invalidation.
        // Set tp_script.status=2 to pending update and non-executable; the rollback chain with changeType=4 does not attach restoreScript.
 // Re-attachment aligns semantics; invalidation applies only to the edit chain with rollback guard exceptions; missing script rows are silently handled as zero rows.
        // Perform a no-op according to the decision.
        if (changeType == CaseVersionInfo.CHANGE_EDIT
                && scriptRelevantFieldsChanged(input, current)) {
            scriptInvalidationSupport.markPending(id);
        }
 // When an approved case is edited and content fields actually change, its status reverts to pending review 1.
        // Concurrent case.invalidated clears the vector when leaving approved status and re-persists it after re-approval, and the determination reuses the following logic.
        // The determination reuses scriptRelevantFieldsChanged for the same content family, such as title, preconditions, expected result, and steps, while priority, tags, and assignee are listed separately.
 // Other metadata that does not change review facts does not trigger; the rollback chain (changeType=4) is not attached, with the same semantics as the referenced decision.
        if (changeType == CaseVersionInfo.CHANGE_EDIT
                && current.status() == CaseStatus.APPROVED.code()
                && scriptRelevantFieldsChanged(input, current)) {
            jdbcTemplate.update(
                    "UPDATE tp_test_case SET status = ?, updated_at = now() WHERE id = ? AND deleted = 0",
                    CaseStatus.PENDING_REVIEW.code(), id);
            caseEventPublisher.publish(new CaseEventMessage(
                    CaseEventType.INVALIDATED.name(), id, current.projectId(), operatorId,
                    "EDIT_ROLLBACK", null, null, null, null,
                    System.currentTimeMillis(), appliedVersion));
        }
        CaseInfo updatedInfo = get(id);
        writeSnapshot(updatedInfo, changeType, operatorId,
                snapshotOf(current)); // For edit or rollback snapshots, increment v by 1 and do not write when the content is identical to the previous version.
        // Adoption ledger observation on the edit chain only (rollback is a
        // restore, not a human reaction to the model text); the content-family
        // predicate is the same single gate as script invalidation, so a
        // metadata-only touch never feeds the ledger.
        if (changeType == CaseVersionInfo.CHANGE_EDIT
                && scriptRelevantFieldsChanged(input, current)) {
            aiOutputAdoptionObserver.observeEdit(updatedInfo);
        }
        // Change-linkage bus announcement (afterCommit discipline lives in the
        // publisher): the field diff rides along so registered edges arm
        // without re-reading the upstream row. Announced on the rollback chain
        // too — a restore invalidates downstream rows exactly like an edit.
        objectChangedEventPublisher.publish(
                new com.sixbean.testpike.core.linkage.api.dto.ObjectChangedEventMessage(
                        com.sixbean.testpike.core.linkage.api.dto.ObjectChangedEventType.CASE.name(),
                        updatedInfo.id(), updatedInfo.projectId(), operatorId,
                        updatedInfo.version(), changedFieldNames(input, current),
                        System.currentTimeMillis()));
        return updatedInfo;
    }

    private int applyFieldUpdate(long id, int casVersion, CaseFieldUpdate fu) {
        List<String> sets = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        if (fu.title() != null) {
            sets.add("title = ?::varchar(300)");
            args.add(fu.title());
        }
        if (fu.precondition() != null) {
            sets.add("precondition = ?::text");
            args.add(fu.precondition());
        }
        if (fu.expectedResult() != null) {
            sets.add("expected_result = ?::text");
            args.add(fu.expectedResult());
        }
        if (fu.priority() != null) {
            sets.add("priority = ?::smallint");
            args.add(fu.priority());
        }
        if (fu.confidence() != null) {
            sets.add("confidence = ?::smallint");
            args.add(fu.confidence());
        }
        if (fu.endType() != null) {
            sets.add("end_type = ?::varchar(10)");
            args.add(fu.endType());
        }
        if (fu.phase() != null) {
            sets.add("phase = ?::smallint");
            args.add(fu.phase());
        }
        if (fu.caseTarget() != null) {
 // The three columns are atomically overwritten as a whole, with valid values produced by the merge gate and member null values persisted as NULL.
            sets.add("automation_target = ?::varchar(10)");
            args.add(fu.caseTarget().automationTarget());
            sets.add("appauto_engine = ?::varchar(32)");
            args.add(fu.caseTarget().appautoEngine());
            sets.add("appauto_env_profile_id = ?::bigint");
            args.add(fu.caseTarget().appautoEnvProfileId());
        }
        if (fu.mockSpaceId() != null) {
            // Same 0-clears encoding as assigneeId: NULL = run against the real environment
            sets.add("mock_space_id = ?::bigint");
            args.add(fu.mockSpaceId() == 0L ? null : fu.mockSpaceId());
        }
        if (fu.assigneeId() != null) {
            // Encoded semantics: 0 clears by setting NULLIF to NULL and clearing assigned_at; other values set by using now when the assignee changes and keeping the existing value when the assignee is the same.
            sets.add("""
                    assignee_id = CASE WHEN NULLIF(?::bigint, 0) IS NULL THEN NULL ELSE NULLIF(?::bigint, 0) END""");
            sets.add("""
                    assigned_at = CASE
                        WHEN NULLIF(?::bigint, 0) IS NULL THEN NULL
                        WHEN assignee_id IS DISTINCT FROM NULLIF(?::bigint, 0) THEN now()
                        ELSE assigned_at END""");
            args.add(fu.assigneeId());
            args.add(fu.assigneeId());
            args.add(fu.assigneeId());
            args.add(fu.assigneeId());
        }
        sets.add("version = version + 1");
        sets.add("updated_at = now()");
        List<String> guards = new ArrayList<>();
        guards.add("id = ?");
        guards.add("version = ?");
        guards.add("deleted = 0");
        String sql = "UPDATE tp_test_case SET " + String.join(", ", sets)
                + " WHERE " + String.join(" AND ", guards);
        args.add(id);
        args.add(casVersion);
        return jdbcTemplate.update(sql, args.toArray()) == 1 ? casVersion + 1 : -1;
    }

    /**
 * Column-level CAS retry (kernel) determines, after the first attempt encounters a version conflict, whether the columns to be written are touched.
     * Concurrent counterpart touches: the baseline is the client-declared version reconstructed from the latest snapshot at or before expectedVersion, and comparison is limited to the write columns.
     * Only non-null write columns in fu are compared, because counterpart changes to other columns do not intersect with this write and must not cause false positives; all current values equal the baseline.
     * If not touched, resend CAS using the latest row version so that writes to different fields can both persist to database, each version incrementing by one without clobbering.
     * This avoids clobbering; if any write column is changed or cannot be safely determined (assignee touched, snapshot missing, or row deleted), return -1 for the caller.
 * The caller returns 40901, enforcing same-field concurrent update semantics and prohibiting silent last-writer-wins overwrite.
     */
    private int retryColumnLevelUpdate(long id, int expectedVersion, CaseFieldUpdate fu) {
        CaseInfo fresh;
        try {
            fresh = get(id); // Retry uses the current latest row as the CAS baseline; for concurrent deletion, the internal -1 is already produced before returning 404 semantics to the caller.
        } catch (ServiceException e) {
            return -1;
        }
        if (fresh.version() == expectedVersion) {
            return -1; // If the version is unchanged but CAS fails, which is theoretically unreachable, conservatively return 40901.
        }
        CaseSnapshot base = snapshotAtOrBefore(id, expectedVersion);
        if (base == null) {
            return -1; // If the client-declared baseline cannot be reconstructed because no snapshot exists, conservatively return 40901 without guessing.
        }
        // Conservative side: column-level concurrency involving assignee changes cannot be safely determined by value equality because of assigned_at timestamp side effects, so it is not retried.
        // The three automation target columns belong to the same family and are not included in version snapshots because metadata is not version content; the baseline cannot be reconstructed, so conservatively return 40901.
        // The mock binding column belongs to the same family: declarative metadata,
        // not part of the version snapshot — a racing writer is refused, never retried.
        if (fu.assigneeId() != null || fu.caseTarget() != null || fu.mockSpaceId() != null) {
            return -1;
        }
        List<Object> checkArgs = new ArrayList<>();
        List<String> conditions = new ArrayList<>();
        if (fu.title() != null) {
            conditions.add("title IS NOT DISTINCT FROM ?::varchar(300)");
            checkArgs.add(base.title());
        }
        if (fu.precondition() != null) {
            conditions.add("precondition IS NOT DISTINCT FROM ?::text");
            checkArgs.add(base.precondition());
        }
        if (fu.expectedResult() != null) {
            conditions.add("expected_result IS NOT DISTINCT FROM ?::text");
            checkArgs.add(base.expectedResult());
        }
        if (fu.priority() != null) {
            conditions.add("priority IS NOT DISTINCT FROM ?::smallint");
            checkArgs.add(base.priority());
        }
        if (fu.confidence() != null) {
            conditions.add("confidence IS NOT DISTINCT FROM ?::smallint");
            checkArgs.add(base.confidence());
        }
        if (fu.endType() != null) {
            conditions.add("end_type IS NOT DISTINCT FROM ?::varchar(10)");
            checkArgs.add(base.endType());
        }
        if (fu.phase() != null) {
            conditions.add("phase IS NOT DISTINCT FROM ?::smallint");
            checkArgs.add(base.phase());
        }
        if (conditions.isEmpty()) {
            return applyFieldUpdate(id, fresh.version(), fu); // If there are no guardable write columns and only steps or tag side effects exist, continue writing directly.
        }
        Integer untouched = jdbcTemplate.queryForObject(
                "SELECT CASE WHEN (" + String.join(" AND ", conditions)
                        + ") THEN 1 ELSE 0 END FROM tp_test_case WHERE id = ? AND deleted = 0",
                Integer.class, concat(checkArgs, id));
        if (untouched == null || untouched == 0) {
            return -1; // If the columns to be written this time have been changed by a counterpart, return 40901 under same-field semantics.
        }
        return applyFieldUpdate(id, fresh.version(), fu);
    }

    /** Build the parameter list so that the discriminating SQL parameters place guard parameters first and the row ID last. */
    private Object[] concat(List<Object> head, long tail) {
        Object[] out = new Object[head.size() + 1];
        head.toArray(out);
        out[head.size()] = tail;
        return out;
    }

    /** Fetch the latest snapshot at or before version as the source for reconstructing the column guard baseline; if none exists, use null without guessing. */
    private CaseSnapshot snapshotAtOrBefore(long caseId, int version) {
        List<String> jsons = jdbcTemplate.queryForList("""
                SELECT snapshot_json::text FROM tp_case_version
                WHERE case_id = ? AND version <= ?
                ORDER BY version DESC LIMIT 1
                """, String.class, caseId, version);
        if (jsons.isEmpty()) {
            return null;
        }
        try {
            return CaseSnapshotCodec.fromJson(jsons.get(0));
        } catch (Exception e) {
            return null; // A corrupted snapshot is indeterminate, so conservatively return 40901.
        }
    }

    /**
 * Script invalidation check determines whether the contents of the four fields (test objective, precondition, operation steps, and expected result) have changed.
     * The <b>actual change</b> follows the authoritative four-field scope and is reverse-pinned by the reference document for tags/priority.
 * Non-script-related fields such as confidence do not trigger; a conditional 'if steps are changed' with identical submitted content is not considered changed, per the referenced decision.
     * <p>The single-field rule mirrors the trim comparison precedent of coreChanged and aligns with UPDATE COALESCE behavior:
     * Null or blank means no update and is not considered changed; non-empty values with unequal trimmed strings are considered changed, and the steps rule uses description after trimming plus isAssert.
     * <b>Sequence comparison</b> does not compare stepNo because the server-side replaceSteps renumbers 1..N, and order changes naturally appear as sequence changes.
     * This follows the referenced decision.
     */
    private boolean scriptRelevantFieldsChanged(CaseUpdateInput input, CaseInfo current) {
        if (scriptFieldChanged(input.title(), current.title())
                || scriptFieldChanged(input.precondition(), current.precondition())
                || scriptFieldChanged(input.expectedResult(), current.expectedResult())) {
            return true;
        }
        if (input.steps() == null) {
            return false;
        }
        List<CaseStepInfo> currentSteps = current.steps() == null ? List.of() : current.steps();
        if (input.steps().size() != currentSteps.size()) {
            return true;
        }
        for (int i = 0; i < currentSteps.size(); i++) {
            CaseStepInfo old = currentSteps.get(i);
            CaseStepInput submitted = input.steps().get(i);
            int submittedAssert = submitted.isAssert() == null ? 0 : submitted.isAssert();
            if (!Objects.equals(trimToNull(old.description()), trimToNull(submitted.description()))
                    || old.isAssert() != submittedAssert) {
                return true;
            }
        }
        return false;
    }

    /** The invalidation check single-field rule is that a submitted null or blank value means no update and is not considered changed, while non-empty values with unequal trimmed strings are considered changed. */
    private boolean scriptFieldChanged(String submitted, String stored) {
        String trimmed = trimToNull(submitted);
        return trimmed != null && !trimmed.equals(stored);
    }

    /**
     * Field names the save actually changed, in the vocabulary the edge
     * ledger's trigger predicates speak (title, precondition, expectedResult,
     * steps, tags, priority, confidence, endType, phase, assignee). Null in
     * the input means "column untouched" (patch semantics), so it is never
     * reported as changed; identical submitted values are skipped so a no-op
     * save announces an empty diff instead of arming content edges.
     */
    private List<String> changedFieldNames(CaseUpdateInput input, CaseInfo current) {
        List<String> names = new ArrayList<>();
        if (scriptFieldChanged(input.title(), current.title())) {
            names.add("title");
        }
        if (scriptFieldChanged(input.precondition(), current.precondition())) {
            names.add("precondition");
        }
        if (scriptFieldChanged(input.expectedResult(), current.expectedResult())) {
            names.add("expectedResult");
        }
        if (scriptRelevantFieldsChanged(input, current) && input.steps() != null) {
            names.add("steps");
        }
        if (input.tagIds() != null) {
            names.add("tags");
        }
        if (input.priority() != null && input.priority() != current.priority()) {
            names.add("priority");
        }
        if (input.confidence() != null && input.confidence() != current.confidence()) {
            names.add("confidence");
        }
        if (scriptFieldChanged(input.endType(), current.endType())) {
            names.add("endType");
        }
        if (input.phase() != null && !Objects.equals(input.phase(), current.phase())) {
            names.add("phase");
        }
        if (input.assigneeId() != null && !Objects.equals(
                input.assigneeId() == 0L ? null : input.assigneeId(), current.assigneeId())) {
            names.add("assignee");
        }
        return names;
    }

    /**
 * Return the list of non-canceled plan names that reference the case as a case-referenced-by-plan prompt.
     * Called when the front-end edit dialog opens to indicate that the case is referenced by plan xx.
     */
    @Override
    public List<String> checkReferences(long caseId) {
        return jdbcTemplate.query("""
                SELECT p.name FROM tp_plan_case pc
                JOIN tp_exec_plan p ON p.id = pc.plan_id
                WHERE pc.case_id = ? AND p.status <> 5
                ORDER BY p.id
                """, (rs, rowNum) -> rs.getString("name"), caseId);
    }

    /**
 * Case review history for the context sidebar is returned in reverse time order, using display_name for the operator display name and a fallback to username.
 * <p>If the case does not exist, including soft-deleted cases, return a human-readable 404; if there are no records, return an empty list as the sidebar empty-state data source.
     */
    @Override
    public List<CaseReviewLogInfo> listReviewLogs(long caseId) {
        get(caseId); // Reuse 404 semantics by providing a unified human-readable message for nonexistent or deleted records and prohibiting duplicated logic.
        return jdbcTemplate.query("""
                SELECT r.id, r.case_id, r.user_id,
                       COALESCE(NULLIF(u.display_name, ''), u.username) AS operator_name,
                       r.action, r.remark, r.created_at, r.feedback_types
                FROM tp_review_log r
                LEFT JOIN tp_user u ON u.id = r.user_id
                WHERE r.case_id = ?
                ORDER BY r.id DESC
                """,
                (rs, rowNum) -> new CaseReviewLogInfo(
                        rs.getLong("id"), rs.getLong("case_id"), rs.getLong("user_id"),
                        rs.getString("operator_name"), rs.getString("action"),
                        rs.getString("remark"),
                        rs.getObject("created_at", java.time.LocalDateTime.class),
                        readStringArray(rs.getArray("feedback_types"))),
                caseId);
    }

    /** Utility for reading back text[] values for the feedback type column, treating NULL as an empty list to make the front-end display empty state friendly. */
    private static List<String> readStringArray(java.sql.Array array) {
        if (array == null) {
            return List.of();
        }
        try {
            Object[] raw = (Object[]) array.getArray();
            return java.util.Arrays.stream(raw).map(String::valueOf).toList();
        } catch (java.sql.SQLException e) {
            throw new ServiceException("评审问题类型读取失败——请刷新「评审工作台」后重试；持续异常可在「系统配置」核对评审配置项", BizErrorCode.INTERNAL_ERROR);
        }
    }

    /**
 * Version history list for the contract GET /cases/{id}/versions.
     * <p>Fetch all snapshot rows at once using the idx_case_version_case index for case_id, because the snapshot volume equals the number of edits and pagination is unnecessary.
     * changedFieldCount is calculated for adjacent pairs by using the smaller version in the database as the baseline for version row v, with the first row v1 having a null baseline and therefore a count of 0.
 * A corrupted snapshot caused by deserialization failure is marked corrupted=true and changedFieldCount=null, and the baseline for the first subsequent row is missing and set to null.
     */
    @Override
    public List<CaseVersionInfo> listVersions(long caseId) {
        get(caseId); // Reuse 404 semantics by providing a unified human-readable message for nonexistent or deleted records and prohibiting duplicated logic.
        List<VersionRow> rows = jdbcTemplate.query("""
                SELECT v.id, v.version, v.change_type, v.operator_id, v.created_at,
                       v.snapshot_json::text AS snapshot,
                       COALESCE(NULLIF(u.display_name, ''), u.username) AS operator_name
                FROM tp_case_version v
                LEFT JOIN tp_user u ON u.id = v.operator_id
                WHERE v.case_id = ?
                ORDER BY v.version DESC
                """, (rs, n) -> new VersionRow(rs.getLong("id"), rs.getInt("version"),
                rs.getInt("change_type"), rs.getObject("operator_id", Long.class),
                rs.getObject("created_at", LocalDateTime.class), rs.getString("snapshot"),
                rs.getString("operator_name")), caseId);
        // Decode snapshots in ascending order using the previous existing version row as the baseline, because a descending list cannot directly chain baselines, so compute in ascending order and backfill.
        List<VersionRow> asc = new ArrayList<>(rows);
        asc.sort(Comparator.comparingInt(VersionRow::version));
        Map<Integer, CaseVersionInfo> byVersion = new LinkedHashMap<>();
        CaseSnapshot baseline = null;
        for (VersionRow row : asc) {
            CaseSnapshot snapshot;
            try {
                snapshot = CaseSnapshotCodec.fromJson(row.snapshotJson());
            } catch (IllegalArgumentException e) {
                snapshot = null; // For corrupted data, mark it and skip comparison without silently swallowing the error or returning 500.
            }
            // When the baseline is missing because the first row of v1 or the previous row is corrupted, the changed field count is conservatively set to 0, and the frontend displays a dash based on the corrupted flag.
            Integer changed = snapshot == null ? null
                    : baseline == null ? 0 : CaseVersionDiffCalculator.changedFieldCount(baseline, snapshot);
            if (snapshot != null) {
                baseline = snapshot;
            }
            byVersion.put(row.version(), new CaseVersionInfo(row.id(), caseId, row.version(),
                    row.changeType(), row.operatorId(), row.operatorName(), row.createdAt(),
                    changed, snapshot == null));
        }
        return rows.stream().map(r -> byVersion.get(r.version())).toList();
    }

    /**
 * The field-level version diff uses GET /cases/{id}/versions/{v}/diff?baseVersion=.
     * <p> semantics: to is the path target version and from is baseVersion, defaulting to the latest snapshot version to express what changed in the target relative to the state at that time.
 * Return 40401 when the version does not exist, 40001 with a human-readable message when the snapshot is corrupted, and set truncated=true when the span exceeds 20 versions so the frontend prompts selecting adjacent versions.
     */
    @Override
    public CaseVersionDiff diffVersion(long caseId, int version, Integer baseVersion) {
        get(caseId); // Reuse the 404 semantics.
        int toVersion = version;
        int fromVersion = baseVersion == null ? latestVersionOf(caseId) : baseVersion;
        CaseSnapshot to = loadSnapshotForDiff(caseId, toVersion, "目标版本");
        CaseSnapshot from = loadSnapshotForDiff(caseId, fromVersion, "基准版本");
        int span = Math.abs(toVersion - fromVersion);
        return new CaseVersionDiff(caseId, fromVersion, toVersion, span > DIFF_SPAN_LIMIT,
                CaseVersionDiffCalculator.diff(from, to));
    }

    /**
 * The rollback endpoint is POST /cases/{id}/versions/{v}/rollback.
     * <p> Create a new version from the old version content by reusing update field-level semantics and optimistic locking, while the snapshot table is append-only so history cannot be tampered with.
     * Only content fields are restored, status and assignee do not change during rollback due to review state machine constraints described in the interface comments, and scripts are restored with the snapshot.
     * <p> Audit: @Audit action=rollback is registered, and before values are carried through AuditLogContext in the same pattern as update.
     */
    @Audit(objectType = "case", action = "rollback", objectId = "#caseId", after = "#result")
    @Override
    @Transactional
    public CaseRollbackResult rollback(Long operatorId, long caseId, int targetVersion, int expectedVersion) {
        if (expectedVersion < 1) {
            throw new ServiceException("version 必须 ≥ 1（乐观锁）——请携带「用例详情」加载用例时返回的版本号提交；若刚被他人改动，请刷新后重试", BizErrorCode.VALIDATION_ERROR);
        }
        CaseInfo current = get(caseId); // This is the data source for 404 and version conflict messages.
        CaseSnapshot target = loadSnapshotForDiff(caseId, targetVersion, "目标版本");
        if (targetVersion == latestVersionOf(caseId)) {
            throw new ServiceException("目标版本即当前版本，无需回滚——请在「用例详情」版本历史选择当前版本以外的历史版本再回滚", BizErrorCode.VALIDATION_ERROR);
        }
        AuditLogContext.setBefore(AuditSnapshot.snapshot(current));
        // Reuse the update implementation body: replay field-level content, replace steps as a whole, use optimistic locking with 40901, and create snapshot v+1 with changeType=4 while avoiding duplicated logic.
        updateWithChangeType(operatorId, caseId, new CaseUpdateInput(target.title(), target.precondition(),
                target.expectedResult(), target.priority(), target.confidence(), target.endType(),
                target.phase(), CaseSnapshotCodec.toStepInputs(target), null, null),
                expectedVersion, CaseVersionInfo.CHANGE_ROLLBACK);
        restoreScript(caseId, target, operatorId);
        return new CaseRollbackResult(caseId, targetVersion, current.version() + 1, current.version() + 1);
    }

 // This section covers flaky isolation, release, and daily detection.

    /** The default reason for one-click isolation is based on the contract allowing reason to be empty and the standard preflight check skip reason wording isolated (unstable). */
    private static final String FLAKY_DEFAULT_REASON = "人工一键隔离（不稳定）";

    @Audit(objectType = "case", action = "flaky", objectId = "#caseId", after = "#result")
    @Override
    @Transactional
    public CaseInfo setFlaky(Long operatorId, long caseId, boolean isolated, String reason) {
        CaseInfo current = get(caseId); // Apply 404 protection.
        AuditLogContext.setBefore(java.util.Map.of("flaky", current.flaky()));
        if (isolated) {
 // Idempotent: repeated isolation only refreshes the reason and time without accumulating duplicate marks.
            String effective = reason == null || reason.isBlank() ? FLAKY_DEFAULT_REASON : reason.trim();
            jdbcTemplate.update("""
                    UPDATE tp_test_case SET flaky = 1, flaky_reason = ?, flaky_marked_at = now(),
                           version = version + 1, updated_at = now()
                    WHERE id = ? AND deleted = 0
                    """, truncate(effective, 200), caseId);
        } else {
            jdbcTemplate.update("""
                    UPDATE tp_test_case SET flaky = 0, flaky_reason = NULL, flaky_marked_at = NULL,
                           version = version + 1, updated_at = now()
                    WHERE id = ? AND deleted = 0
                    """, caseId);
        }
        return get(caseId);
    }

    @Override
    public com.sixbean.testpike.core.cases.api.dto.FlakyDetectionResult detectFlaky() {
        // Scan cases that have terminal execution records in tp_exec_task status 3, 4, or 5 and are not deleted.
        Map<Long, List<Integer>> statusSeqByCase = new java.util.LinkedHashMap<>();
        jdbcTemplate.query("""
                SELECT case_id, status FROM tp_exec_task
                WHERE status IN (3, 4, 5) AND case_id IN (SELECT id FROM tp_test_case WHERE deleted = 0)
                ORDER BY case_id, COALESCE(finished_at, started_at, created_at), id
                """, (java.sql.ResultSet rs) -> {
                    statusSeqByCase.computeIfAbsent(rs.getLong("case_id"), k -> new java.util.ArrayList<>())
                            .add(rs.getInt("status"));
                });
        Map<Long, Integer> flakyByCase = new java.util.HashMap<>();
        jdbcTemplate.query("SELECT id, flaky FROM tp_test_case WHERE deleted = 0",
                (java.sql.ResultSet rs) -> {
                    flakyByCase.put(rs.getLong("id"), rs.getInt("flaky"));
                });

        int marked = 0;
        int suggestClear = 0;
        for (Map.Entry<Long, List<Integer>> entry : statusSeqByCase.entrySet()) {
            long caseId = entry.getKey();
            boolean currentlyFlaky = flakyByCase.getOrDefault(caseId, 0) == 1;
            com.sixbean.testpike.core.cases.domain.component.FlakyVerdict verdict =
                    flakyRuleCalculator.evaluate(entry.getValue(), currentlyFlaky);
            if (verdict.suggestClear()) {
 // An isolated case whose latest five consecutive results are consistent is recommended for manual release before the marking or refresh decision.
                // Detection does not automatically release; if the case remains flaky, the next window change follows the marking or refresh branch.
                suggestClear++;
            } else if (verdict.flaky() && !currentlyFlaky) {
                jdbcTemplate.update("""
                        UPDATE tp_test_case SET flaky = 1, flaky_reason = ?, flaky_marked_at = now(),
                               updated_at = updated_at
                        WHERE id = ? AND deleted = 0 AND flaky = 0
                        """, truncate(verdict.reason(), 200), caseId);
                marked++;
            } else if (verdict.flaky() && currentlyFlaky) {
                // Rerun is idempotent: if the case is still matched, refresh the reason because the flip count may change, without adding duplicate marks.
                jdbcTemplate.update("UPDATE tp_test_case SET flaky_reason = ? WHERE id = ? AND flaky = 1",
                        truncate(verdict.reason(), 200), caseId);
            }
            // Cases already isolated but no longer meeting the condition and not reaching five consecutive consistent results remain isolated because release is only manual or suggested.
 // suggestClear=true has already been counted in the first branch, so it only suggests release and does not release automatically.
        }
        return new com.sixbean.testpike.core.cases.api.dto.FlakyDetectionResult(
                statusSeqByCase.size(), marked, suggestClear);
    }

 // The excellent case marking and unmarking endpoint is PUT /cases/{id}/excellent.

    @Override
    @Transactional
    public CaseInfo markExcellent(Long operatorId, long caseId, boolean excellent, int expectedVersion) {
        if (expectedVersion < 1) {
            throw new ServiceException("version 必须 ≥ 1（乐观锁）——请携带「用例详情」加载用例时返回的版本号提交；若刚被他人改动，请刷新后重试", BizErrorCode.VALIDATION_ERROR);
        }
        CaseInfo current = get(caseId); // Apply 404 protection by reusing the get semantics.
        if (excellent && current.status() != CaseStatus.APPROVED.code()) {
 // Only passed cases can be marked, unmarking is allowed in any state, and the human-readable message includes the current status label.
            throw new ServiceException("仅已通过用例可标记优秀——请在「评审工作台」先通过该用例再标记（当前状态："
                    + CaseStatus.of(current.status()).label(), BizErrorCode.VALIDATION_ERROR);
        }
        int applied = jdbcTemplate.update("""
                UPDATE tp_test_case SET excellent_flag = ?, version = version + 1, updated_at = now()
                WHERE id = ? AND version = ? AND deleted = 0
                """, excellent ? 1 : 0, caseId, expectedVersion);
        if (applied == 0) {
            CaseInfo latest = get(caseId);
            throw new OptimisticLockingException(
                    "用例已被他人修改（版本冲突），请刷新后重试（当前版本 " + latest.version() + "）");
        }
        // Audit has two actions: the @Audit annotation action is a static string that cannot distinguish mark and unmark, so the audit is recorded manually.
        // Following the recordExportAudit precedent, AuditLogService.record is placed into the hash chain.
        auditLogService.record(new com.sixbean.testpike.core.audit.api.dto.AuditLogInput(
                operatorId, current.projectId(), "case", String.valueOf(caseId),
                excellent ? "excellent-mark" : "excellent-unmark",
                java.util.Map.of("excellent", current.excellent()),
                java.util.Map.of("excellent", excellent ? 1 : 0)));
 // For vector linkage, marking and unmarking both publish REVIEW_CHANGED with action=EXCELLENT.
        // Reuse the case.reviewed route and the ai-service persistence queue without creating a new queue, and the consumer chain fetches the source details.
        // Dispatch the excellent boolean value, including false when unmarked, and caseVersion is the new version after CAS.
        // expectedVersion is incremented by one, and kb-ingest:case:{id}:{version} is the idempotency key used for deduplication.
 // The publisher has built-in afterCommit behavior, so it does not publish on rollback because the conflict path in this method has already been thrown above.
        caseEventPublisher.publish(new CaseEventMessage(
                CaseEventType.REVIEW_CHANGED.name(), caseId, current.projectId(), operatorId,
                "EXCELLENT", null, null, null, null,
                System.currentTimeMillis(), expectedVersion + 1));
        return get(caseId);
    }

    /** Truncate strings to the column width because flaky_reason is VARCHAR(200) and must guard against 500-character overlong values. */
    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** The latest version number is max(version) from the snapshot table, and if no snapshot exists, return 40401 with a human-readable message for legacy cases without snapshots. */
    private int latestVersionOf(long caseId) {
        Integer max = jdbcTemplate.queryForObject(
                "SELECT max(version) FROM tp_case_version WHERE case_id = ?", Integer.class, caseId);
        if (max == null) {
            throw new ServiceException("该用例暂无版本快照（历史存量数据）无法比较——请先在「用例详情」保存一次生成 v1 快照，再发起版本比较", BizErrorCode.NOT_FOUND);
        }
        return max;
    }

 /** Load the snapshot for the specified version for shared use by diff and rollback, returning 40401 when it does not exist and 40001 with a human-readable message when it is corrupted. */
    private CaseSnapshot loadSnapshotForDiff(long caseId, int version, String roleLabel) {
        List<String> jsons = jdbcTemplate.query(
                "SELECT snapshot_json::text FROM tp_case_version WHERE case_id = ? AND version = ?",
                (rs, n) -> rs.getString(1), caseId, version);
        if (jsons.isEmpty()) {
            throw new ServiceException("用例版本 v%d 不存在——请在「用例详情」版本历史列表中选择实际存在的版本号比较/回滚".formatted(version), BizErrorCode.NOT_FOUND);
        }
        try {
            return CaseSnapshotCodec.fromJson(jsons.get(0));
        } catch (IllegalArgumentException e) {
            throw new ServiceException("%s v%d 快照已损坏无法%s——请在「用例详情」改用其他版本，并携带用例号与版本号反馈管理员"
                    .formatted(roleLabel, version, roleLabel.equals("目标版本") ? "回滚" : "比较"),
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    /** The current script is unique by case_id, and when there is no script, return {null,null} to match the snapshot script=null semantics. */
    private ScriptForSnapshot currentScript(long caseId) {
        return jdbcTemplate.query("SELECT schema_version, action_json::text FROM tp_script WHERE case_id = ?",
                (rs, n) -> new ScriptForSnapshot(rs.getString(1), rs.getString(2)),
                caseId).stream().findFirst().orElseGet(() -> new ScriptForSnapshot(null, null));
    }

    /** Convert a snapshot to case details, where the rollback target is the latest snapshot fragment used as the current side of the diff, and because the snapshot does not contain optimistic-lock row metadata, version passes the snapshot sequence. */
    private CaseSnapshot snapshotOf(CaseInfo info) {
        ScriptForSnapshot script = currentScript(info.id());
        return CaseSnapshotCodec.of(info, script.schemaVersion(), script.actionJson());
    }

    /** Rollback restores scripts with the version: if the snapshot contains a script, upsert it; if the snapshot has no script but the current case has one, delete it to fully restore the content.
 * <p> The upsert branch appends a version-line snapshot at the end with reason=rollback reattach, expressing the append-reattach-row semantics.
     * The DELETE branch does not write a snapshot because rolling back to no script makes the current pointer empty while preserving history for assertions. */
    private void restoreScript(long caseId, CaseSnapshot target, Long operatorId) {
        if (target.script() != null) {
            jdbcTemplate.update("""
                    INSERT INTO tp_script (case_id, schema_version, action_json, status, generated_by, version)
                    VALUES (?, ?, ?::jsonb, 1, 1, 1)
                    ON CONFLICT (case_id) DO UPDATE
                    SET schema_version = EXCLUDED.schema_version, action_json = EXCLUDED.action_json,
                        version = tp_script.version + 1, updated_at = now()
                    """, caseId, target.scriptSchemaVersion(), target.script());
            // Snapshot column values are taken from the post-write rows of tp_script and tp_test_case, with case_version equal to the new version produced by rollback.
            scriptVersionSupport.write(caseId, ScriptVersionSupport.REASON_ROLLBACK_RESTORE, operatorId);
        } else {
            jdbcTemplate.update("DELETE FROM tp_script WHERE case_id = ?", caseId);
        }
    }

    /** Represents a version snapshot row as an internal data carrier for decoding a single listVersions query, not an API DTO and not exposed outside the domain. */
    private record VersionRow(long id, int version, int changeType, Long operatorId,
                              LocalDateTime createdAt, String snapshotJson, String operatorName) {
    }

    /** Provides a read carrier for script snapshots that holds schema_version and action_json text. */
    private record ScriptForSnapshot(String schemaVersion, String actionJson) {
    }

    /**
     * Writes a version snapshot, with case_id+version UNIQUE providing natural idempotency.
     * Write only when previous=null for creating v1 or when current content differs from it for edit deduplication; a corrupted snapshot, theoretically unreachable and defensive, does not block the main flow.
     * Treat deduplication comparison failures as changes, preferring extra snapshots over losing history.
     */
    private void writeSnapshot(CaseInfo info, int changeType, Long operatorId, CaseSnapshot previous) {
        CaseSnapshot snapshot = snapshotOf(info);
        if (previous != null) {
            boolean same;
            try {
                same = CaseVersionDiffCalculator.diff(previous, snapshot).isEmpty();
            } catch (Exception e) {
                same = false;
            }
            if (same) {
                return;
            }
        }
        jdbcTemplate.update("""
                INSERT INTO tp_case_version (case_id, version, snapshot_json, change_type, operator_id)
                VALUES (?, ?, ?::jsonb, ?, ?)
                ON CONFLICT (case_id, version) DO NOTHING
                """, info.id(), info.version(), CaseSnapshotCodec.toJson(snapshot), changeType, operatorId);
    }

    /** Soft-deletes a case by setting deleted=1 while retaining data and steps for traceability; it is hidden from list and detail views, and delete permission is enforced on the admin side. */
    @Audit(objectType = "case", action = "delete", objectId = "#id", after = "#result")
    public long delete(Long operatorId, long id) {
        int updated = jdbcTemplate.update(
                "UPDATE tp_test_case SET deleted = 1, updated_at = now() WHERE id = ? AND deleted = 0", id);
        if (updated == 0) {
            throw caseNotFound(id);
        }
 // Cleanup trigger: soft deletion emits case.deleted, and the cleanup path deletes vectors and marks ledger INACTIVE.
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT project_id, source FROM tp_test_case WHERE id = ?", id);
        Long projectId = ((Number) row.get("project_id")).longValue();
        // A delete of AI material is the strongest rejection signal (deprecated,
        // no threshold read); the observer itself gates on source=1 and swallows
        // every ledger failure, so the delete chain stays untouched.
        aiOutputAdoptionObserver.observeDelete(id,
                ((Number) row.get("source")).intValue(), projectId);
        caseEventPublisher.publish(new CaseEventMessage(
                CaseEventType.DELETED.name(), id, projectId, operatorId,
                "DELETE", null, null, null, null, System.currentTimeMillis(), null));
        return id;
    }

    /** Recycle-bin row query SQL uses the same projection for list and single-row entry points to avoid duplicated logic. */
    private static final String RECYCLE_SELECT = """
            SELECT c.id, c.case_no, c.project_id, c.title, c.priority, c.status, c.version,
                   la.operator_id AS deleted_by,
                   COALESCE(NULLIF(u.display_name, ''), u.username) AS deleted_by_name,
                   la.created_at AS deleted_at
            FROM tp_test_case c
            LEFT JOIN LATERAL (
                SELECT operator_id, created_at FROM tp_audit_log
                WHERE object_type = 'case' AND object_id = c.id::text AND action = 'delete'
                ORDER BY id DESC LIMIT 1) la ON true
            LEFT JOIN tp_user u ON u.id = la.operator_id
            """;

    private CaseRecycleInfo mapRecycleRow(ResultSet rs, int rowNum) throws SQLException {
        // wasNull must be read immediately after the target column because JDBC semantics reflect the last get, and reading later columns overwrites the determination.
        long deletedById = rs.getLong("deleted_by");
        boolean deletedByNull = rs.wasNull();
        return new CaseRecycleInfo(rs.getLong("id"), rs.getString("case_no"),
                rs.getLong("project_id"), rs.getString("title"), rs.getInt("priority"),
                rs.getInt("status"), rs.getInt("version"),
                deletedByNull ? null : deletedById, rs.getString("deleted_by_name"),
                rs.getObject("deleted_at", java.time.LocalDateTime.class));
    }

    /**
 * Recycle-bin list endpoint GET /cases/recycle-bin returns paginated deleted=1 rows with isolation.
     * No new table is introduced; the deleting user and deletion time are obtained by reverse lookup of the latest audit action=delete record using LATERAL, treating audit as the sole trace source.
     * operator_id is persisted from the X-Operator-Id header through an aspect; existing directly created soft-deleted rows without audit records keep the two columns null and remain safe due to LEFT JOIN.
 * keyword matches case_no and title using the shared LikePattern component with escaping, and results are ordered by deleted_at descending.
     * Rows without audit records are placed last using NULLS LAST.
     */
    public PageResult<CaseRecycleInfo> recycleBin(long projectId, String keyword, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = PageQueryPolicy.enforceSize(size);
        int offset = (int) PageQueryPolicy.clampOffset(safePage, safeSize, MAX_OFFSET);

        StringBuilder where = new StringBuilder(" WHERE c.deleted = 1 AND c.project_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(projectId);
        String kw = LikePattern.normalize(keyword);
        if (kw != null) {
            String pattern = "%" + LikePattern.escape(kw) + "%";
            where.append(" AND (c.case_no ILIKE ? ESCAPE '\\' OR c.title ILIKE ? ESCAPE '\\')");
            args.add(pattern);
            args.add(pattern);
        }
        Long total = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_test_case c" + where, Long.class, args.toArray());

        List<Object> listArgs = new ArrayList<>(args);
        listArgs.add(safeSize);
        listArgs.add(offset);
        List<CaseRecycleInfo> list = jdbcTemplate.query(
                RECYCLE_SELECT + where + " ORDER BY la.created_at DESC NULLS LAST, c.id DESC LIMIT ? OFFSET ?",
                this::mapRecycleRow, listArgs.toArray());
        return new PageResult<>(list, total != null ? total : 0L);
    }

    /** Single-source recycle-bin row lookup: reused by both recycleEntry and the typed not-found message. */
    public Optional<CaseRecycleInfo> findRecycleEntry(long id) {
        return jdbcTemplate.query(
                RECYCLE_SELECT + " WHERE c.id = ? AND c.deleted = 1", this::mapRecycleRow, id).stream().findFirst();
    }

    /** Recycle-bin single-row entry performs admin permission validation before querying, returns a human-readable 404 for active or nonexistent rows, and exposes the soft-deleted row's projectId. */
    public CaseRecycleInfo recycleEntry(long id) {
        return findRecycleEntry(id).orElseThrow(() -> new ServiceException(
                "用例不存在或不在回收站——活跃用例请在「用例库」直接打开，仅「回收站」内的用例可恢复", BizErrorCode.NOT_FOUND));
    }

    /** Typed not-found rejection for case ids: the recycle-bin pointer is offered only when a soft-delete
     *  record really exists (pointing at an empty recycle bin would be a dead end); otherwise the message
     *  names the id and points to the case-library search as the recoverable path. */
    private ServiceException caseNotFound(long id) {
        if (findRecycleEntry(id).isPresent()) {
            return new ServiceException("用例已被删除（id=" + id + "）——可在「用例库」页的「回收站」中恢复后继续查看",
                    BizErrorCode.NOT_FOUND);
        }
        return new ServiceException("用例不存在（id=" + id + "）——库中无此记录也无删除痕迹，请到「用例库」页按用例编号或标题搜索确认；"
                + "若从 AI 生成结果跳转，请以「用例库」列表中的实际产出为准", BizErrorCode.NOT_FOUND);
    }

    /**
 * Restores a soft-deleted case via PUT /cases/{id}/restore.
     * version optimistic-lock mismatch returns a human-readable 40901 containing the current version; restore does not bump version because undoing deletion is not a content change.
 * version not reset means neither reset nor appended, and case_no is naturally retained without changing columns.
     * Concurrent fallback uses an UPDATE with both version and deleted conditions, throwing 40901 when affected rows are 0, and audit records action=restore.
     */
    @Audit(objectType = "case", action = "restore", objectId = "#id", after = "#result")
    public CaseInfo restore(Long operatorId, long id, int expectedVersion) {
        CaseRecycleInfo entry = recycleEntry(id); // Returns a human-readable 404 when the case does not exist or is not soft-deleted.
        if (entry.version() != expectedVersion) {
            throw new OptimisticLockingException(
                    "用例版本已变更（当前版本 " + entry.version() + "），请刷新回收站后重试");
        }
        int updated = jdbcTemplate.update(
                "UPDATE tp_test_case SET deleted = 0, updated_at = now() WHERE id = ? AND version = ? AND deleted = 1",
                id, expectedVersion);
        if (updated == 0) {
            throw new OptimisticLockingException("用例已被他人修改（版本冲突），请刷新后重试");
        }
 // Restore rebuild: restoring an approved case directly emits case.reviewed with action=PASS.
        // Reuses the existing CaseIngestConsumer to persist to database again, with idempotency key kb-ingest:case:{id}:{version} providing natural deduplication.
        // Do not emit the event when restoring a case that is not approved, because unapproved cases are not persisted to database.
        if (entry.status() == CaseStatus.APPROVED.code()) {
            caseEventPublisher.publish(new CaseEventMessage(
                    CaseEventType.REVIEW_CHANGED.name(), id, entry.projectId(), operatorId,
                    ReviewAction.PASS.name(), null, null, null, null,
                    System.currentTimeMillis(), entry.version()));
        }
        return get(id);
    }

    /**
 * Permanently deletes recycle-bin cases by reusing CasePurgeSupport to cascade-clean associated rows along the same path as project purge.
     * Only soft-deleted rows can be purged, returning a human-readable 404 for active cases; the main row and dependent rows are physically deleted in the same transaction according to the foreign-key cleanup list.
     * Audit rows are retained for traceability under the global append-only rule.
     */
    @Audit(objectType = "case", action = "purge", objectId = "#id", after = "")
    @Transactional
    public long purge(Long operatorId, long id) {
        recycleEntry(id); // Defines 404 semantics and restricts purge to recycle-bin rows only.
        casePurgeSupport.cleanByCaseIds(List.of(id));
        jdbcTemplate.update("DELETE FROM tp_test_case WHERE id = ?", id);
        return id;
    }

    /**
 * Clears the recycle bin for the clear button, with delete permission validated on the admin side using the same criteria.
     * Physically deletes only deleted=1 rows in the current project, including cascade cleanup, leaving active data unaffected; clearing an empty recycle bin returns 0 idempotently.
     */
    @Audit(objectType = "case", action = "purge", objectId = "'recycle-bin:' + #projectId", after = "#result")
    @Transactional
    public long clearRecycleBin(Long operatorId, long projectId) {
        List<Long> ids = jdbcTemplate.queryForList(
                "SELECT id FROM tp_test_case WHERE project_id = ? AND deleted = 1", Long.class, projectId);
        if (ids.isEmpty()) {
            return 0L;
        }
        casePurgeSupport.cleanByCaseIds(ids);
        int cleared = jdbcTemplate.update(
                "DELETE FROM tp_test_case WHERE project_id = ? AND deleted = 1", projectId);
        return cleared;
    }


    /**
 * Review action implements POST /cases/{id}/review as a subset of the planned state machine.
     * Flow: validate 404, validate state-machine transitions with 40902 for terminal or disallowed states, then require review comments.
 * The requirement is configurable via review.remark-required, followed by an optimistic-lock UPDATE that returns 40901 on version conflict.
 * Appends a review record to tp_review_log for traceability.
 * Review permissions are validated on the admin side, returning 403 for users who are neither assignees nor developers, while this service focuses on state-machine and data correctness.
     *
     * @return review result containing caseId, status, and version as response data.
     */
    @Audit(objectType = "case", action = "review", objectId = "#id", after = "#result")
    @Transactional
    public ReviewResult review(Long operatorId, long id, ReviewAction action, String remark, int expectedVersion) {
        return review(operatorId, id, action, remark, expectedVersion, null);
    }

 /** Feedback intake stage: reject or need_change requires a problem type, while pass may be empty and is not falsely blocked. */
    @Override
    @Audit(objectType = "case", action = "review", objectId = "#id", after = "#result")
    @Transactional
    public ReviewResult review(Long operatorId, long id, ReviewAction action, String remark,
                               int expectedVersion, List<String> feedbackTypes) {
        if (expectedVersion < 1) {
            throw new ServiceException("version 必须 ≥ 1（乐观锁）——请携带「用例详情」加载用例时返回的版本号提交；若刚被他人改动，请刷新后重试", BizErrorCode.VALIDATION_ERROR);
        }
        CaseInfo current = get(id); // Defines 404 semantics and returns the current row for use in version-conflict messages.
        int target = CaseStatusMachine.transition(current.status(), action.event()); // 40902
        String remarkText = trimToNull(remark);
        if (remarkText != null && remarkText.length() > 500) {
            throw new ServiceException("评审意见不超过 500 字——请在「评审工作台」评审表单精简「评审意见」后提交（上限 500 字）", BizErrorCode.VALIDATION_ERROR);
        }
        if (remarkRequired && action != ReviewAction.PASS && remarkText == null) {
            throw new ServiceException("评审意见不能为空：需修改/拒绝须说明原因——请在「评审工作台」评审表单填写「评审意见」后再提交（正确写法示例：步骤3缺少断言，请补充预期结果校验）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        List<String> typeLabels = requireFeedbackTypes(action, feedbackTypes); // /
        int updated = jdbcTemplate.update("""
                UPDATE tp_test_case SET status = ?, version = version + 1, updated_at = now()
                WHERE id = ? AND version = ? AND deleted = 0
                """, target, id, expectedVersion);
        if (updated == 0) {
            // If get(id) returns approved, only a version mismatch can occur due to concurrent submission, matching the optimistic-lock semantics.
            throw new OptimisticLockingException(
                    "用例已被他人修改（版本冲突），请刷新后重试（当前版本 " + current.version() + "）");
        }
        Long caseReviewLogId = insertReviewLog(id, operatorId, action.label(), remarkText, typeLabels);
        recordCaseReviewOutcome(caseReviewLogId, id, action, current.projectId());
 // A review status change writes a snapshot with change_type=2, with content deduplication guaranteed by writeSnapshot.
        writeSnapshot(get(id), CaseVersionInfo.CHANGE_REVIEW, operatorId, snapshotOf(current));
 // Review side effect: emits the REVIEW_CHANGED event after transaction commit and does not emit it on rollback.
 // caseVersion is the new version after the optimistic-lock UPDATE (expectedVersion+1) and is the basis for the idempotent key of the G1 ingestion pipeline.
        caseEventPublisher.publish(new CaseEventMessage(
                CaseEventType.REVIEW_CHANGED.name(), id, current.projectId(), operatorId,
                action.name(), remarkText, null, null, null, System.currentTimeMillis(),
                expectedVersion + 1));
        return new ReviewResult(id, target, expectedVersion + 1);
    }

    /**
     * Batch review (interface design section 3.2) performs per-item state-machine validation, skips and lists conflicting items, and is non-atomic unlike batch assignment's full rollback.
 * Skip reasons (succeeded/skipped semantics, /13) include terminal state or disallowed state (40902 plain-language).
     * Skip reasons include concurrent state changed (optimistic protection, does not overwrite another reviewer's result), case not found, or cross-project (H1 isolation).
 * Remark validation (need_change/reject and remark-required) is a parameter-level error that returns an overall 400 (configurable).
     * Audit: object_id stores a "batch:{n}" summary (same pattern as batch assignment, with details fully retained in after_json).
     */
    @Audit(objectType = "case", action = "review",
            objectId = "'batch:' + #request.caseIds()?.size()", after = "#result")
    @Transactional
    public ReviewBatchResult reviewBatch(Long operatorId, ReviewBatchRequest request) {
        if (request == null || request.projectId() == null) {
            throw new ServiceException("批量评审参数不完整：缺少项目 ID——请在顶栏选择当前项目后从「评审工作台」发起批量评审", BizErrorCode.VALIDATION_ERROR);
        }
        ReviewAction action = ReviewAction.from(request.action()); // Invalid action returns 400 (boundary).
        List<Long> ids = normalizeCaseIds(request.caseIds()); // Non-empty, deduplicated, and capped at 100.
        projectCode(request.projectId()); // Project not found returns 40401 (H1 ownership dimension).
        String remarkText = trimToNull(request.remark());
        if (remarkText != null && remarkText.length() > 500) {
            throw new ServiceException("评审意见不超过 500 字——请在「评审工作台」评审表单精简「评审意见」后提交（上限 500 字）", BizErrorCode.VALIDATION_ERROR);
        }
        if (remarkRequired && action != ReviewAction.PASS && remarkText == null) {
            throw new ServiceException("评审意见不能为空：需修改/拒绝须说明原因——请在「评审工作台」评审表单填写「评审意见」后再提交（正确写法示例：步骤3缺少断言，请补充预期结果校验）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        List<String> typeLabels = requireFeedbackTypes(action, request.feedbackTypes()); // Parameter-level validation returns an overall 400 (same structure as remark).
        List<Long> succeeded = new ArrayList<>(ids.size());
        List<SkippedReviewItem> skipped = new ArrayList<>();
        for (long id : ids) {
            String reason = tryReviewOne(operatorId, request.projectId(), id, action, remarkText, typeLabels);
            if (reason == null) {
                succeeded.add(id);
            } else {
                skipped.add(new SkippedReviewItem(id, reason));
            }
        }
        return new ReviewBatchResult(succeeded, skipped);
    }

    /**
 * Batch edit (N4) covers phase, priority, and tags, and returns a result report with H1 isolation.
     * Per-item validation is executed non-atomically—N successes and M failures with per-item plain-language reasons; skipped items are cases that do not exist, are deleted, or are cross-project.
     * Parameter-level validation (missing project, empty ids, no edit fields, phase out of range, priority out of range, or cross-project tags) returns an overall 400.
     * To prevent H1 leakage, tags can be batch-set only if they belong to the project; tags are set in replace style (empty list means clear, same semantics as single-item tags).
     * Audit: object_id stores a "batch:{n}" summary (same pattern as batch review, with details fully retained in after_json).
     */
    @Audit(objectType = "case", action = "case_batch_edit",
            objectId = "'batch:' + #request.caseIds()?.size()", after = "#result")
    @Transactional
    public BatchEditResult batchEdit(Long operatorId, BatchEditRequest request) {
        if (request == null || request.projectId() == null) {
            throw new ServiceException("批量编辑参数不完整：缺少项目 ID——请在顶栏选择当前项目后从「用例库」勾选用例发起批量编辑", BizErrorCode.VALIDATION_ERROR);
        }
        List<Long> ids = normalizeCaseIds(request.caseIds());
        projectCode(request.projectId()); // Project not found returns 40401 (H1 ownership dimension).
        boolean hasField = request.phase() != null || request.priority() != null || request.tagIds() != null;
        if (!hasField) {
            throw new ServiceException("请至少选择一项编辑内容——请在「用例库」批量编辑弹窗修改「阶段」「优先级」「标签」任一字段后提交", BizErrorCode.VALIDATION_ERROR);
        }
        validatePhase(request.phase());
        if (request.priority() != null) {
            validatePriority(request.priority());
        }
        if (request.tagIds() != null && request.tagIds().size() > 50) {
            throw new ServiceException("标签单次最多设置 50 个——请在「用例详情」标签区减少勾选后保存（单次上限 50）", BizErrorCode.VALIDATION_ERROR);
        }
        List<Long> tagIds = request.tagIds() == null ? List.of() : request.tagIds();
        validateProjectTags(request.projectId(), tagIds); // Tag project-ownership validation returns an overall 400 for cross-project tags (H1) to prevent leaking the existence of other projects' tags.
        List<Long> succeeded = new ArrayList<>(ids.size());
        List<BatchEditFailureItem> failures = new ArrayList<>();
        for (long id : ids) {
            String reason = tryEditOne(request.projectId(), id, request.phase(), request.priority(), tagIds);
            if (reason == null) {
                succeeded.add(id);
            } else {
                failures.add(new BatchEditFailureItem(id, reason));
            }
        }
        return new BatchEditResult(succeeded, failures);
    }

    /** Batch edit single-item processing returns null on success and returns a plain-language reason on skip (no exception is thrown—non-atomic skip semantics). */
    private String tryEditOne(long projectId, long id, Integer phase, Integer priority, List<Long> tagIds) {
        CaseInfo current;
        try {
            current = get(id); // 404 semantics (not found or deleted) result in skip.
        } catch (ServiceException e) {
            return "用例不存在或已被删除";
        }
        if (current.projectId() != projectId) {
            return "用例不属于当前项目（H1 项目隔离）";
        }
        jdbcTemplate.update("""
                UPDATE tp_test_case SET phase = COALESCE(?, phase), priority = COALESCE(?, priority),
                       version = version + 1, updated_at = now()
                WHERE id = ? AND deleted = 0
                """, phase, priority, id);
        if (tagIds != null) {
            // This setting is replace-style (same semantics as PUT /cases/{id}/tags).
            replaceCaseTags(id, tagIds);
        }
        return null;
    }

    /** Batch review single-item processing returns null on success and returns a plain-language reason on skip (no exception is thrown—non-atomic skip semantics). */
    private String tryReviewOne(Long operatorId, long projectId, long id,
                                 ReviewAction action, String remark, List<String> typeLabels) {
        CaseInfo current;
        try {
            current = get(id); // 404 semantics (not found or deleted) result in skip.
        } catch (ServiceException e) {
            return "用例不存在或已被删除";
        }
        if (current.projectId() != projectId) {
            return "用例不属于当前项目（H1 项目隔离）";
        }
        int target;
        try {
            target = CaseStatusMachine.transition(current.status(), action.event()); // 40902 results in skip.
        } catch (ServiceException e) {
            return e.getMessage(); // Terminal state or disallowed state uses skip semantics (plain-language reason already included).
        }
        // Optimistic concurrency protection uses conditional state updates (0 rows updated means a concurrent review changed the state, so skip without overwriting others' results).
        int updated = jdbcTemplate.update("""
                UPDATE tp_test_case SET status = ?, version = version + 1, updated_at = now()
                WHERE id = ? AND deleted = 0 AND status = ?
                """, target, id, current.status());
        if (updated == 0) {
            return "用例状态已变更，请刷新后重试";
        }
        Long batchReviewLogId = insertReviewLog(id, operatorId, action.label(), remark, typeLabels);
        recordCaseReviewOutcome(batchReviewLogId, id, action, current.projectId());
 // Q1: batch review has the same semantics as single-item review—state-change snapshots use change_type=2 (deduplication protection, no logic branching allowed).
        writeSnapshot(get(id), CaseVersionInfo.CHANGE_REVIEW, operatorId, snapshotOf(current));
 // Review side effect: emits the REVIEW_CHANGED event after transaction commit and does not emit it on rollback.
 // caseVersion is the new version after the optimistic UPDATE (version+1) and is the basis for the idempotent key of the G1 ingestion pipeline.
        caseEventPublisher.publish(new CaseEventMessage(
                CaseEventType.REVIEW_CHANGED.name(), id, current.projectId(), operatorId,
                action.name(), remark, null, null, null, System.currentTimeMillis(),
                current.version() + 1));
        return null;
    }

    /**
 * D1 feedback issue-type validation is a single source and follows red line 6.
     * For reject or need_change, required feedback types return 400 with the plain-language message "please select issue type" including the six-category list; pass may be empty without false rejection.
     * Out-of-enum values cause FeedbackType.labelsOf to throw 40001 with the valid list; it returns an order-preserving deduplicated list of Chinese labels, and the value persisted to database follows the same convention as the action column.
     */
    private List<String> requireFeedbackTypes(ReviewAction action, List<String> feedbackTypes) {
        List<String> labels = FeedbackType.labelsOf(feedbackTypes);
        if (labels.isEmpty() && action != ReviewAction.PASS) {
            throw new ServiceException("请选择问题类型（可多选）——请在「评审工作台」拒绝表单勾选至少一个问题类型（当前可选：" + FeedbackType.labelList(),
                    BizErrorCode.VALIDATION_ERROR);
        }
        return labels;
    }

    /** Review audit trail insertion (shared by single and batch paths, red line 6 prohibits duplication) stores feedback_types text[] as NULL when the list is empty. Returns the generated row id so the outcome ledger can key the verdict event. */
    private Long insertReviewLog(long caseId, Long operatorId, String actionLabel,
                                 String remark, List<String> typeLabels) {
        org.springframework.jdbc.support.GeneratedKeyHolder holder =
                new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbcTemplate.update((java.sql.Connection con) -> {
            java.sql.PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO tp_review_log (case_id, user_id, action, remark, feedback_types) VALUES (?, ?, ?, ?, ?)",
                    new String[]{"id"});
            ps.setLong(1, caseId);
            ps.setObject(2, operatorId);
            ps.setString(3, actionLabel);
            ps.setString(4, remark);
            if (typeLabels == null || typeLabels.isEmpty()) {
                ps.setNull(5, java.sql.Types.ARRAY, "text[]");
            } else {
                java.sql.Array arr = con.createArrayOf("text", typeLabels.toArray());
                ps.setArray(5, arr);
            }
            return ps;
        }, holder);
        java.lang.Number key = holder.getKey();
        return key == null ? null : key.longValue();
    }

    /**
 * Feeds one case review verdict to the outcome ledger ( item 7 wiring;
 * a single intake helper serves both the single and the batch path — red
 * line 6 forbids a second copy). The intake swallows its own failures, so
 * the review flow is never blocked by the ledger.
 */
    private void recordCaseReviewOutcome(Long reviewLogId, long caseId,
                                         ReviewAction action, Long projectId) {
        behaviorOutcomeIntake.recordReviewVerdict(
                com.sixbean.testpike.core.behavior.domain.component.OutcomeChannel.CASE_REVIEW,
                reviewLogId,
                com.sixbean.testpike.core.behavior.domain.component.BehaviorOutcomeIntake
                        .reviewOutcome(action.name()),
                String.valueOf(caseId), projectId);
    }

    /**
 * Single-item assignment is described in interface design section 3.6, PRD 4.6, and PLAN.
     * Rule: only pending review (1) or in review (2) can be assigned (40001; member handoff returns only these two states to the pool).
 * The assignment target must be a project member (40001), and assignment does not change status or version.
     * Case not found returns 40401; assignment permission is validated on the admin side (project owner or system administrator; developer or regular member returns 403).
     */
    @Audit(objectType = "case", action = "assign", objectId = "#caseId", after = "#result")
    public AssignResult assign(Long operatorId, long caseId, Long assigneeId) {
        if (assigneeId == null) {
            throw new ServiceException("分配目标不能为空：单条分配必须指定项目成员——请在「用例库」分配弹窗选择「分配对象」（仅限本项目成员）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        AssignResult result = assignOne(operatorId, caseId, assigneeId);
 // Side effect: assignment emits an ASSIGNED event (assignOne already guarantees the case exists; a lightweight query retrieves projectId once for H1 propagation).
        Long projectId = jdbcTemplate.queryForObject(
                "SELECT project_id FROM tp_test_case WHERE id = ? AND deleted = 0", Long.class, caseId);
        caseEventPublisher.publish(new CaseEventMessage(
                CaseEventType.ASSIGNED.name(), caseId, projectId, operatorId,
                "ASSIGN", null, assigneeId, null, null, System.currentTimeMillis(), null));
        return result;
    }

    /**
 * Batch assignment (interface design section 3.6) is atomic and rolls back entirely if any item fails, as noted in //06.
 * If {@code assigneeId} is empty, automatic load balancing is used (prioritize fewest unreviewed items, member difference ≤1, /08).
     * For each item, select the project member with the fewest currently unreviewed cases (unreviewed count = number of cases under the member with status in (1,2)).
     * Ties are ordered by ascending user_id for determinism; load-balancing counts are retrieved once by SQL grouping and decremented in memory to avoid N×M queries.
     * Audit: object_id stores a "batch:{n}" summary because tp_audit_log.object_id is varchar(64).
     * A list toString would exceed the length and be rejected—a real defect fixed on 2026-08-30; per-item details are fully retained in after_json.
     */
    @Audit(objectType = "case", action = "assign",
            objectId = "'batch:' + #caseIds.size()", after = "#result")
    @Transactional
    public AssignBatchResult assignBatch(Long operatorId, long projectId, List<Long> caseIds, Long assigneeId) {
        projectCode(projectId); // Project not found returns 40401 (H1 ownership dimension).
        List<Long> ids = normalizeCaseIds(caseIds); // Non-empty, deduplicated, and capped at 100.
        List<CaseInfo> rows = listByIds(ids);
        if (rows.size() != ids.size()) {
            throw new ServiceException("批量分配存在不存在的用例已整体回滚——请在「用例库」刷新列表后重新勾选在用用例再分配", BizErrorCode.NOT_FOUND);
        }
        for (CaseInfo row : rows) {
            if (row.status() != 1 && row.status() != 2) {
                throw new ServiceException("用例 %s 状态不允许分配：仅待评审/评审中可分配——请在「用例库」刷新确认状态后只勾选这两种状态的用例"
                        + "（已整体回滚）", BizErrorCode.VALIDATION_ERROR, row.caseNo());
            }
        }
        List<Long> assignees = new ArrayList<>(ids.size());
        if (assigneeId != null) {
            requireMember(projectId, assigneeId); // Return 40301 for cross-project or non-member cases as a fail-fast check.
            requireTester(assigneeId);            // Return 40301 for developers as a fail-fast check.
            for (Long ignored : ids) {
                assignees.add(assigneeId);
            }
        } else {
            assignees.addAll(autoAssignees(projectId, rows)); // Automatic load balancing uses only tester candidates.
        }
        List<AssignResult> assigned = new ArrayList<>(ids.size());
        for (int i = 0; i < ids.size(); i++) {
            assigned.add(assignOne(operatorId, ids.get(i), assignees.get(i)));
        }
 // As a side effect, batch assignment emits a BATCH_ASSIGNED summary event while fully preserving the detailed caseIds.
        caseEventPublisher.publish(new CaseEventMessage(
                CaseEventType.BATCH_ASSIGNED.name(), null, projectId, operatorId,
                "BATCH_ASSIGN", null, assigneeId, ids, ids.size(), System.currentTimeMillis(), null));
        return new AssignBatchResult(assigned);
    }

    // This section contains internal assignment utilities.

    /** Fetch cases by ID in batch, filtering out nonexistent or deleted cases, and reuse the query in assignBatch and requirement-associated case lists to avoid duplicate SQL. */
    @Override
    public List<CaseInfo> listByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM tp_test_case WHERE id IN ("
                        + SqlPlaceholders.in(ids.size()) + ") AND deleted = 0",
                this::mapRow, ids.toArray());
    }

    /** Execute a single assignment with validation and UPDATE, sharing the logic between single and batch item-by-item assignment to prohibit duplicate logic. */
    private AssignResult assignOne(Long operatorId, long caseId, Long assigneeId) {
        CaseInfo current = get(caseId); // This section covers 404 semantics.
        if (current.status() != 1 && current.status() != 2) {
            throw new ServiceException("用例 %s 状态不允许分配：仅待评审/评审中可分配——请在「用例库」刷新确认状态后只勾选这两种状态的用例",
                    BizErrorCode.VALIDATION_ERROR, current.caseNo());
        }
        requireMember(current.projectId(), assigneeId); // Cross-project requests return 403.
        requireTester(assigneeId);                       // Developer requests return 403.
        int updated = jdbcTemplate.update(
                "UPDATE tp_test_case SET assignee_id = ?, assigned_at = now(), updated_at = now()"
                        + " WHERE id = ? AND deleted = 0",
                assigneeId, caseId);
        if (updated == 0) {
            throw caseNotFound(caseId);
        }
        return new AssignResult(caseId, assigneeId, current.status());
    }

    /** Automatic load balancing returns a target member list with the same length as rows, with the candidate pool and greedy dispatch sharing AutoAssignSupport.
     * Use a single source of truth with the automatic assignment endpoint and prohibit copying, restricting candidates to testers or test leads. */
    private List<Long> autoAssignees(long projectId, List<CaseInfo> rows) {
        List<com.sixbean.testpike.core.cases.domain.component.AutoAssignCandidate> candidates =
                autoAssignSupport.candidates(projectId);
        if (candidates.isEmpty()) {
            throw new ServiceException("项目暂无测试人员可分配——请先到「项目详情」的「成员管理」页添加「测试人员」角色成员，再回「用例库」分配",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return autoAssignSupport.dispatch(candidates, rows.size()).stream()
                .map(com.sixbean.testpike.core.cases.domain.component.AutoAssignCandidate::userId)
                .toList();
    }

    /**
 * The assignment target must be a member of the current project, requiring a tp_user_project record, and cross-project or non-member cases return 40301.
     * Return a human-readable message stating that the user is not a member of this project, with isolation.
     */
    private void requireMember(long projectId, long userId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_user_project WHERE project_id = ? AND user_id = ?",
                Integer.class, projectId, userId);
        if (count == null || count == 0) {
            throw new ServiceException("该用户不是本项目成员——请先在「项目详情」的「成员管理」页将其加入项目，再回「用例库」分配", BizErrorCode.FORBIDDEN);
        }
    }

    /**
 * The assignment target must be a tester or test lead, with developers and other roles returning 40301.
     * Use a human-readable message that assignment is only to testers of this project, treating tp_user_role to tp_role.code as authoritative with fallback to the old tp_user.role enum values 1 and 2.
     */
    private void requireTester(long userId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM tp_user u
                LEFT JOIN tp_user_role ur ON ur.user_id = u.id
                LEFT JOIN tp_role r ON r.id = ur.role_id AND r.status = 1
                WHERE u.id = ?
                  AND (r.code IN ('tester', 'test_lead') OR u.role IN (1, 2))
                """, Integer.class, userId);
        if (count == null || count == 0) {
            throw new ServiceException("仅可分配给本项目测试人员——请在「用例库」分配弹窗选择本项目成员（成员在「项目详情」的「成员管理」页维护）", BizErrorCode.FORBIDDEN);
        }
    }

    /**
 * Tags must belong to the same project as the case, are shared by batch editing, and cross-project tags are rejected with 400 as a whole.
 * This prevents leaking the existence of tags in other projects, and an empty list naturally passes with clear semantics.
     */
    private void validateProjectTags(long projectId, List<Long> tagIds) {
        if (tagIds == null || tagIds.isEmpty()) {
            return;
        }
        Object[] args = new Object[tagIds.size() + 1];
        args[0] = projectId;
        for (int i = 0; i < tagIds.size(); i++) {
            args[i + 1] = tagIds.get(i);
        }
        Long matched = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM tp_tag_dict WHERE project_id = ? AND id IN (%s)
                """.formatted(SqlPlaceholders.in(tagIds.size())),
                Long.class, args);
        if (matched == null || matched != tagIds.size()) {
            throw new ServiceException("标签不属于当前项目——请在「用例详情」标签下拉中选择当前项目的标签（标签在项目内维护，跨项目不可见）", BizErrorCode.VALIDATION_ERROR);
        }
    }

    /** Overwrite case tags by clearing them first and batch inserting, using idempotent ON CONFLICT with the same semantics as PUT /cases/{id}/tags.
     * Reuse this logic in create, edit, and batch edit to prohibit duplicate logic. */
    private void replaceCaseTags(long caseId, List<Long> tagIds) {
        jdbcTemplate.update("DELETE FROM tp_case_tag WHERE case_id = ?", caseId);
        if (tagIds == null || tagIds.isEmpty()) {
            return;
        }
        Object[] args = new Object[tagIds.size() * 2];
        for (int i = 0; i < tagIds.size(); i++) {
            args[i * 2] = caseId;
            args[i * 2 + 1] = tagIds.get(i);
        }
        jdbcTemplate.update("""
                INSERT INTO tp_case_tag (case_id, tag_id) VALUES %s ON CONFLICT DO NOTHING
                """.formatted(String.join(",", Collections.nCopies(tagIds.size(), "(?, ?)"))), args);
    }

    /** Normalize the case ID list by requiring non-empty, deduplicating while preserving order, and limiting it to 100 as a batch operation payload limit independent from list size clamping. */
    private List<Long> normalizeCaseIds(List<Long> caseIds) {
        if (caseIds == null || caseIds.isEmpty()) {
            throw new ServiceException("caseIds 不能为空——请在「用例库」勾选至少一条用例后再发起批量操作", BizErrorCode.VALIDATION_ERROR);
        }
        if (caseIds.size() > 100) {
            throw new ServiceException("批量操作单次最多 100 条——请在「用例库」减少勾选数量（上限 100）后分批提交", BizErrorCode.VALIDATION_ERROR);
        }
        LinkedHashSet<Long> unique = new LinkedHashSet<>(caseIds);
        return new ArrayList<>(unique);
    }

    /**
 * Case cloning is implemented by reusing the create path.
 * The target project new number, UNIQUE fallback, v1 snapshot, and audit are naturally obtained, and quickExecute composition reuses the same pattern.
     * Validate in order, with fail-fast before any writes, and return 404 for source case get because soft deletion is naturally covered.
     * For cross-project cloning, return a human-readable 404 if the target project does not exist, reusing projectCode, and return 403 if the operator is not a member of the target project.
     * The copied content includes fields, steps field by field, and tags, with cross-project tags remapped by target dictionary name and silently not attached when no matching name exists.
     * Execution history, defects, reviews, scripts, and plan batch associations are not copied, producing a clean copy documented in the interface javadoc.
     */
    @Override
    @Transactional
    public CaseInfo clone(Long operatorId, long id, Long targetProjectId) {
        CaseInfo source = get(id); // Return a human-readable 404 first when the source is soft-deleted or nonexistent.
        boolean sameProject = targetProjectId == null || targetProjectId == source.projectId();
        long targetPid = sameProject ? source.projectId() : targetProjectId;
        Long assigneeId;
        List<Long> tagIds;
        if (sameProject) {
            assigneeId = source.assigneeId(); // Retain the assignee for same-project cloning, and create revalidation must pass.
            tagIds = tagIdsOf(source.id());
        } else {
            projectCode(targetPid); // Return a human-readable 404 naming the project when the target project does not exist, before authorization checks.
            requireMember(targetPid, requireOperator(operatorId)); // Return 403 for non-members and perform zero writes.
            assigneeId = null; // Cross-project items are not attached because target membership is not verified.
            tagIds = remapTagIds(source.id(), targetPid);
        }
 // Automation target three-column carrying semantics means same-project cloning carries them as a whole, including profiles and engines.
        // Project assets are attached, while cross-project cloning clears attachments on the clone surface because a target profile belonging to another project will be rejected by the create gate.
        // Proactively clearing attachments is explainable in human-readable terms and prevents clone errors stating that the profile does not belong to the current project.
        String cloneTarget = sameProject ? source.automationTarget() : null;
        String cloneEngine = sameProject ? source.appautoEngine() : null;
        Long cloneProfileId = sameProject ? source.appautoEnvProfileId() : null;
        List<CaseStepInput> steps = source.steps() == null ? List.of()
                : source.steps().stream()
                        .map(s -> new CaseStepInput(s.stepNo(), s.description(), s.isAssert()))
                        .toList();
        // status=1 pending review is not inherited, and source=4 with source_ref=case:{source id} forms a stable key for single-level non-recursive references.
        // The mock binding follows the same carry rule as the target columns: kept for
        // same-project clones (the space is that project's asset), dropped cross-project
        // so the clone never points at a space its own project cannot publish.
        CaseCreateInput input = new CaseCreateInput(targetPid, source.title(), source.precondition(),
                source.expectedResult(), source.priority(), source.confidence(), source.endType(),
                1, source.phase(), 4, "case:" + source.id(), steps, tagIds, assigneeId,
                cloneTarget, cloneEngine, cloneProfileId, sameProject ? source.mockSpaceId() : null);
        return create(operatorId, input);
    }

    /** The set of current case tag IDs from tp_case_tag is ordered by ascending ID to ensure idempotent stability. */
    private List<Long> tagIdsOf(long caseId) {
        return jdbcTemplate.queryForList(
                "SELECT tag_id FROM tp_case_tag WHERE case_id = ? ORDER BY tag_id",
                Long.class, caseId);
    }

    /**
 * Cross-project tag remapping looks up source tag names by name in the target project tp_tag_dict.
     * If no matching name exists, silently do not attach, and because tp_tag_dict has only idx_tag_dict_project without uk(project,name), use LIMIT 1 defensively.
     */
    private List<Long> remapTagIds(long sourceCaseId, long targetProjectId) {
        List<String> names = jdbcTemplate.queryForList("""
                SELECT d.name FROM tp_case_tag ct JOIN tp_tag_dict d ON d.id = ct.tag_id
                WHERE ct.case_id = ? ORDER BY ct.tag_id
                """, String.class, sourceCaseId);
        List<Long> mapped = new ArrayList<>();
        for (String name : names) {
            List<Long> hits = jdbcTemplate.queryForList(
                    "SELECT id FROM tp_tag_dict WHERE project_id = ? AND name = ? ORDER BY id LIMIT 1",
                    Long.class, targetProjectId, name);
            mapped.addAll(hits); // If no matching name exists, skip silently with zero rows.
        }
        return mapped;
    }

    /** Assert that the operator id is non-null (internal core call contract: operatorId is always propagated through the request chain). */
    private long requireOperator(Long operatorId) {
        if (operatorId == null) {
            throw new ServiceException("缺少操作人信息——请「退出登录」后重新登录再操作（会话过期会导致该信息缺失）", BizErrorCode.VALIDATION_ERROR);
        }
        return operatorId;
    }

    // Internal utilities.

    /** Project code (test case number prefix); if the project does not exist, return 40401. */
    private String projectCode(long projectId) {
        List<String> rows = jdbcTemplate.query(
                "SELECT code FROM tp_project WHERE id = ?", (rs, rowNum) -> rs.getString(1), projectId);
        if (rows.isEmpty()) {
            throw new ServiceException("项目不存在或已被删除——请在顶栏「选择项目」重新选择有效项目后重试（选不到=非该项目成员，请项目管理员添加成员）", BizErrorCode.NOT_FOUND);
        }
        return rows.get(0);
    }

 /** Generate the identifier as TC-{code}-{maximum sequence within the project + 1}, zero-padded to at least three digits, and do not reuse sequence numbers from soft-deleted rows to avoid UNIQUE conflicts. */
    private String generateCaseNo(long projectId, String code) {
        Integer maxSeq = jdbcTemplate.queryForObject("""
                SELECT COALESCE(MAX((regexp_match(case_no, '-(\\d+)$'))[1]::int), 0)
                FROM tp_test_case WHERE project_id = ?
                """, Integer.class, projectId);
        int seq = (maxSeq == null ? 0 : maxSeq) + 1;
        return "TC-" + code + "-" + String.format("%03d", seq);
    }

    /** Replace all steps inside a transaction with column-level anchor preservation and R28 replay idempotency.
     *  <p>Steps carrying no explicit anchor keep the anchor stored on the row at the same position (manual
     *  edit chain has no anchor knowledge and must not silently drop evidence). Replaying an identical step
     *  set performs zero writes: no DELETE+INSERT, no updated_at bump, no row-id churn. */
    private void replaceSteps(long caseId, int caseVersion, List<CaseStepInput> steps) {
        List<CaseStepInput> normalized = normalizeStepAnchors(steps);
        Map<Integer, String> previousAnchors = new HashMap<>();
        Map<Integer, String[]> previousRows = new HashMap<>();
        jdbcTemplate.query(
                "SELECT step_no, description, is_assert, anchor::text AS anchor FROM tp_case_step WHERE case_id = ?",
                (rs, rowNum) -> {
                    int no = rs.getInt("step_no");
                    previousRows.put(no, new String[]{rs.getString("description"),
                            String.valueOf(rs.getInt("is_assert")), rs.getString("anchor")});
                    previousAnchors.put(no, rs.getString("anchor"));
                    return null;
                }, caseId);
        if (matchesPrevious(normalized, previousRows)) {
            return; // identical replay: keep step ids, timestamps and audit baseline untouched (R28)
        }
        jdbcTemplate.update("DELETE FROM tp_case_step WHERE case_id = ?", caseId);
        String stepsText = aggregateSteps(normalized);
        if (normalized != null && !normalized.isEmpty()) {
            int no = 1;
            for (CaseStepInput s : normalized) {
                String anchor = s.anchor() != null ? s.anchor() : previousAnchors.get(no);
                jdbcTemplate.update("""
                        INSERT INTO tp_case_step (case_id, case_version, step_no, description, is_assert, anchor)
                        VALUES (?, ?, ?, ?, ?, ?::jsonb)
                        """, caseId, caseVersion, no++, trimToNull(s.description()),
                        s.isAssert() == null ? 0 : s.isAssert(), anchor);
            }
        }
        jdbcTemplate.update("UPDATE tp_test_case SET steps_text = ?, updated_at = now() WHERE id = ?",
                stepsText, caseId);
    }

    /** Run every input anchor through the single-source normalizer, preserving input order. */
    private List<CaseStepInput> normalizeStepAnchors(List<CaseStepInput> steps) {
        if (steps == null) {
            return null;
        }
        List<CaseStepInput> out = new ArrayList<>(steps.size());
        for (CaseStepInput s : steps) {
            out.add(new CaseStepInput(s.stepNo(), s.description(), s.isAssert(),
                    anchorNormalizer.normalize(s.anchor(), s.description())));
        }
        return out;
    }

    /** True when the normalized step set equals the stored rows position by position (content and anchor). */
    private boolean matchesPrevious(List<CaseStepInput> normalized, Map<Integer, String[]> previousRows) {
        if (normalized == null || normalized.isEmpty()) {
            return previousRows.isEmpty();
        }
        if (normalized.size() != previousRows.size()) {
            return false;
        }
        int no = 1;
        for (CaseStepInput s : normalized) {
            String[] row = previousRows.get(no++);
            if (row == null) {
                return false;
            }
            String desc = trimToNull(s.description());
            if (!java.util.Objects.equals(row[0], desc)
                    || !row[1].equals(String.valueOf(s.isAssert() == null ? 0 : s.isAssert()))
                    || !java.util.Objects.equals(anchorNormalizer.canonical(row[2]), s.anchor())) {
                return false;
            }
        }
        return true;
    }

    /** Aggregated step text (redundant column for retrieval) is space-separated, such as 1.xxx 2.yyy. */
    private String aggregateSteps(List<CaseStepInput> steps) {
        if (steps == null || steps.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        int no = 1;
        for (CaseStepInput s : steps) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(no++).append('.').append(s.description());
        }
        return sb.toString();
    }

    /** Validate steps by requiring a description of at most 500 characters, ensuring step_no is at least 1 and unique within the list with the server reordering them sequentially from 1 to n, and requiring is_assert to be either 0 or 1. */
    private void validateSteps(List<CaseStepInput> steps) {
        if (steps == null) {
            return;
        }
        Set<Integer> seen = new HashSet<>();
        for (CaseStepInput s : steps) {
            String desc = trimToNull(s.description());
            if (desc == null || desc.length() > 500) {
                throw new ServiceException("步骤描述不能为空且不超过 500 字——请在「用例详情」步骤编辑区填写「步骤描述」（正确写法示例：点击登录按钮，预期跳转首页）", BizErrorCode.VALIDATION_ERROR);
            }
            if (s.stepNo() != null) {
                if (s.stepNo() < 1) {
                    throw new ServiceException("步骤序号必须 ≥ 1——请在「用例详情」步骤编辑区把「步骤序号」改成从 1 开始的连续整数", BizErrorCode.VALIDATION_ERROR);
                }
                if (!seen.add(s.stepNo())) {
                    throw new ServiceException("步骤序号 %s 重复——请在「用例详情」步骤编辑区把重复的「步骤序号」改成不重复的连续编号后保存", BizErrorCode.VALIDATION_ERROR, s.stepNo());
                }
            }
            int assertFlag = s.isAssert() == null ? 0 : s.isAssert();
            if (assertFlag != 0 && assertFlag != 1) {
                throw new ServiceException("is_assert 仅支持 0/1——请改成 0=操作 或 1=断言（正确写法示例：校验预期结果的动作填 1）", BizErrorCode.VALIDATION_ERROR);
            }
        }
    }

    private void validatePriority(int priority) {
        if (priority < 0 || priority > 2) {
            throw new ServiceException("优先级仅支持 0/1/2——请在「用例详情」把「优先级」改成 0=P0、1=P1 或 2=P2", BizErrorCode.VALIDATION_ERROR);
        }
    }

    private void validateConfidence(int confidence) {
        if (confidence < 1 || confidence > 3) {
            throw new ServiceException("置信度仅支持 1/2/3——请改成 1=高、2=中 或 3=低（正确写法示例：人工确认过的用例填 1）", BizErrorCode.VALIDATION_ERROR);
        }
    }

    private void validateEndType(String endType) {
        if (!END_TYPES.contains(endType)) {
            throw new ServiceException("端属性仅支持 4 个取值——请在「用例详情」把「端属性」改成 web、mobile、pos 或 multi", BizErrorCode.VALIDATION_ERROR);
        }
    }

 /** Validate the test case phase (K10/: only 1=smoke, 2=functional, 3=regression, 4=release verification, and null=unassigned is allowed). */
    private void validatePhase(Integer phase) {
        if (phase != null && (phase < 1 || phase > 4)) {
            throw new ServiceException("用例阶段仅支持 1~4——请改成 1=冒烟、2=功能、3=回归 或 4=发布验证（正确写法示例：核心链路冒烟填 1；未归属阶段时该字段不传）",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    private CaseInfo mapRow(ResultSet rs, int rowNum) throws SQLException {
        long createdByValue = rs.getLong("created_by");
        boolean createdByNull = rs.wasNull(); // Record the value immediately because subsequent getters will overwrite the wasNull state.
        long assigneeValue = rs.getLong("assignee_id");
        boolean assigneeNull = rs.wasNull();
        int phaseValue = rs.getInt("phase");
        boolean phaseNull = rs.wasNull();
        return new CaseInfo(
                rs.getLong("id"),
                rs.getString("case_no"),
                rs.getLong("project_id"),
                rs.getString("title"),
                rs.getString("precondition"),
                rs.getString("expected_result"),
                rs.getInt("priority"),
                rs.getInt("confidence"),
                rs.getString("end_type"),
                rs.getInt("status"),
                phaseNull ? null : phaseValue,
                rs.getInt("version"),
                assigneeNull ? null : assigneeValue,
                rs.getObject("assigned_at", LocalDateTime.class),
                rs.getInt("source"),
                rs.getString("source_ref"),
                createdByNull ? null : createdByValue,
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class),
                null, // Populate when step details are retrieved.
                rs.getInt("flaky"),
                rs.getString("flaky_reason"),
                rs.getObject("flaky_marked_at", LocalDateTime.class),
                rs.getInt("excellent_flag"),
                rs.getString("automation_target"),
                rs.getString("appauto_engine"),
                readLongColumn(rs, "appauto_env_profile_id"),
                readLongColumn(rs, "mock_space_id"),
                rs.getInt("evidence_grade"),
                com.sixbean.testpike.core.cases.domain.component.CaseSourceFacet.of(
                        rs.getInt("source"), rs.getString("source_ref")));
    }

    /** Read nullable bigint columns by recording wasNull immediately to prevent subsequent getters from overwriting it, using the same rule in mapRow. */
    private Long readLongColumn(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
