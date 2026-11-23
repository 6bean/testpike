package com.sixbean.testpike.core.program.domain.component;

import com.sixbean.testpike.core.project.domain.component.ProjectRoleResolver;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Single point where direct and group grants become one effective project role.
 *
 * <p>Contract (cluster Y decision, locked by ProjectRoleExpansionTest):
 * <pre>
 *   effective role = max(direct tp_user_project role,
 *                        1 if user is in any ACTIVE program containing the project)
 * </pre>
 * A program owner (tp_user_program.role = 2) expands to member level only — upgrading a
 * group grant into project ownership would silently widen the write path and break the
 * H1 isolation story. Every consumer that asks "what may this user do in this project"
 * must go through this component; direct channel lookups elsewhere must not be
 * duplicated into a second decision point.
 *
 * <p>No cache (mirrors {@link ProjectRoleResolver}): revoking a grant takes effect on
 * the next evaluation.
 */
@Component
public class ProjectRoleExpansion {

    private final ProjectRoleResolver roleResolver;
    private final NamedParameterJdbcTemplate jdbc;

    public ProjectRoleExpansion(ProjectRoleResolver roleResolver, NamedParameterJdbcTemplate jdbc) {
        this.roleResolver = roleResolver;
        this.jdbc = jdbc;
    }

    /** Effective project role for a user: 0 none, 1 member (direct or expanded), 2 direct owner. */
    public int effectiveRole(long projectId, long userId) {
        int direct = roleResolver.roleOf(projectId, userId);
        if (ProjectRoleResolver.isOwnerRole(direct)) {
            // Only a direct grant can reach owner level; short-circuit the expansion query.
            return direct;
        }
        return Math.max(direct, ProjectRoleResolver.ROLE_MEMBER * groupGrantCount(projectId, userId));
    }

    /** Whether any active program grants this user access to the project. */
    public boolean hasGroupGrant(long projectId, long userId) {
        return groupGrantCount(projectId, userId) > 0;
    }

    /**
     * All project ids visible to a user: direct grants union the expansion of group
     * grants through active programs. This is the membership-visibility primitive the
     * project switcher consumes (two-level switcher, Y-2b).
     */
    public Set<Long> expandedProjectIds(long userId) {
        List<Long> rows = jdbc.queryForList(
                "SELECT project_id FROM tp_user_project WHERE user_id = :uid "
                        + "UNION "
                        + "SELECT pp.project_id FROM tp_user_program up "
                        + "JOIN tp_program_project pp ON pp.program_id = up.program_id "
                        + "JOIN tp_program p ON p.id = up.program_id "
                        + "WHERE up.user_id = :uid AND up.role >= 1 AND p.state = 1",
                new MapSqlParameterSource("uid", userId), Long.class);
        return new HashSet<>(rows);
    }

    private int groupGrantCount(long projectId, long userId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM tp_user_program up "
                        + "JOIN tp_program_project pp ON pp.program_id = up.program_id "
                        + "JOIN tp_program p ON p.id = up.program_id "
                        + "WHERE up.user_id = :uid AND pp.project_id = :pid "
                        + "AND up.role >= 1 AND p.state = 1",
                new MapSqlParameterSource().addValue("uid", userId).addValue("pid", projectId),
                Integer.class);
        return n == null ? 0 : n;
    }
}
