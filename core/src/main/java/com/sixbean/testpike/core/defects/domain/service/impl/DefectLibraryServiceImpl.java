package com.sixbean.testpike.core.defects.domain.service.impl;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.audit.api.dto.AuditLogInput;
import com.sixbean.testpike.core.audit.domain.component.Audit;
import com.sixbean.testpike.core.audit.domain.service.AuditLogService;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.common.domain.component.ExcelExportSupport;
import com.sixbean.testpike.core.common.domain.component.ExportScopeValidator;
import com.sixbean.testpike.core.common.domain.component.SqlPlaceholders;
import com.sixbean.testpike.core.common.domain.component.SortColumnPolicy;
import com.sixbean.testpike.core.defects.api.dto.DefectCreateInput;
import com.sixbean.testpike.core.defects.api.dto.DefectExportRequest;
import com.sixbean.testpike.core.defects.api.dto.DefectExportResult;
import com.sixbean.testpike.core.defects.api.dto.DefectLibraryInfo;
import com.sixbean.testpike.core.defects.api.dto.DefectQuery;
import com.sixbean.testpike.core.defects.api.dto.DefectSummary;
import com.sixbean.testpike.core.defects.api.dto.DefectTransitionInput;
import com.sixbean.testpike.core.defects.api.dto.DefectUpdateInput;
import com.sixbean.testpike.core.defects.domain.component.DefectExportRow;
import com.sixbean.testpike.core.defects.domain.component.DefectExportSupport;
import com.sixbean.testpike.core.defects.domain.component.DefectFilteredWhere;
import com.sixbean.testpike.core.defects.domain.component.DefectFilters;
import com.sixbean.testpike.common.defect.DefectLabels;
import com.sixbean.testpike.core.defects.domain.service.DefectLibraryService;
import com.sixbean.testpike.core.program.domain.component.ProgramLensScope;
import com.sixbean.testpike.core.report.domain.component.ExportArtifact;
import com.sixbean.testpike.core.report.domain.component.ExportTaskSupport;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.sixbean.testpike.core.audit.domain.component.AuditLogContext;
import com.sixbean.testpike.core.audit.domain.component.AuditSnapshot;


@Service
public class DefectLibraryServiceImpl implements DefectLibraryService {

    
    private static final Map<Integer, Set<Integer>> TRANSITIONS = Map.of(
            1, Set.of(2),
            2, Set.of(3),
            3, Set.of(4),
            4, Set.of(5, 6),
            5, Set.of(),
            6, Set.of(2));

 
    private static final long DEFECT_KEY_LOCK_KEY_BASE = 20260909_158L;

    private static final String COLUMNS = """
            d.id, d.project_id, d.case_id, d.plan_id, d.title, d.description, d.severity, d.status,
            d.assignee_id, a.display_name AS assignee_name, d.reporter_id, r.display_name AS reporter_name,
            d.steps_json, d.external_key, d.closed_reason,
            d.source, d.jira_sync_status, d.jira_sync_error, d.version,
            to_char(d.created_at, 'YYYY-MM-DD HH24:MI:SS') AS created_at,
            to_char(d.updated_at, 'YYYY-MM-DD HH24:MI:SS') AS updated_at
            """;

    private static final String FROM = """
            FROM tp_defect d
            LEFT JOIN tp_user a ON a.id = d.assignee_id
            LEFT JOIN tp_user r ON r.id = d.reporter_id
            """;

    private final JdbcTemplate jdbcTemplate;
    private final DefectExportSupport defectExportSupport;
    private final ExportTaskSupport exportTaskSupport;
    private final AuditLogService auditLogService;
    /** Program lens scope single source — consumed by the list, never re-implemented. */
    private final ProgramLensScope programLensScope;

    public DefectLibraryServiceImpl(JdbcTemplate jdbcTemplate,
                                    DefectExportSupport defectExportSupport,
                                    ExportTaskSupport exportTaskSupport,
                                    AuditLogService auditLogService,
                                    ProgramLensScope programLensScope) {
        this.jdbcTemplate = jdbcTemplate;
        this.defectExportSupport = defectExportSupport;
        this.exportTaskSupport = exportTaskSupport;
        this.auditLogService = auditLogService;
        this.programLensScope = programLensScope;
    }

