package com.sixbean.testpike.admin.mcp.app.component;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.common.exception.BizErrorCode;

import com.sixbean.testpike.common.exception.ServiceException;
import org.springframework.stereotype.Component;


@Component
public class McpAccessGuard {

    private final ProjectAccessGuard projectAccessGuard;

    public McpAccessGuard(ProjectAccessGuard projectAccessGuard) {
        this.projectAccessGuard = projectAccessGuard;
    }

    
    public void assertCanManage(CurrentUser user) {
        if (!projectAccessGuard.isAdmin(user)) {
            throw new ServiceException("「MCP 服务」页仅系统管理员可管理：如需注册或修改 MCP 服务，请联系系统管理员用管理员账号在「MCP 服务」页操作",
                    BizErrorCode.FORBIDDEN);
        }
    }
}
