package com.sixbean.testpike.core.program.domain.component;

import com.sixbean.testpike.core.program.domain.service.ProgramService;
import com.sixbean.testpike.core.rbac.domain.service.RoleService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Single point that narrows a domain list query to one program lens (project set).
 *
 * <p>Contract (locked by ProgramLensScopeTest, consumed by every list endpoint that
 * gained a programId filter — case/API test/perf/batch/defect/report):
 * <pre>
 *   programId &lt;= 0            → null  (no lens requested; caller skips narrowing)
 *   archived lens (state ≠ 1) → empty (never expands, same rule as ProjectRoleExpansion)
 *   data scope = all projects → every project inside the lens
 *   otherwise                 → lens projects ∩ caller's visible projects
 * </pre>
 * Visibility always comes from {@link ProjectRoleExpansion#expandedProjectIds(long)}
 * (direct grants ∪ group grants) — domain code must not re-implement the lookup.
 * An unreachable lens yields an empty set (honest empty page), never an error:
 * the lens narrowing can only shrink the result of a list the caller could query anyway.
 *
 * <p>No cache: attaching/detaching projects takes effect on the next evaluation.
 */
@Component
public class ProgramLensScope {

    private final ProgramService programService;
    private final ProjectRoleExpansion roleExpansion;
    private final RoleService roleService;
    private final NamedParameterJdbcTemplate jdbc;

    public ProgramLensScope(ProgramService programService, ProjectRoleExpansion roleExpansion,
                            RoleService roleService, NamedParameterJdbcTemplate jdbc) {
        this.programService = programService;
        this.roleExpansion = roleExpansion;
        this.roleService = roleService;
        this.jdbc = jdbc;
    }

    /**
     * Projects the operator may list through one lens.
     *
     * @param programId  lens id; 0/negative means "no lens" and returns null.
     * @param operatorId caller; null = trusted internal call without a session user
     *                   (visibility intersection is skipped, mirroring the self-filter
     *                   convention where a null operator applies no personal narrowing).
     * @return null when no lens is requested; otherwise the (possibly empty) project id set.
     */
    public Set<Long> visibleProjectIds(long programId, Long operatorId) {
        if (programId <= 0) {
            return null;
        }
        Integer active = jdbc.queryForObject(
                "SELECT count(*) FROM tp_program WHERE id = :pid AND state = 1",
                new MapSqlParameterSource("pid", programId), Integer.class);
        if (active == null || active == 0) {
            return Set.of();
        }
        // Membership rows stay owned by ProgramService — the single query source.
        List<Long> inside = programService.listProjectIds(programId);
        if (inside.isEmpty()) {
            return Set.of();
        }
        if (operatorId == null || roleService.dataScopeOfUser(operatorId) == 1) {
            return new HashSet<>(inside);
        }
        Set<Long> visible = roleExpansion.expandedProjectIds(operatorId);
        Set<Long> narrowed = new HashSet<>(inside);
        narrowed.retainAll(visible);
        return narrowed;
    }
}
