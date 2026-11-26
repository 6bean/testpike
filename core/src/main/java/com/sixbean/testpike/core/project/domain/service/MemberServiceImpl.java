package com.sixbean.testpike.core.project.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.core.audit.domain.component.Audit;
import com.sixbean.testpike.core.project.api.dto.MemberInfo;
import com.sixbean.testpike.core.project.domain.component.ProjectRoleResolver;


import com.sixbean.testpike.common.exception.ServiceException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;

/**
 * <ul>
 * </ul>
 *
 */
@Service
public class MemberServiceImpl implements MemberService {

    private final JdbcTemplate jdbcTemplate;
    private final ProjectService projectService;

    public MemberServiceImpl(JdbcTemplate jdbcTemplate, ProjectService projectService) {
        this.jdbcTemplate = jdbcTemplate;
        this.projectService = projectService;
    }

    
    public List<MemberInfo> list(long projectId) {
        return jdbcTemplate.query("""
                SELECT m.user_id, COALESCE(u.username, '') AS username, COALESCE(u.display_name, '') AS nick_name,
                       m.role,
                       COALESCE(string_agg(r.code, ',' ORDER BY r.code) FILTER (WHERE r.code IS NOT NULL), '') AS roles
                FROM tp_user_project m
                LEFT JOIN tp_user u ON u.id = m.user_id AND u.deleted = 0
                LEFT JOIN tp_user_role ur ON ur.user_id = m.user_id
                LEFT JOIN tp_role r ON r.id = ur.role_id AND r.status = 1
                WHERE m.project_id = ?
                GROUP BY m.user_id, u.username, u.display_name, m.role
                ORDER BY m.role DESC, m.user_id
                """,
                (rs, rowNum) -> new MemberInfo(rs.getLong("user_id"), rs.getString("username"),
                        rs.getString("nick_name"), rs.getInt("role"),
                        rs.getInt("role") == 2 ? "项目负责人" : "成员",
                        roleCodes(rs.getString("roles"))),
                projectId);
    }

    
    private static List<String> roleCodes(String joined) {
        if (joined == null || joined.isEmpty()) {
            return List.of();
        }
        return Arrays.asList(joined.split(","));
    }

    
    @Audit(objectType = "project_member", action = "add", objectId = "#projectId", after = "#userId")
    public void add(long projectId, long userId, int role) {
        upsert(projectId, userId, role);
    }

    
    @Audit(objectType = "project_member", action = "update_role", objectId = "#projectId", after = "#userId")
    public void updateRole(long projectId, long userId, int role) {
        upsert(projectId, userId, role);
    }

    
    private void upsert(long projectId, long userId, int role) {
        requireProject(projectId);
        requireRole(role);
        jdbcTemplate.update("""
                INSERT INTO tp_user_project (project_id, user_id, role) VALUES (?, ?, ?)
                ON CONFLICT (project_id, user_id) DO UPDATE SET role = EXCLUDED.role, updated_at = now()
                """, projectId, userId, role);
    }

    
    @Audit(objectType = "project_member", action = "remove", objectId = "#projectId", after = "#userId")
    @Transactional
    public void remove(long projectId, long userId) {
        requireProject(projectId);
        if (ProjectRoleResolver.isOwnerRole(projectService.getMemberRole(projectId, userId))) {
            Integer ownerCount = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM tp_user_project WHERE project_id = ? AND role = 2",
                    Integer.class, projectId);
            if (ownerCount != null && ownerCount <= 1) {
                throw new ServiceException("项目至少保留一名项目负责人——请先在「成员管理」页选中接任成员点「设为负责人」完成移交，再移出原负责人",
                        BizErrorCode.VALIDATION_ERROR);
            }
        }
        jdbcTemplate.update(
                "DELETE FROM tp_user_project WHERE project_id = ? AND user_id = ?", projectId, userId);
        jdbcTemplate.update(
                "UPDATE tp_test_case SET assignee_id = NULL, assigned_at = NULL, updated_at = now()"
                        + " WHERE project_id = ? AND assignee_id = ?",
                projectId, userId);
    }

    private void requireProject(long projectId) {
        if (!projectService.exists(projectId)) {
            throw new ServiceException("项目不存在或已被删除——请到「项目管理」页确认所选项目；已归档的可由系统管理员点「归档项目」按钮后「还原」",
                    BizErrorCode.NOT_FOUND);
        }
    }

    private void requireRole(int role) {
        if (role != 1 && role != 2) {
            throw new ServiceException("项目角色仅支持 1=成员、2=项目负责人——请改成「1」或「2」后重新提交；页面入口在「成员管理」的角色下拉",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }
}
