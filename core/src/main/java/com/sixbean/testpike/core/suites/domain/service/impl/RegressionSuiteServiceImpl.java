package com.sixbean.testpike.core.suites.domain.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.suites.domain.component.RegressionSuiteRefusals;
import com.sixbean.testpike.core.audit.domain.component.Audit;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.common.domain.component.SortColumnPolicy;
import com.sixbean.testpike.core.plans.api.dto.CaseScopeRule;
import com.sixbean.testpike.core.plans.api.dto.GenerateBatchResult;
import com.sixbean.testpike.core.plans.api.dto.PlanAssignInput;
import com.sixbean.testpike.core.plans.api.dto.PlanCreateInput;
import com.sixbean.testpike.core.plans.api.dto.PlanInfo;
import com.sixbean.testpike.core.plans.domain.service.PlanService;
import com.sixbean.testpike.core.sched.domain.component.ScheduleCronTimes;
import com.sixbean.testpike.core.project.api.dto.MemberInfo;
import com.sixbean.testpike.core.project.domain.service.MemberService;
import com.sixbean.testpike.core.project.domain.service.ProjectAccessService;
import com.sixbean.testpike.core.suites.api.dto.SuiteCreateInput;
import com.sixbean.testpike.core.suites.api.dto.SuiteInfo;
import com.sixbean.testpike.core.suites.api.dto.SuiteQuery;
import com.sixbean.testpike.core.suites.api.dto.SuiteRunResult;
import com.sixbean.testpike.core.suites.api.dto.SuiteUpdateInput;
import com.sixbean.testpike.core.suites.domain.service.RegressionSuiteService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.sixbean.testpike.core.audit.domain.component.AuditLogContext;
import com.sixbean.testpike.core.audit.domain.component.AuditSnapshot;
import com.sixbean.testpike.core.executor.domain.component.AppAutoCaseTarget;
import com.sixbean.testpike.core.executor.domain.component.AppAutoDispatchSupport;


