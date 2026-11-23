package com.sixbean.testpike.api.ci.domain.component;

import com.sixbean.testpike.api.mcp.domain.component.McpAuthResult;
import com.sixbean.testpike.api.mcp.domain.component.McpBearerAuthenticator;
import com.sixbean.testpike.api.mcp.domain.component.McpPrincipal;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.springframework.stereotype.Component;


@Component
public class CiBearerGate {

 
    static final String CI_SCOPE = "ci";

    private final McpBearerAuthenticator authenticator;

    public CiBearerGate(McpBearerAuthenticator authenticator) {
        this.authenticator = authenticator;
    }

    
    public McpPrincipal requireCiPrincipal(String authorizationHeader) {
        McpAuthResult result = authenticator.authenticate(authorizationHeader);
        if (!result.authenticated()) {
            throw new ServiceException(result.message(), BizErrorCode.UNAUTHORIZED);
        }
        McpPrincipal principal = result.principal();
        if (principal == null || principal.scopes().stream().noneMatch(CI_SCOPE::equals)) {
            throw new ServiceException("当前 API Token 未开通 ci 范围：请在 testPike 管理页"
                    + "「API Token」创建或编辑时勾选 CI 触发（ci）范围后重试",
                    BizErrorCode.FORBIDDEN);
        }
        return principal;
    }
}
