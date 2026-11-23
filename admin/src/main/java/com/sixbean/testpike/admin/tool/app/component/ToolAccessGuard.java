package com.sixbean.testpike.admin.tool.app.component;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.springframework.stereotype.Component;

/**
 * <ul>
 * </ul>
 *
 */
@Component
public class ToolAccessGuard {

    private final ProjectAccessGuard projectAccessGuard;

    public ToolAccessGuard(ProjectAccessGuard projectAccessGuard) {
        this.projectAccessGuard = projectAccessGuard;
    }

    
    public void assertCanManage(CurrentUser user, Long projectId) {
        if (projectId == null) {
            if (!projectAccessGuard.isAdmin(user)) {
                throw new ServiceException("系统级工具仅系统管理员可管理，请联系管理员",
                        BizErrorCode.FORBIDDEN);
            }
            return;
        }
        projectAccessGuard.assertCanManage(user, projectId);
    }

    
    public void assertAdmin(CurrentUser user) {
        requireAdmin(user, "工具调用历史仅系统管理员可查，请联系管理员");
    }

    
    public void assertAdmin(CurrentUser user, String actionSubject) {
        requireAdmin(user, actionSubject + "仅系统管理员可操作，请联系管理员");
    }

    private void requireAdmin(CurrentUser user, String message) {
        if (!projectAccessGuard.isAdmin(user)) {
            throw new ServiceException(message, BizErrorCode.FORBIDDEN);
        }
    }
}
