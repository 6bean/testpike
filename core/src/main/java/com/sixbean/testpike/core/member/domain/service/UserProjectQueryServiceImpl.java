package com.sixbean.testpike.core.member.domain.service;

import com.sixbean.testpike.core.member.api.dto.UserProjectInfo;
import com.sixbean.testpike.core.program.domain.component.ProjectRoleExpansion;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;


@Service
public class UserProjectQueryServiceImpl implements UserProjectQueryService {

    /**
     * Rows over the expanded visible id set: the role comes from the direct grant when
     * one exists; group-only projects read as member level (role 1), never as a second
     * row for the same project.
     */
    private static final String SELECT_BY_IDS_SQL = """
            SELECT p.id AS project_id, p.name, p.code,
                   CASE WHEN up.role IS NULL THEN 1 ELSE up.role END AS role,
                   p.default_lang
            FROM tp_project p
            LEFT JOIN tp_user_project up ON up.project_id = p.id AND up.user_id = :uid
            WHERE p.status = 1 AND p.id IN (:ids)
            ORDER BY p.created_at, p.id
            """;

    
    private static final String SELECT_VISIBLE_SQL = """
            SELECT p.id AS project_id, p.name, p.code, COALESCE(up.role, 0) AS role, p.default_lang
            FROM tp_project p
            LEFT JOIN tp_user_project up ON up.project_id = p.id AND up.user_id = ?
            WHERE p.status = 1
            ORDER BY p.created_at, p.id
            """;

    private final JdbcTemplate jdbcTemplate;

    private final NamedParameterJdbcTemplate namedJdbc;

    /** Direct ∪ program-grant expansion single point (member visibility must match access). */
    private final ProjectRoleExpansion roleExpansion;

    public UserProjectQueryServiceImpl(JdbcTemplate jdbcTemplate,
                                       ProjectRoleExpansion roleExpansion) {
        this.jdbcTemplate = jdbcTemplate;
        this.namedJdbc = new NamedParameterJdbcTemplate(jdbcTemplate);
        this.roleExpansion = roleExpansion;
    }

    /**
 * <p>Visible set = direct grants ∪ group (program) grants. A user the access layer
 * admits into a project must never be invisible here, or the switcher hides reachable
 * work. Group-only rows read as member level; direct rows keep their role, one row each.
 */
    public List<UserProjectInfo> findByUserId(long userId) {
        Set<Long> visible = roleExpansion.expandedProjectIds(userId);
        if (visible.isEmpty()) {
            return List.of();
        }
        return namedJdbc.query(SELECT_BY_IDS_SQL,
                new MapSqlParameterSource("uid", userId).addValue("ids", visible),
                this::mapRow);
    }

    @Override
    public List<UserProjectInfo> findVisibleByUserId(long userId, boolean systemManager) {
        if (!systemManager) {
            return findByUserId(userId);
        }
        return jdbcTemplate.query(SELECT_VISIBLE_SQL, this::mapRow, userId);
    }

    
    private UserProjectInfo mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        int role = rs.getInt("role");
        String roleLabel = switch (role) {
            case 2 -> "项目负责人";
            case 1 -> "成员";
            default -> "系统角色可见";
        };
        return new UserProjectInfo(
                rs.getLong("project_id"),
                rs.getString("name"),
                rs.getString("code"),
                role,
                roleLabel,
                rs.getString("default_lang"));
    }
}
