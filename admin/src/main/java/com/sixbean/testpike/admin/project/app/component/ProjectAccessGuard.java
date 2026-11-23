package com.sixbean.testpike.admin.project.app.component;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.defects.api.dto.DefectLibraryView;
import com.sixbean.testpike.admin.project.infrastructure.remote.client.CoreProjectClient;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.rbac.SystemManagerRoles;


import com.sixbean.testpike.common.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * <ul>
 * </ul>
 *
 */
@Component
public class ProjectAccessGuard {

    public static final String ROLE_ADMIN = SystemManagerRoles.ROLE_ADMIN;
    public static final String ROLE_TEST_LEAD = SystemManagerRoles.ROLE_TEST_LEAD;
    public static final String ROLE_DEVELOPER = "DEVELOPER";

    private final CoreProjectClient coreProjectClient;

    public ProjectAccessGuard(CoreProjectClient coreProjectClient) {
        this.coreProjectClient = coreProjectClient;
    }

    
    public void assertCanView(CurrentUser user, long projectId) {
        if (isSystemManager(user)) {
            return;
        }
        requireProjectRole(user, projectId, 1, "您不是该项目成员，无权访问该项目资源");
    }

    
    public void assertCanManage(CurrentUser user, long projectId) {
        if (hasRole(user, ROLE_ADMIN)) {
            return;
        }
        requireProjectRole(user, projectId, 2, "该操作仅项目负责人可执行");
    }

