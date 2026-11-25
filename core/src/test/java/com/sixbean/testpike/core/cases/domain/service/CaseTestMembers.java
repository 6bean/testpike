package com.sixbean.testpike.core.cases.domain.service;

import org.springframework.jdbc.core.JdbcTemplate;


final class CaseTestMembers {

    private CaseTestMembers() {
    }

    
    static void addMember(JdbcTemplate jdbc, long projectId, long userId) {
        addMemberWithRole(jdbc, projectId, userId, "tester");
    }

    
    static void addMemberWithRole(JdbcTemplate jdbc, long projectId, long userId, String roleCode) {
        ensureUserWithRole(jdbc, userId, roleCode);
        jdbc.update(
                "INSERT INTO tp_user_project (project_id, user_id, role) VALUES (?, ?, 1) ON CONFLICT DO NOTHING",
                projectId, userId);
    }

    
    static void ensureUserWithRole(JdbcTemplate jdbc, long userId, String roleCode) {
        int legacyRole = switch (roleCode) {
            case "test_lead" -> 2;
            case "developer" -> 3;
            case "admin" -> 4;
            default -> 1;
        };
        Integer exists = jdbc.queryForObject(
                "SELECT count(*) FROM tp_user WHERE id = ?", Integer.class, userId);
        if (exists == null || exists == 0) {
            jdbc.update(
                    "INSERT INTO tp_user (id, username, password_hash, role, status) VALUES (?, ?, NULL, ?, 1)",
                    userId, "k6user" + userId, legacyRole);
        } else {
            jdbc.update("UPDATE tp_user SET role = ? WHERE id = ?", legacyRole, userId);
        }
        jdbc.update("""
                DELETE FROM tp_user_role
                WHERE user_id = ? AND role_id IN (SELECT id FROM tp_role WHERE code IN ('tester', 'test_lead', 'developer', 'admin'))
                """, userId);
        jdbc.update("""
                INSERT INTO tp_user_role (user_id, role_id)
                SELECT ?, id FROM tp_role WHERE code = ?
                ON CONFLICT DO NOTHING
                """, userId, roleCode);
    }

 
    static void removeMember(JdbcTemplate jdbc, long projectId, long userId) {
        jdbc.update("DELETE FROM tp_user_project WHERE project_id = ? AND user_id = ?", projectId, userId);
    }

    /** Teardown pair for ensureUserWithRole: drops a suite-owned actor and
     * its membership rows. Never pass a factory user id (the shared
     * administrator id=1 is a notification recipient set). */
    static void dropUser(JdbcTemplate jdbc, long userId) {
        jdbc.update("DELETE FROM tp_user_role WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM tp_user_project WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM tp_user WHERE id = ?", userId);
    }
}
