package com.sixbean.testpike.core.project.domain.component;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * <ul>
 * </ul>
 * <ul>
 * </ul>
 *
 */
@Component
public class ProjectRoleResolver {

    
    public static final int ROLE_NONE = 0;

    
    public static final int ROLE_MEMBER = 1;

    
    public static final int ROLE_OWNER = 2;

    private final JdbcTemplate jdbcTemplate;

    public ProjectRoleResolver(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    
    public int roleOf(long projectId, long userId) {
        List<Integer> rows = jdbcTemplate.query(
                "SELECT role FROM tp_user_project WHERE project_id = ? AND user_id = ?",
                (rs, rowNum) -> rs.getInt(1), projectId, userId);
        return rows.isEmpty() ? ROLE_NONE : rows.get(0);
    }

    
    public static boolean isOwnerRole(int role) {
        return role >= ROLE_OWNER;
    }

    
    public static boolean isMemberRole(int role) {
        return role >= ROLE_MEMBER;
    }

    
    public boolean isOwner(long projectId, long userId) {
        return isOwnerRole(roleOf(projectId, userId));
    }

    
    public boolean isMember(long projectId, long userId) {
        return isMemberRole(roleOf(projectId, userId));
    }
}
