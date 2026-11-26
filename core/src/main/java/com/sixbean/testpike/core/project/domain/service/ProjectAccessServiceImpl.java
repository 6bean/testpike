package com.sixbean.testpike.core.project.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.program.domain.component.ProjectRoleExpansion;
import com.sixbean.testpike.core.project.domain.component.ProjectRoleResolver;
import org.springframework.stereotype.Service;

/**
 * <p>Role lookups consume ProjectRoleExpansion — the single point where direct grants
 * and active program (group) grants become one effective role. Existence checks stay
 * on ProjectService. No second decision path is allowed (no double lookup).
 *
 */
@Service
public class ProjectAccessServiceImpl implements ProjectAccessService {

    private final ProjectService projectService;

    /** Direct ∪ group expansion single point. */
    private final ProjectRoleExpansion roleExpansion;

    public ProjectAccessServiceImpl(ProjectService projectService,
                                    ProjectRoleExpansion roleExpansion) {
        this.projectService = projectService;
        this.roleExpansion = roleExpansion;
    }

    /**
     * Effective project role: 0 none, 1 member (direct or group-expanded), 2 direct owner.
     */
    public int getMemberRole(long projectId, long userId) {
        return roleExpansion.effectiveRole(projectId, userId);
    }

    
    public void requireProjectMember(long projectId, long userId) {
        requireProject(projectId);
        // threshold predicate stays single-sourced in ProjectRoleResolver.isMemberRole
        if (!ProjectRoleResolver.isMemberRole(roleExpansion.effectiveRole(projectId, userId))) {
            throw new ServiceException("您不是该项目成员，无权访问该项目资源——请项目管理员在「项目管理」打开该项目「成员管理」页点「添加成员」将您加入后重试",
                    BizErrorCode.FORBIDDEN);
        }
    }

    
    public void requireProjectOwner(long projectId, long userId) {
        requireProject(projectId);
        // owner semantics unchanged: effectiveRole reaches 2 only through a direct grant
        // (short-circuited inside the expansion component), so a program owner never
        // upgrades to project owner — write paths stay direct-grant governed.
        if (!ProjectRoleResolver.isOwnerRole(roleExpansion.effectiveRole(projectId, userId))) {
            throw new ServiceException("该操作仅项目负责人可执行——可在「项目管理」打开该项目「成员管理」页，由现负责人选中您点「设为负责人」完成移交后重试",
                    BizErrorCode.FORBIDDEN);
        }
    }

    private void requireProject(long projectId) {
        if (!projectService.exists(projectId)) {
            throw new ServiceException("项目不存在或已被删除——请到「项目管理」页确认所选项目；已归档的可由系统管理员点「归档项目」按钮后「还原」",
                    BizErrorCode.NOT_FOUND);
        }
    }
}