    /**
 * Program-lens aware manage gate : when a list runs through a project-set
 * lens WITHOUT an explicit project, the single-project gate is skipped — core
 * intersects the lens with the caller's visibility and an unreachable lens
 * yields an honest empty page, so no cross-project row can surface. Any other
 * combination (explicit project, or no lens) keeps the exact manage semantics.
 */
    public void assertCanManageWithinLens(CurrentUser user, long projectId, long programId) {
        if (projectId == 0L && programId > 0L) {
            return;
        }
        assertCanManage(user, projectId);
    }

    
    public void assertCanCreate(CurrentUser user) {
        if (!isSystemManager(user)) {
            throw new ServiceException("仅系统管理员或测试负责人可创建项目", BizErrorCode.FORBIDDEN);
        }
    }

    
    public void assertCanArchive(CurrentUser user) {
        if (!hasRole(user, ROLE_ADMIN)) {
            throw new ServiceException("仅系统管理员可归档项目", BizErrorCode.FORBIDDEN);
        }
    }

    
    public void assertSystemAdminOnly(CurrentUser user, String action) {
        if (!hasRole(user, ROLE_ADMIN)) {
            throw new ServiceException("仅系统管理员可" + action, BizErrorCode.FORBIDDEN);
        }
    }

 
    public void assertCanManageUsers(CurrentUser user) {
        if (!hasRole(user, ROLE_ADMIN)) {
            throw new ServiceException("仅系统管理员可管理用户与角色", BizErrorCode.FORBIDDEN);
        }
    }

 
    public void assertCanWriteCase(CurrentUser user, long projectId) {
        assertNotDeveloper(user, "开发人员为只读角色，不能创建或编辑用例");
        assertCanView(user, projectId);
    }

    
    public void assertCanDeleteCase(CurrentUser user, long projectId) {
        assertNotDeveloper(user, "开发人员为只读角色，不能删除用例");
        assertCanManage(user, projectId);
    }

    
    public void assertCanAssignCase(CurrentUser user, long projectId) {
        assertNotDeveloper(user, "开发人员为只读角色，不能分配用例");
        assertCanManage(user, projectId);
    }

    
    public void assertCanWriteRequirement(CurrentUser user, long projectId) {
        assertNotDeveloper(user, "开发人员为只读角色，不能管理需求点");
        assertCanManage(user, projectId);
    }

    
    public void assertCanReview(CurrentUser user, long projectId, Long assigneeId) {
        assertNotDeveloper(user, "开发人员为只读角色，不能评审用例");
        if (hasRole(user, ROLE_ADMIN)) {
            return;
        }
        int role = coreProjectClient.memberRole(projectId, user.userId());
        if (role >= 2) {
            return;
        }
        if (role >= 1 && Objects.equals(assigneeId, user.userId())) {
            return;
        }
        throw new ServiceException("仅被分配人或项目负责人可评审该用例", BizErrorCode.FORBIDDEN);
    }

    
    public void assertCanBackfillDefectStatus(CurrentUser user, long projectId, String defectStatus) {
        assertNotDeveloper(user, "开发人员为只读角色，不能回填缺陷状态");
        if ("closed".equals(defectStatus)) {
            assertCanManage(user, projectId);
        } else {
            assertCanView(user, projectId);
        }
    }

    
    public void assertCanTransitionDefect(CurrentUser user, DefectLibraryView defect, Integer toStatus) {
        assertNotDeveloper(user, "开发人员为只读角色，不能流转缺陷状态");
        if (hasRole(user, ROLE_ADMIN) || memberRole(user, defect.projectId()) >= 2) {
            return;
        }
        boolean fixAction = toStatus != null && (toStatus == 2 || toStatus == 3);
        boolean verifyAction = toStatus != null && (toStatus == 4 || toStatus == 5 || toStatus == 6);
        if (fixAction && Objects.equals(defect.assigneeId(), user.userId())) {
            return;
        }
        if (verifyAction && defect.reporterId() == user.userId()) {
            return;
        }
        throw new ServiceException("仅修复人或报告人（验证人）可执行此流转，越权操作已拒绝",
                BizErrorCode.FORBIDDEN);
    }

    
    public int memberRole(CurrentUser user, long projectId) {
        return coreProjectClient.memberRole(projectId, user.userId());
    }

    
    public void assertCanViewReport(CurrentUser user, long projectId, String reportType) {
        if ("team_board".equals(reportType)) {
            assertNotDeveloper(user, "开发人员为只读角色，不能查看团队看板");
            assertCanManage(user, projectId);
            return;
        }
        if ("personal_review".equals(reportType)) {
            assertNotDeveloper(user, "开发人员为只读角色，不能查看评审进度");
        }
        assertCanView(user, projectId);
    }

 
    public void assertCanExportReport(CurrentUser user, long projectId) {
        assertNotDeveloper(user, "开发人员为只读角色，不能导出报表");
        assertCanView(user, projectId);
    }

    
    public void assertCanExportCase(CurrentUser user, long projectId) {
        assertNotDeveloper(user, "开发人员为只读角色，不能导出用例");
        assertCanView(user, projectId);
    }

    
    public void assertCanExportDefect(CurrentUser user, long projectId) {
        assertNotDeveloper(user, "开发人员为只读角色，不能导出缺陷");
        assertCanView(user, projectId);
    }

    
    public void assertCanViewCoverage(CurrentUser user, long projectId) {
        assertCanView(user, projectId);
    }

 
    public boolean isAdmin(CurrentUser user) {
        return SystemManagerRoles.isAdmin(user == null ? null : user.roles());
    }

    
    public boolean isSystemManager(CurrentUser user) {
        return SystemManagerRoles.isSystemManager(user == null ? null : user.roles());
    }

    private void requireProjectRole(CurrentUser user, long projectId, int minRole, String message) {
        if (coreProjectClient.memberRole(projectId, user.userId()) < minRole) {
            throw new ServiceException(message, BizErrorCode.FORBIDDEN);
        }
    }

    private void assertNotDeveloper(CurrentUser user, String message) {
        if (hasRole(user, ROLE_DEVELOPER)) {
            throw new ServiceException(message, BizErrorCode.FORBIDDEN);
        }
    }

    private static boolean hasRole(CurrentUser user, String role) {
        return user != null && user.roles() != null && user.roles().contains(role);
    }
}
