package com.sixbean.testpike.core.cases.domain.component;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.common.domain.component.LikePattern;
import com.sixbean.testpike.core.common.domain.component.SqlPlaceholders;
import com.sixbean.testpike.core.rbac.domain.service.RoleService;
import com.sixbean.testpike.core.tags.domain.component.TagQuerySupport;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Common component for case filter conditions (CaseService.list and export share the same WHERE).
 * Build logic red line: the same logic appearing at least twice must be extracted into a common component; the criteria are naturally consistent, so the list view equals the export result.
 * <p>Semantics (moved down as-is from CaseServiceImpl.list with zero behavior change): data scope filtering.
 * Self means created_by OR assignee_id; all projects projectId=0 is allowed only when data scope=1, otherwise return 403 in plain language.
 * The filters include 9 states, priority, confidence, assignee (0=unassigned IS NULL), phase (0=unattributed IS NULL), and keyword.
 * Keyword matching uses LikePattern normalization, escaping, and truncation ≤50, matching 5 columns; tags use TagQuerySupport ownership validation.
 *
 */
public final class CaseFilters {

    private CaseFilters() {
    }

    /**
     * Assembles the WHERE fragment shared by the case list and export.
     *
     * @param projectId Project ID (0=all projects, available only when data scope=all,
     *                  or when a program lens narrows the visible set instead).
     * @param programProjectIds Project ids resolved by ProgramLensScope for one lens
     *                  (null=no lens; empty=honest empty page via AND 1=0; otherwise
     *                  AND project_id IN (...)). Applied before the explicit projectId
     *                  so a lens + project stays conjunctive.
     * @param status Status 1~9 (null=all).
     * @param keyword Keyword (null/empty=all).
     * @param priority Priority (null=all).
     * @param confidence Confidence (null=all).
     * @param assigneeId Assignee (0=unassigned IS NULL; null=all).
     * @param phase Phase (0=unattributed IS NULL; null=all).
     * @param tagIds Tag ID list (null/empty=all).
     * @param operatorId Operator (subject of data scope filtering; null=do not apply self filtering, full scope within project).
     * @param roleService Role service (data scope resolution).
     * @return WHERE fragment + parameters.
     */
    /**
 * Assembles the WHERE fragment shared by the case list and export (extended with excellentOnly).
     *
 * @param excellentOnly Excellent cases only (true=AND excellent_flag=1, migration file partial index; null/false=all).
     */
    public static CaseFilteredWhere build(long projectId, Set<Long> programProjectIds,
                                 Integer status, String keyword, Integer priority,
                                 Integer confidence, Long assigneeId, Integer phase,
                                 List<Long> tagIds, Boolean excellentOnly,
                                 Long operatorId, RoleService roleService) {
        return build(projectId, programProjectIds, status, keyword, priority, confidence,
                assigneeId, phase, tagIds, excellentOnly, null, null, operatorId, roleService);
    }

