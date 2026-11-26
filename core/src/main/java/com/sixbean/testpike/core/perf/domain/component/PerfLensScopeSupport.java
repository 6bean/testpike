package com.sixbean.testpike.core.perf.domain.component;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.program.domain.component.ProgramLensScope;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Single source for the program-lens (project-set) scope of the paged perf doors
 * (scenario ledger + dataset pool). One shared implementation, consumed by both —
 * the same anchor rule and the same three-branch predicate must never be written twice.
 *
 * <p>Contract (locked by the perf lens suite, mirrors the defect/batch doors):
 * exactly one scope anchor is required — an explicit project OR a program lens;
 * the lens resolves through {@link ProgramLensScope} (lens projects ∩ visibility),
 * an unreachable lens yields an honest empty page, and lens + explicit project stays
 * conjunctive. All values ride as bound parameters — none reach the SQL text.
 */
@Component
public class PerfLensScopeSupport {

    /** Scope single source: null = no lens, empty = honest empty page, non-empty = IN set. */
    private final ProgramLensScope programLensScope;

    public PerfLensScopeSupport(ProgramLensScope programLensScope) {
        this.programLensScope = programLensScope;
    }

    /**
     * Validates that exactly one scope anchor is present. A list with neither a
     * project nor a lens would dump every project's rows — refused by name.
     *
     * @param subject  what the list is of (human wording for the rejection message).
     */
    public void requireAnchor(long projectId, long programId, String subject) {
        if (projectId <= 0 && programId <= 0) {
            throw new ServiceException(subject + "列表需要指定项目或项目集——请先在顶栏项目切换器选择项目，"
                    + "或在顶栏项目集切换器改选项目集（含「全部项目集」）后重新查询", BizErrorCode.VALIDATION_ERROR);
        }
    }

    /**
     * Resolves the lens for one door call. operatorId 0 means a trusted internal
     * caller without a session user (no personal narrowing, self-filter convention).
     */
    public Set<Long> resolveLens(long operatorId, long programId) {
        return programId > 0
                ? programLensScope.visibleProjectIds(programId, operatorId <= 0 ? null : operatorId)
                : null;
    }

    /**
     * WHERE fragment for the project anchor shared by the paged perf doors:
     * lens set (named IN), honest empty page (1=0), or the historical equality;
     * an explicit project AND a lens stays conjunctive. Bindings land in the
     * caller's parameter source — no values ever reach the SQL text.
     */
    public static String projectScope(Set<Long> lensProjectIds, long projectId,
                                      MapSqlParameterSource params) {
        StringBuilder where = new StringBuilder();
        if (lensProjectIds != null) {
            if (lensProjectIds.isEmpty()) {
                where.append("1 = 0");
            } else {
                // NamedParameterJdbcTemplate expands a Collection bound under one
                // name into the IN list — no positional placeholders here.
                where.append("project_id IN (:plens)");
                params.addValue("plens", lensProjectIds);
            }
        } else {
            where.append("project_id = :p");
            params.addValue("p", projectId);
        }
        if (projectId > 0 && lensProjectIds != null && !lensProjectIds.isEmpty()) {
            where.append(" AND project_id = :p");
            params.addValue("p", projectId);
        }
        return where.toString();
    }
}