@Service
public class RegressionSuiteServiceImpl implements RegressionSuiteService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final JdbcTemplate jdbcTemplate;
    private final ProjectAccessService projectAccessService;
    private final MemberService memberService;
    private final PlanService planService;
    private final ObjectMapper objectMapper;
    private final AppAutoDispatchSupport appAutoDispatchSupport;

    /** Mock binding gate: published+own-project check for the declarative mock_space_id. */
    private final com.sixbean.testpike.core.mock.domain.component.MockSpaceBindingGate mockSpaceBindingGate;

    public RegressionSuiteServiceImpl(JdbcTemplate jdbcTemplate,
                                      ProjectAccessService projectAccessService,
                                      MemberService memberService,
                                      PlanService planService,
                                      ObjectMapper objectMapper,
                                      AppAutoDispatchSupport appAutoDispatchSupport,
                                      com.sixbean.testpike.core.mock.domain.component.MockSpaceBindingGate mockSpaceBindingGate) {
        this.jdbcTemplate = jdbcTemplate;
        this.projectAccessService = projectAccessService;
        this.memberService = memberService;
        this.planService = planService;
        this.objectMapper = objectMapper;
        this.appAutoDispatchSupport = appAutoDispatchSupport;
        this.mockSpaceBindingGate = mockSpaceBindingGate;
    }

    @Override
    @Transactional
    @Audit(objectType = "regression_suite", action = "suite_create", objectId = "#result.id", after = "#result")
    public SuiteInfo create(long operatorId, SuiteCreateInput input) {
        validate(operatorId, input.projectId(), input.name(), input.caseIds(), input.envIds(),
                input.defaultAssigneeIds());
        ScheduleCronTimes.validate(input.cronExpr());
        AppAutoCaseTarget target = appAutoDispatchSupport.validateCaseTargetFields(
                input.projectId(), input.automationTarget(), input.appautoEngine(),
                input.appautoEnvProfileId());
        // Declarative mock binding: 0/null = real environment; the shared
        // MockSpaceBindingGate guards existence, project ownership and published state
        Long mockSpaceId = (input.mockSpaceId() == null || input.mockSpaceId() == 0L)
                ? null : input.mockSpaceId();
        mockSpaceBindingGate.requireBindable(input.projectId(), mockSpaceId);
        long id = jdbcTemplate.queryForObject("""
                INSERT INTO tp_regression_suite (project_id, name, case_ids_json, env_ids_json,
                                                 default_assignee_ids_json, cron_expr, status, created_by,
                                                 automation_target, appauto_engine, appauto_env_profile_id, mock_space_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, input.projectId(), input.name().trim(),
                toJson(input.caseIds()), toJson(input.envIds()),
                toJson(safeList(input.defaultAssigneeIds())), trimToNull(input.cronExpr()),
                input.status() == null || input.status() == 0 ? 1 : input.status(), operatorId,
                target.automationTarget(), target.appautoEngine(), target.appautoEnvProfileId(), mockSpaceId);
        return get(id);
    }

    @Override
    @Transactional
    @Audit(objectType = "regression_suite", action = "suite_update", objectId = "#id", after = "#result")
    public SuiteInfo update(long operatorId, long id, SuiteUpdateInput input) {
        SuiteInfo suite = get(id);
        AuditLogContext.setBefore(AuditSnapshot.snapshot(suite));
        validate(operatorId, suite.projectId(), input.name(), input.caseIds(), input.envIds(),
                input.defaultAssigneeIds());
        ScheduleCronTimes.validate(input.cronExpr());
        AppAutoCaseTarget target = appAutoDispatchSupport.validateCaseTargetUpdate(
                suite.projectId(), suite.automationTarget(), suite.appautoEngine(),
                suite.appautoEnvProfileId(),
                input.automationTarget(), input.appautoEngine(), input.appautoEnvProfileId());
        // Declarative mock binding: null = untouched; 0 = unbind back to the real
        // environment; otherwise the shared MockSpaceBindingGate guards the new value
        Long mockSpaceId = input.mockSpaceId();
        if (mockSpaceId != null && mockSpaceId != 0L) {
            mockSpaceBindingGate.requireBindable(suite.projectId(), mockSpaceId);
        }
        Long effectiveMockSpace = mockSpaceId == null ? suite.mockSpaceId()
                : (mockSpaceId == 0L ? null : mockSpaceId);
        jdbcTemplate.update("""
                UPDATE tp_regression_suite SET name = ?, case_ids_json = ?, env_ids_json = ?,
                       default_assignee_ids_json = ?, cron_expr = ?, status = ?, updated_at = now(),
                       automation_target = ?, appauto_engine = ?, appauto_env_profile_id = ?, mock_space_id = ?
                WHERE id = ?
                """, input.name().trim(), toJson(input.caseIds()), toJson(input.envIds()),
                toJson(safeList(input.defaultAssigneeIds())), trimToNull(input.cronExpr()),
                input.status() == null || input.status() == 0 ? 1 : input.status(),
                target.automationTarget(), target.appautoEngine(), target.appautoEnvProfileId(),
                effectiveMockSpace, id);
        return get(id);
    }

    @Override
    public SuiteInfo get(long id) {
        String caseCountSql = " (SELECT count(*) FROM tp_test_case c " +
                " WHERE c.id::text IN (SELECT json_array_elements_text(s.case_ids_json::json)) " +
                "   AND c.deleted = 0 AND c.status <> 9) AS case_count,";
        List<SuiteInfo> rows = jdbcTemplate.query("""
        SELECT s.*, to_char(s.created_at, 'YYYY-MM-DD HH24:MI:SS') AS created_at,
               to_char(s.last_run_at, 'YYYY-MM-DD HH24:MI:SS') AS last_run_at,
               %s
               COALESCE(u.display_name, '') AS created_by_name
        FROM tp_regression_suite s
        LEFT JOIN tp_user u ON u.id = s.created_by
        WHERE s.id = ?
        """.formatted(caseCountSql),
                (rs, rowNum) -> new SuiteInfo(rs.getLong("id"), rs.getLong("project_id"),
                        rs.getString("name"),
                        fromJsonLongs(rs.getString("case_ids_json")),
                        fromJsonLongs(rs.getString("env_ids_json")),
                        fromJsonLongs(rs.getString("default_assignee_ids_json")),
                        rs.getString("cron_expr"), rs.getString("last_run_at"),
                        rs.getInt("status"), SuiteInfo.statusLabel(rs.getInt("status")),
                        rs.getInt("case_count"), rs.getLong("created_by"),
                        rs.getString("created_by_name"), rs.getString("created_at"),
                        rs.getString("automation_target"), rs.getString("appauto_engine"),
                        readLongColumn(rs, "appauto_env_profile_id"),
                        readLongColumn(rs, "mock_space_id")), id);
        if (rows.isEmpty()) {
            throw new ServiceException(RegressionSuiteRefusals.suiteMissing(id), BizErrorCode.NOT_FOUND);
        }
        return rows.get(0);
    }

    /** Server-side sort whitelist (suite list; frontend column props map onto these
     * keys via SORT_KEY_MAP). Values are physical columns or select aliases —
     * case_count is the subquery alias from the list SELECT, never user input. */
    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "name", "s.name",
            "status", "s.status",
            "case_count", "case_count",
            "created_at", "s.created_at",
            "last_run_at", "s.last_run_at");

    @Override
    public PageResult<SuiteInfo> list(SuiteQuery query) {
        int page = Math.max(1, query.page());
        int size = Math.min(200, Math.max(1, query.size() == 0 ? 20 : query.size()));
        // Sort keys must resolve to a physical column (or select alias) through the
        // shared whitelist — user input never reaches the SQL string (SortColumnPolicy.require,
        // same posture as the plan/batch/mcp/skill/tool domains; off-list key = fail-closed 400).
        String sortColumn = SortColumnPolicy.require(query.sortBy(), SORT_COLUMNS, "回归集");
        String order = sortColumn == null
                ? " ORDER BY s.id DESC"
                : " ORDER BY " + sortColumn
                        + ("asc".equalsIgnoreCase(query.sortDir()) ? " ASC" : " DESC") + ", s.id DESC";
        StringBuilder where = new StringBuilder(" WHERE s.project_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(query.projectId());
        if (query.keyword() != null && !query.keyword().isBlank()) {
            where.append(" AND s.name ILIKE ?");
            args.add("%" + query.keyword().trim() + "%");
        }
        if (query.status() != null) {
            where.append(" AND s.status = ?");
            args.add(query.status());
        }
        long total = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_regression_suite s" + where, Long.class, args.toArray());
        args.add(size);
        args.add((page - 1) * size);
        List<SuiteInfo> list = jdbcTemplate.query("""
                SELECT s.*, to_char(s.created_at, 'YYYY-MM-DD HH24:MI:SS') AS created_at,
                       to_char(s.last_run_at, 'YYYY-MM-DD HH24:MI:SS') AS last_run_at,
                       (SELECT count(*) FROM tp_test_case c
                         WHERE c.id::text IN (SELECT json_array_elements_text(s.case_ids_json::json))
                           AND c.deleted = 0 AND c.status <> 9) AS case_count,
                       COALESCE(u.display_name, '') AS created_by_name
                FROM tp_regression_suite s
                LEFT JOIN tp_user u ON u.id = s.created_by""" + where
                + order + " LIMIT ? OFFSET ?",
                (rs, rowNum) -> new SuiteInfo(rs.getLong("id"), rs.getLong("project_id"),
                        rs.getString("name"),
                        fromJsonLongs(rs.getString("case_ids_json")),
                        fromJsonLongs(rs.getString("env_ids_json")),
                        fromJsonLongs(rs.getString("default_assignee_ids_json")),
                        rs.getString("cron_expr"), rs.getString("last_run_at"),
                        rs.getInt("status"), SuiteInfo.statusLabel(rs.getInt("status")),
                        rs.getInt("case_count"), rs.getLong("created_by"),
                        rs.getString("created_by_name"), rs.getString("created_at"),
                        rs.getString("automation_target"), rs.getString("appauto_engine"),
                        readLongColumn(rs, "appauto_env_profile_id"),
                        readLongColumn(rs, "mock_space_id")),
                args.toArray());
        return new PageResult<>(list, total);
    }

    @Override
    @Transactional
    @Audit(objectType = "regression_suite", action = "suite_delete", objectId = "#id")
    public void delete(long operatorId, long id) {
        get(id);
        jdbcTemplate.update("DELETE FROM tp_regression_suite WHERE id = ?", id);
    }

    @Override
    @Transactional
    @Audit(objectType = "regression_suite", action = "suite_run", objectId = "#id", after = "#result")
    public SuiteRunResult run(long operatorId, long id) {
        SuiteInfo suite = get(id);
        if (suite.status() != 1) {
            throw new ServiceException(RegressionSuiteRefusals.suiteDisabled(id),
                    BizErrorCode.STATE_MACHINE_REJECT);
        }
        if (suite.caseIds().isEmpty()) {
            throw new ServiceException(RegressionSuiteRefusals.noCases(id),
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (suite.envIds().isEmpty()) {
            throw new ServiceException(RegressionSuiteRefusals.noEnv(id),
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (suite.defaultAssigneeIds().isEmpty()) {
            throw new ServiceException(RegressionSuiteRefusals.noAssignee(id),
                    BizErrorCode.VALIDATION_ERROR);
        }

        int skipped = 0;
        List<Long> validCaseIds = new ArrayList<>();
        for (Long caseId : suite.caseIds()) {
            Long ok = jdbcTemplate.queryForObject("""
                    SELECT count(*) FROM tp_test_case
                    WHERE id = ? AND project_id = ? AND deleted = 0 AND status <> 9
                    """, Long.class, caseId, suite.projectId());
            if (ok != null && ok > 0) {
                validCaseIds.add(caseId);
            } else {
                skipped++;
            }
        }
        if (validCaseIds.isEmpty()) {
            throw new ServiceException(RegressionSuiteRefusals.allCasesArchived(skipped),
                    BizErrorCode.VALIDATION_ERROR);
        }

        String planName = suite.name() + "-" + LocalDateTime.now().format(TS);
        PlanInfo plan = planService.create(operatorId, new PlanCreateInput(
                suite.projectId(), planName, null, operatorId, suite.envIds(),
                CaseScopeRule.of(List.of(), List.of(), null, List.of(), validCaseIds),
                com.sixbean.testpike.core.plans.api.dto.EntryCriteria.of(false),
                com.sixbean.testpike.core.plans.api.dto.ExitCriteria.of(null, null),
                null, null, null));

        List<Long> assignees = suite.defaultAssigneeIds();
        List<Long> planCaseIds = planService.listCases(plan.id(), null).stream()
                .map(c -> c.caseId()).toList();
        for (int i = 0; i < planCaseIds.size(); i++) {
            long assignee = assignees.get(i % assignees.size());
            planService.assignCases(operatorId, plan.id(),
                    new PlanAssignInput(List.of(planCaseIds.get(i)), assignee));
        }

        planService.publish(operatorId, plan.id());
        GenerateBatchResult batch = planService.generateBatches(operatorId, plan.id());

        jdbcTemplate.update("UPDATE tp_regression_suite SET last_run_at = now(), updated_at = now() WHERE id = ?",
                id);

        String message = "已生成计划「" + planName + "」：" + batch.message();
        if (skipped > 0) {
            message += "；" + skipped + " 条已归档/删除用例自动跳过（可在「回归集」页「编辑」→「用例圈选」更新清单）";
        }
        return new SuiteRunResult(plan.id(), planName, batch.batchCount(), batch.taskCount(),
                skipped, message);
    }


    private void validate(long operatorId, long projectId, String name, List<Long> caseIds,
                          List<Long> envIds, List<Long> assigneeIds) {
        projectAccessService.requireProjectMember(projectId, operatorId);
        if (name == null || name.isBlank()) {
            throw new ServiceException(RegressionSuiteRefusals.nameBlank(), BizErrorCode.VALIDATION_ERROR);
        }
        if (name.length() > 100) {
            throw new ServiceException(RegressionSuiteRefusals.nameTooLong(name.length()), BizErrorCode.VALIDATION_ERROR);
        }
        if (caseIds == null || caseIds.isEmpty()) {
            throw new ServiceException(RegressionSuiteRefusals.casesEmpty(), BizErrorCode.VALIDATION_ERROR);
        }
        if (envIds == null || envIds.isEmpty()) {
            throw new ServiceException(RegressionSuiteRefusals.envEmpty(), BizErrorCode.VALIDATION_ERROR);
        }
        String placeholders = String.join(",",
                caseIds.stream().map(x -> "?").toList());
        Long matched = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_test_case WHERE id IN (" + placeholders + ") AND project_id = ?",
                Long.class, concat(caseIds, projectId));
        if (matched == null || matched != caseIds.size()) {
            throw new ServiceException(RegressionSuiteRefusals.foreignCases(
                    matched == null ? caseIds.size() : (int) (caseIds.size() - matched), caseIds.size()),
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (assigneeIds != null && !assigneeIds.isEmpty()) {
            List<Long> memberIds = memberService.list(projectId).stream()
                    .map(MemberInfo::userId).toList();
            for (Long uid : assigneeIds) {
                if (!memberIds.contains(uid)) {
                    throw new ServiceException(RegressionSuiteRefusals.assigneeNotMember(uid),
                            BizErrorCode.VALIDATION_ERROR);
                }
            }
        }
    }

    private static List<Long> safeList(List<Long> list) {
        return list == null ? List.of() : list;
    }

    private static String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    
    private Long readLongColumn(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Object[] concat(List<Long> ids, Long tail) {
        List<Object> all = new ArrayList<>(ids);
        all.add(tail);
        return all.toArray();
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new ServiceException(RegressionSuiteRefusals.serializeFailed(), BizErrorCode.INTERNAL_ERROR);
        }
    }

    private List<Long> fromJsonLongs(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Long>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }
}