    @Override
    @Transactional
    @Audit(objectType = "defect", action = "defect_create", objectId = "#result.id", after = "#result")
    public DefectLibraryInfo create(long operatorId, DefectCreateInput input) {
        if (input.projectId() == null || input.projectId() <= 0) {
            throw new ServiceException("所属项目不能为空——请先在顶栏「选择项目」切换到目标项目，"
                    + "或在「新建缺陷」弹窗的「选择项目」下拉选定项目后提交", BizErrorCode.VALIDATION_ERROR);
        }
        String title = input.title() == null ? "" : input.title().trim();
        if (title.isEmpty()) {
            throw new ServiceException("缺陷标题不能为空——请在「新建缺陷」弹窗的「缺陷标题」输入框填写标题后提交",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (title.length() > 200) {
            throw new ServiceException("缺陷标题长度不能超过 200 个字符——请在「新建缺陷」弹窗的「缺陷标题」输入框"
                    + "删减到 200 字以内后重新提交", BizErrorCode.VALIDATION_ERROR);
        }
        int severity = input.severity() == null ? 2 : input.severity();
        if (severity < 1 || severity > 4) {
            throw new ServiceException("严重级别仅支持 P0(1)/P1(2)/P2(3)/P3(4)，实际: " + severity
                    + "——请在「新建缺陷」弹窗的「严重级别」下拉重新选择（P0 最严重，P3 最轻）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        Long projectId = jdbcTemplate.query("SELECT id FROM tp_project WHERE id = ?",
                rs -> rs.next() ? rs.getLong(1) : null, input.projectId());
        if (projectId == null) {
            throw new ServiceException("项目不存在——请在「新建缺陷」弹窗的「选择项目」重新选择有效项目；"
                    + "项目已被删除时请先到「项目管理」新建或恢复项目", BizErrorCode.VALIDATION_ERROR);
        }
        if (input.caseId() != null) {
            Long caseProject = jdbcTemplate.query(
                    "SELECT project_id FROM tp_test_case WHERE id = ? AND deleted = 0",
                    rs -> rs.next() ? rs.getLong(1) : null, input.caseId());
            if (caseProject == null) {
                throw new ServiceException("关联用例不存在——请在「新建缺陷」弹窗的「关联用例」下拉"
                        + "选择本项目下的有效用例后提交", BizErrorCode.VALIDATION_ERROR);
            }
            if (caseProject.longValue() != projectId.longValue()) {
                throw new ServiceException("关联用例不属于该项目——请在「新建缺陷」弹窗把「选择项目」切到该用例所属项目，"
                        + "或在「关联用例」改选当前项目下的用例", BizErrorCode.VALIDATION_ERROR);
            }
        }
        if (input.planId() != null) {
            Long planProject = jdbcTemplate.query(
                    "SELECT project_id FROM tp_exec_plan WHERE id = ?",
                    rs -> rs.next() ? rs.getLong(1) : null, input.planId());
            if (planProject == null) {
                throw new ServiceException("关联计划不存在——请在「新建缺陷」弹窗的「关联计划」下拉"
                        + "选择本项目下的执行计划后提交", BizErrorCode.VALIDATION_ERROR);
            }
            if (planProject.longValue() != projectId.longValue()) {
                throw new ServiceException("关联计划不属于该项目——请在「新建缺陷」弹窗把「选择项目」切到该计划所属项目，"
                        + "或在「关联计划」改选当前项目下的执行计划", BizErrorCode.VALIDATION_ERROR);
            }
        }
        Long assigneeId = input.assigneeId();
        if (assigneeId != null) {
            assertProjectMember(assigneeId, projectId, "修复人");
        }
        String externalKey = input.externalKey() == null ? "" : input.externalKey().trim();
        if (externalKey.length() > 50) {
            throw new ServiceException("外部缺陷 key 长度不能超过 50 个字符——请在「新建缺陷」弹窗的「外部 Jira key」输入框"
                    + "改为 50 字符以内，格式：JIRA-101", BizErrorCode.VALIDATION_ERROR);
        }
        if (assigneeId == null) {
            Long devOwner = jdbcTemplate.query(
                    "SELECT dev_owner_id FROM tp_project WHERE id = ?",
                    rs -> rs.next() ? (Long) rs.getObject(1) : null, projectId);
            if (devOwner != null && isProjectMember(devOwner, projectId)) {
                assigneeId = devOwner;
            }
        }
        if (externalKey.isEmpty()) {
            String jiraKey = jdbcTemplate.query(
                    "SELECT jira_project_id FROM tp_project WHERE id = ?",
                    rs -> rs.next() ? rs.getString(1) : null, projectId);
            if (jiraKey != null && !jiraKey.isBlank()) {
                jdbcTemplate.queryForObject("SELECT pg_advisory_xact_lock(?)::text", String.class,
                        DEFECT_KEY_LOCK_KEY_BASE + projectId);
                externalKey = jiraKey + "-" + String.format("%03d", nextDefectSeq(projectId, jiraKey));
            }
        }
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO tp_defect (project_id, case_id, plan_id, title, description, severity,
                                       assignee_id, reporter_id, steps_json, external_key)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, projectId, input.caseId(), input.planId(), title, input.description(),
                severity, assigneeId, operatorId, input.stepsJson(),
                externalKey.isEmpty() ? null : externalKey);
        return get(id);
    }

    /** Server-side sort whitelist (defect library list; frontend column props map
     * onto these keys via SORT_KEY_MAP). All values are physical columns of tp_defect. */
    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "title", "d.title",
            "severity", "d.severity",
            "status", "d.status",
            "created_at", "d.created_at",
            "updated_at", "d.updated_at");

    @Override
    public PageResult<DefectLibraryInfo> list(long operatorId, DefectQuery query) {
        // One scope anchor required: an explicit project OR a program lens. Neither
        // stays the historical 400 — a lens alone never falls through to a cross-tenant dump.
        if ((query.projectId() == null || query.projectId() <= 0) && query.programId() <= 0) {
            throw new ServiceException("所属项目不能为空——请在顶栏「选择项目」选择项目，"
                    + "或在顶栏项目集切换器改选项目集后查看缺陷列表", BizErrorCode.VALIDATION_ERROR);
        }
        int safePage = Math.max(query.page(), 1);
        int safeSize = Math.min(Math.max(query.size(), 1), 100);
        int offset = (safePage - 1) * safeSize;
        // Sort keys must resolve to a physical column through the shared whitelist —
        // user input never reaches the SQL string (SortColumnPolicy.require, same posture
        // as the plan/suite/batch domains; off-list key = fail-closed 400).
        String sortColumn = SortColumnPolicy.require(query.sortBy(), SORT_COLUMNS, "缺陷");
        String order = sortColumn == null
                ? " ORDER BY d.id DESC"
                : " ORDER BY " + sortColumn
                        + ("asc".equalsIgnoreCase(query.sortDir()) ? " ASC" : " DESC") + ", d.id DESC";

        // Program lens : resolved by the ProgramLensScope single source. null = no
        // lens (legacy path untouched); export keeps the 6-arg form (no lens this wave).
        Set<Long> lensProjectIds = query.programId() > 0
                ? programLensScope.visibleProjectIds(query.programId(), operatorId)
                : null;
        DefectFilteredWhere where = DefectFilters.build(
                query.projectId() == null ? 0L : query.projectId(), lensProjectIds,
                query.status(), query.severity(), query.assigneeId(), query.caseId(), query.keyword());
        Long total = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_defect d" + where.sql(), Long.class, where.args().toArray());
        List<Object> params = new ArrayList<>(where.args());
        params.add(safeSize);
        params.add(offset);
        List<DefectLibraryInfo> list = jdbcTemplate.query(
                "SELECT " + COLUMNS + FROM + where.sql() + order + " LIMIT ? OFFSET ?",
                (rs, i) -> mapRow(rs), params.toArray());
        return new PageResult<>(list, total == null ? 0 : total);
    }

    
    @Override
    public DefectExportResult export(long operatorId, DefectExportRequest request) {
        if (request.projectId() <= 0) {
            throw new ServiceException("所属项目不能为空——请在顶栏「选择项目」选定项目后，再到「缺陷库」"
                    + "勾选行用「导出勾选」或使用「导出当前筛选结果」", BizErrorCode.VALIDATION_ERROR);
        }
        boolean hasFilter = request.status() != null || request.severity() != null
                || request.assigneeId() != null || request.caseId() != null
                || (request.keyword() != null && !request.keyword().isBlank());
        String mode = ExportScopeValidator.validate(request.ids(), hasFilter);

        DefectFilteredWhere where = DefectFilters.build(request.projectId(),
                "ids".equals(mode) ? null : request.status(),
                "ids".equals(mode) ? null : request.severity(),
                "ids".equals(mode) ? null : request.assigneeId(),
                "ids".equals(mode) ? null : request.caseId(),
                "ids".equals(mode) ? null : request.keyword());
        List<DefectLibraryInfo> visible;
        int removed = 0;
        if ("ids".equals(mode)) {
            List<Object> args = new ArrayList<>(where.args());
            args.addAll(request.ids());
            visible = jdbcTemplate.query(
                    "SELECT " + COLUMNS + FROM + where.sql() + " AND d.id IN ("
                            + SqlPlaceholders.in(request.ids().size()) + ") ORDER BY d.id",
                    (rs, i) -> mapRow(rs), args.toArray());
            removed = request.ids().size() - visible.size();
        } else {
            visible = jdbcTemplate.query(
                    "SELECT " + COLUMNS + FROM + where.sql() + " ORDER BY d.id",
                    (rs, i) -> mapRow(rs), where.args().toArray());
        }
        int total = visible.size();
        recordExportAudit(operatorId, request.projectId(), mode, total, removed);

        if (total > ExportScopeValidator.ASYNC_THRESHOLD) {
            long taskId = exportTaskSupport.createTask(operatorId, request.projectId(),
                    "defect_export", false, total);
            exportTaskSupport.startAsync(taskId, operatorId, request.projectId(), "defect_export",
                    "缺陷导出完成", "缺陷导出完成（%d 条），请到导出任务列表下载。",
                    () -> new ExportArtifact(buildExcel(visible), total));
            return new DefectExportResult(true, taskId, null, null, total, removed);
        }
        return new DefectExportResult(false, 0,
                ExportScopeValidator.exportFileName(EXPORT_FILE_BASE),
                buildExcel(visible), total, removed);
    }

    
    private byte[] buildExcel(List<DefectLibraryInfo> visible) {
        List<DefectExportRow> rows = defectExportSupport.assembleRows(visible);
        List<List<Object>> data = new ArrayList<>(rows.size());
        for (DefectExportRow row : rows) {
            data.add(row.toExcelRow());
        }
        return ExcelExportSupport.toExcel(DefectExportSupport.sheetName(),
                DefectExportSupport.HEADERS, data);
    }

    
    private void recordExportAudit(long operatorId, long projectId, String mode, int total, int removed) {
        Map<String, Object> after = new java.util.LinkedHashMap<>();
        after.put("mode", mode);
        after.put("projectId", projectId);
        after.put("total", total);
        after.put("removed", removed);
        auditLogService.record(new AuditLogInput(
                operatorId, projectId, "defect", String.valueOf(projectId), "export", null, after));
    }

    @Override
    public DefectLibraryInfo get(long id) {
        DefectLibraryInfo info = getById(id);
        if (info == null) {
            throw new ServiceException("缺陷不存在——请返回「缺陷库」列表刷新后重新选择缺陷；刚被删除的缺陷无法再打开详情",
                    BizErrorCode.NOT_FOUND);
        }
        return info;
    }

    @Override
    @Transactional
    @Audit(objectType = "defect", action = "defect_update", objectId = "#id", after = "#result")
    public DefectLibraryInfo update(long operatorId, long id, DefectUpdateInput input) {
        DefectLibraryInfo current = get(id);
        AuditLogContext.setBefore(AuditSnapshot.snapshot(current));
        if (current.status() == STATUS_CLOSED) {
            throw new ServiceException("已关闭的缺陷不可编辑——请先在「缺陷详情」点「流转」，在「缺陷状态流转」弹窗"
                    + "把「目标状态」选为「重新打开」并填写「原因」提交，再回到「编辑缺陷」修改", BizErrorCode.STATE_MACHINE_REJECT);
        }
        if (input.version() == null || input.version() <= 0) {
            throw new ServiceException("版本号不能为空——请携带打开「编辑缺陷」弹窗时加载的最新版本号提交；"
                    + "版本号缺失时请关闭弹窗重新打开后再保存", BizErrorCode.VALIDATION_ERROR);
        }
        String title = input.title() == null ? "" : input.title().trim();
        if (title.isEmpty()) {
            throw new ServiceException("缺陷标题不能为空——请在「编辑缺陷」弹窗的「缺陷标题」输入框填写标题后保存",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (title.length() > 200) {
            throw new ServiceException("缺陷标题长度不能超过 200 个字符——请在「编辑缺陷」弹窗的「缺陷标题」输入框"
                    + "删减到 200 字以内后重新保存", BizErrorCode.VALIDATION_ERROR);
        }
        int severity = input.severity() == null ? current.severity() : input.severity();
        if (severity < 1 || severity > 4) {
            throw new ServiceException("严重级别仅支持 P0(1)/P1(2)/P2(3)/P3(4)，实际: " + severity
                    + "——请在「编辑缺陷」弹窗的「严重级别」下拉重新选择（P0 最严重，P3 最轻）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        Long assigneeId = input.assigneeId();
        if (assigneeId != null && !assigneeId.equals(current.assigneeId())) {
            assertProjectMember(assigneeId, current.projectId(), "修复人");
        }
        String externalKey = input.externalKey() == null ? "" : input.externalKey().trim();
        if (externalKey.length() > 50) {
            throw new ServiceException("外部缺陷 key 长度不能超过 50 个字符——请在「编辑缺陷」弹窗的「外部 Jira key」输入框"
                    + "改为 50 字符以内，格式：JIRA-101", BizErrorCode.VALIDATION_ERROR);
        }
        int rows = jdbcTemplate.update("""
                UPDATE tp_defect SET title = ?, description = ?, severity = ?, assignee_id = ?,
                       steps_json = ?, external_key = ?, version = version + 1, updated_at = now()
                WHERE id = ? AND version = ?
                """, title, input.description(), severity, assigneeId, input.stepsJson(),
                externalKey.isEmpty() ? null : externalKey, id, input.version());
        if (rows == 0) {
            throw new ServiceException("缺陷已被他人修改——请刷新「缺陷库」列表后重新打开「编辑缺陷」弹窗，"
                    + "加载最新版本号再保存", BizErrorCode.VERSION_CONFLICT);
        }
        return get(id);
    }

    @Override
    @Transactional
    @Audit(objectType = "defect", action = "defect_transition", objectId = "#id", after = "#result")
    public DefectLibraryInfo transition(long operatorId, long id, DefectTransitionInput input) {
        DefectLibraryInfo current = get(id);
        AuditLogContext.setBefore(AuditSnapshot.snapshot(current));
        if (input.toStatus() == null) {
            throw new ServiceException("目标状态不能为空——请在「缺陷状态流转」弹窗的「目标状态」下拉选择要流转到的状态",
                    BizErrorCode.VALIDATION_ERROR);
        }
        int to = input.toStatus();
        if (to < 2 || to > 6) {
            throw new ServiceException("目标状态仅支持处理中(2)/已修复(3)/验证中(4)/已关闭(5)/重新打开(6)——"
                    + "请在「缺陷状态流转」弹窗的「目标状态」下拉重新选择上述状态",
                    BizErrorCode.VALIDATION_ERROR);
        }
        Set<Integer> allowed = TRANSITIONS.getOrDefault(current.status(), Set.of());
        if (!allowed.contains(to)) {
            throw new ServiceException("非法状态流转：当前「" + statusLabel(current.status())
                    + "」不可流转到「" + statusLabel(to) + "」——请在「缺陷状态流转」弹窗的「目标状态」改选允许流转的状态；"
                    + "已关闭的缺陷需先流转到「重新打开」", BizErrorCode.STATE_MACHINE_REJECT);
        }
        if (input.version() == null || input.version() <= 0) {
            throw new ServiceException("版本号不能为空——请携带打开「缺陷状态流转」弹窗时加载的最新版本号提交；"
                    + "版本号缺失时请关闭弹窗重新打开后再提交", BizErrorCode.VALIDATION_ERROR);
        }
        String reason = input.closedReason() == null ? "" : input.closedReason().trim();
        if (to == STATUS_CLOSED || to == STATUS_REOPENED) {
            if (reason.isEmpty()) {
                throw new ServiceException("关闭/重新打开必须填写原因——请在「缺陷状态流转」弹窗的「原因」输入框"
                        + "填写处理说明后提交", BizErrorCode.VALIDATION_ERROR);
            }
            if (reason.length() > 500) {
                throw new ServiceException("原因长度不能超过 500 个字符——请在「缺陷状态流转」弹窗的「原因」输入框"
                        + "删减到 500 字以内后提交", BizErrorCode.VALIDATION_ERROR);
            }
        }
        int rows = jdbcTemplate.update("""
                UPDATE tp_defect SET status = ?, closed_reason = ?, version = version + 1, updated_at = now()
                WHERE id = ? AND version = ?
                """, to, reason.isEmpty() ? null : reason, id, input.version());
        if (rows == 0) {
            throw new ServiceException("缺陷已被他人修改——请刷新「缺陷库」列表后重新打开「缺陷状态流转」弹窗，"
                    + "加载最新版本号再提交", BizErrorCode.VERSION_CONFLICT);
        }
        return get(id);
    }

    @Override
    public DefectSummary summaryByCase(long caseId) {
        Long builtinTotal = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_defect WHERE case_id = ?", Long.class, caseId);
        Long builtinOpen = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_defect WHERE case_id = ? AND status <> 5", Long.class, caseId);
        Long extTotal = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_case_defect WHERE case_id = ?", Long.class, caseId);
        Long extOpen = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_case_defect WHERE case_id = ? AND defect_status <> 'closed'",
                Long.class, caseId);
        int total = (int) (nz(builtinTotal) + nz(extTotal));
        int open = (int) (nz(builtinOpen) + nz(extOpen));
        return new DefectSummary(caseId, total, open);
    }

    @Override
    public int countOpenByPlan(long planId, boolean severityP0Only) {
        String severitySql = severityP0Only ? " AND d.severity = 1" : "";
        Long n = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM tp_defect d
                WHERE (d.plan_id = ? OR d.case_id IN (SELECT case_id FROM tp_plan_case WHERE plan_id = ?))
                  AND d.status <> 5%s
                """.formatted(severitySql), Long.class, planId, planId);
        return n == null ? 0 : n.intValue();
    }

    private DefectLibraryInfo getById(long id) {
        return jdbcTemplate.query("SELECT " + COLUMNS + FROM + " WHERE d.id = ?",
                rs -> rs.next() ? mapRow(rs) : null, id);
    }

    private DefectLibraryInfo mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new DefectLibraryInfo(rs.getLong("id"), rs.getLong("project_id"),
                (Long) rs.getObject("case_id"), (Long) rs.getObject("plan_id"),
                rs.getString("title"), rs.getString("description"),
                rs.getInt("severity"), rs.getInt("status"),
                (Long) rs.getObject("assignee_id"), rs.getString("assignee_name"),
                rs.getLong("reporter_id"), rs.getString("reporter_name"),
                rs.getString("steps_json"), rs.getString("external_key"), rs.getString("closed_reason"),
                rs.getInt("source"), rs.getInt("jira_sync_status"), rs.getString("jira_sync_error"),
                rs.getInt("version"), rs.getString("created_at"), rs.getString("updated_at"));
    }

    
    private void assertProjectMember(long userId, long projectId, String roleLabel) {
        if (!isProjectMember(userId, projectId)) {
            throw new ServiceException("该用户不是本项目成员，不能作为" + roleLabel
                    + "——请到「项目管理」打开本项目详情的「成员管理」把该用户加为成员，"
                    + "或改选一名项目成员担任" + roleLabel, BizErrorCode.VALIDATION_ERROR);
        }
    }

    
    private boolean isProjectMember(long userId, long projectId) {
        Long cnt = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_user_project WHERE user_id = ? AND project_id = ?",
                Long.class, userId, projectId);
        return cnt != null && cnt > 0;
    }

    
    private int nextDefectSeq(long projectId, String jiraKey) {
        Integer maxSeq = jdbcTemplate.queryForObject("""
                SELECT COALESCE(MAX((regexp_match(external_key, ?))[1]::int), 0)
                FROM tp_defect WHERE project_id = ?
                """, Integer.class, "^" + jiraKey + "-(\\d+)$", projectId);
        return (maxSeq == null ? 0 : maxSeq) + 1;
    }

    private long nz(Long v) {
        return v == null ? 0 : v;
    }

    private String statusLabel(int status) {
        return DefectLabels.statusLabel(status);
    }

    private static final int STATUS_CLOSED = 5;
    private static final int STATUS_REOPENED = 6;
    
    private static final String EXPORT_FILE_BASE = "缺陷导出";
}
