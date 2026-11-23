package com.sixbean.testpike.core.defects.domain.component;

import com.sixbean.testpike.core.common.domain.component.SqlPlaceholders;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;


public final class DefectFilters {

    private DefectFilters() {
    }

    /**
 *
 * @param programProjectIds Project-set lens resolution (ProgramLensScope single source):
 * null = no lens (legacy single-project WHERE untouched),
 * empty = honest empty page (AND 1 = 0), non-empty = lens
 * projects IN + conjunctive explicit projectId when given.
 */
    public static DefectFilteredWhere build(long projectId, Set<Long> programProjectIds,
                                            Integer status, Integer severity,
                                            Long assigneeId, Long caseId, String keyword) {
        StringBuilder where = new StringBuilder(" WHERE");
        List<Object> args = new ArrayList<>();
        if (programProjectIds == null) {
            where.append(" d.project_id = ?");
            args.add(projectId);
        } else if (programProjectIds.isEmpty()) {
            where.append(" 1 = 0");
        } else {
            where.append(" d.project_id IN (")
                    .append(SqlPlaceholders.in(programProjectIds.size())).append(")");
            args.addAll(programProjectIds);
            // Lens + explicit project stays conjunctive (same posture as the batch
            // domain): the caller sees only rows inside both the lens and the picked project.
            if (projectId > 0L) {
                where.append(" AND d.project_id = ?");
                args.add(projectId);
            }
        }
        if (status != null) {
            where.append(" AND d.status = ?");
            args.add(status);
        }
        if (severity != null) {
            where.append(" AND d.severity = ?");
            args.add(severity);
        }
        if (assigneeId != null) {
            where.append(" AND d.assignee_id = ?");
            args.add(assigneeId);
        }
        if (caseId != null) {
            where.append(" AND d.case_id = ?");
            args.add(caseId);
        }
        String kw = keyword == null ? "" : keyword.trim();
        if (!kw.isEmpty()) {
            where.append(" AND d.title LIKE ?");
            args.add("%" + kw + "%");
        }
        return new DefectFilteredWhere(where.toString(), args);
    }

    /** Back-compatible form: no program lens (the export path keeps its current scope). */
    public static DefectFilteredWhere build(long projectId, Integer status, Integer severity,
                                            Long assigneeId, Long caseId, String keyword) {
        return build(projectId, null, status, severity, assigneeId, caseId, keyword);
    }
}