    /**
 * Same WHERE with the provenance facet and evidence-grade quick filters .
 *
 * @param sourceFacet One of CaseSourceFacet.FACET_VALUES (null/blank=all); any other
 * value is refused in plain language instead of silently matching nothing.
 * @param evidenceGrade 0..3 (null=all); outside the range is refused the same way.
 */
    public static CaseFilteredWhere build(long projectId, Set<Long> programProjectIds,
                                 Integer status, String keyword, Integer priority,
                                 Integer confidence, Long assigneeId, Integer phase,
                                 List<Long> tagIds, Boolean excellentOnly,
                                 String sourceFacet, Integer evidenceGrade,
                                 Long operatorId, RoleService roleService) {
        int dataScope = operatorId != null ? roleService.dataScopeOfUser(operatorId) : 2;
        StringBuilder where = new StringBuilder(" WHERE deleted = 0");
        List<Object> args = new ArrayList<>();
        if (programProjectIds != null) {
            // Program lens: pre-resolved (membership ∩ visibility) project set.
            // An empty set is an honest empty page — the caller simply sees nothing
            // through this lens (no 403: a lens can only shrink a legal list query).
            if (programProjectIds.isEmpty()) {
                where.append(" AND 1 = 0");
            } else {
                where.append(" AND project_id IN (")
                        .append(SqlPlaceholders.in(programProjectIds.size())).append(")");
                args.addAll(programProjectIds);
            }
        }
        if (projectId == 0L) {
            // All projects: allowed when data scope=all, or when a program lens already
            // narrowed the set to the caller's visible projects.
            if (programProjectIds == null && dataScope != 1) {
                throw new ServiceException("仅数据范围为「全部项目」的角色可跨项目查看用例——"
                        + "请改用具体项目筛选，或由管理员在「系统管理」→「角色管理」中调整该角色的数据范围",
                        BizErrorCode.FORBIDDEN);
            }
        } else {
            where.append(" AND project_id = ?");
            args.add(projectId);
        }
        if (dataScope == 3 && operatorId != null) {
            // Data scope=self: only own created or assigned cases (also under a lens).
            where.append(" AND (created_by = ? OR assignee_id = ?)");
            args.add(operatorId);
            args.add(operatorId);
        }
        if (status != null) {
            where.append(" AND status = ?");
            args.add(status);
        }
        if (priority != null) {
            where.append(" AND priority = ?");
            args.add(priority);
        }
        if (confidence != null) {
            where.append(" AND confidence = ?");
            args.add(confidence);
        }
        if (assigneeId != null) {
            if (assigneeId == 0L) {
 // Unassigned view (assignee_id IS NULL, cases returned to the pool).
                where.append(" AND assignee_id IS NULL");
            } else {
                where.append(" AND assignee_id = ?");
                args.add(assigneeId);
            }
        }
        if (phase != null) {
            if (phase == 0) {
                // Unattributed view (phase IS NULL).
                where.append(" AND phase IS NULL");
            } else {
                where.append(" AND phase = ?");
                args.add(phase);
            }
        }
        if (Boolean.TRUE.equals(excellentOnly)) {
 // The excellent-only view uses the migration file partial index (project_id, excellent_flag) WHERE excellent_flag=1.
            where.append(" AND excellent_flag = 1");
        }
        if (evidenceGrade != null && (evidenceGrade < 0 || evidenceGrade > 3)) {
            throw new ServiceException("证据级筛选仅支持 0~3（0=无证据 1=站外自报 2=站外实证 3=平台复证），当前值："
                    + evidenceGrade + "——请在用例列表筛选区把「证据级」改成 0~3 内的单个数字", BizErrorCode.VALIDATION_ERROR);
        }
        if (sourceFacet != null && !sourceFacet.isBlank()) {
            String facet = sourceFacet.trim().toLowerCase(Locale.ROOT);
            if (!CaseSourceFacet.FACET_VALUES.contains(facet)) {
                throw new ServiceException("来源筛选仅支持 chat(站内对话)/off_agent(站外代理)/import(导入)/"
                        + "manual(人工) 四值，当前值：" + sourceFacet
                        + "——请在用例列表筛选区的「来源」下拉中选择上述四值之一", BizErrorCode.VALIDATION_ERROR);
            }
            // Same expression the row mapping and the facet column use: one source, no drift.
            where.append(" AND (").append(CaseSourceFacet.FACET_CASE_SQL).append(") = ?");
            args.add(facet);
        }
        if (evidenceGrade != null) {
            where.append(" AND evidence_grade = ?");
            args.add(evidenceGrade);
        }
        String kw = LikePattern.normalize(keyword);
        if (kw != null) {
 // Keyword matches case_no (remote search 'case_no+title'), with special-character escaping and over-length truncation ≤50 (normalization/escaping unified in the common component LikePattern—DRY moved down).
            String pattern = "%" + LikePattern.escape(kw) + "%";
            where.append(" AND (case_no ILIKE ? ESCAPE '\\'"
                    + " OR title ILIKE ? ESCAPE '\\'"
                    + " OR precondition ILIKE ? ESCAPE '\\'"
                    + " OR expected_result ILIKE ? ESCAPE '\\'"
                    + " OR steps_text ILIKE ? ESCAPE '\\')");
            for (int i = 0; i < 5; i++) {
                args.add(pattern);
            }
        }
        // Case tags: filter cases by tags (tag dictionary project ownership validation and isolation).
        TagQuerySupport.appendCaseTagFilter(where, args, projectId, tagIds);
        return new CaseFilteredWhere(where.toString(), args);
    }
}
