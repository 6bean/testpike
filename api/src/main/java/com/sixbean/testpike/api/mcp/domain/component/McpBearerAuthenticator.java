package com.sixbean.testpike.api.mcp.domain.component;

import com.sixbean.testpike.api.mcp.infrastructure.remote.ApiTokenVerifyClient;
import com.sixbean.testpike.api.mcp.infrastructure.remote.McpVerifyOutcome;

import java.util.List;


public class McpBearerAuthenticator {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String MISSING_MESSAGE =
            "缺少访问凭据：请在 Authorization 头携带 Bearer <API Token>"
                    + "（Token 在 testPike 管理页「API Token」创建，明文仅展示一次）";

    private final ApiTokenVerifyClient verifyClient;

    public McpBearerAuthenticator(ApiTokenVerifyClient verifyClient) {
        this.verifyClient = verifyClient;
    }

    
    public McpAuthResult authenticate(String authorizationHeader) {
        if (authorizationHeader == null || authorizationHeader.isBlank()) {
            return McpAuthResult.reject(MISSING_MESSAGE);
        }
        if (!authorizationHeader.startsWith(BEARER_PREFIX)
                || authorizationHeader.substring(BEARER_PREFIX.length()).isBlank()) {
            return McpAuthResult.reject("Authorization 头格式不正确：须为「Bearer <API Token>」形态");
        }
        String plaintext = authorizationHeader.substring(BEARER_PREFIX.length()).trim();
        McpVerifyOutcome outcome;
        try {
            outcome = verifyClient.verify(plaintext);
        } catch (Exception ex) {
            return McpAuthResult.reject("认证服务暂不可用，请稍后重试（原因："
                    + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()) + "）");
        }
        if (outcome == null) {
            return McpAuthResult.reject("Token 无效或属主已停用");
        }
        if (!outcome.ok()) {
            String message = outcome.message() == null || outcome.message().isBlank()
                    ? "Token 无效或属主已停用" : outcome.message();
            return McpAuthResult.reject(message);
        }
        List<String> scopes = outcome.scopes() == null ? List.of() : outcome.scopes();
        return McpAuthResult.accept(new McpPrincipal(
                outcome.userId() == null ? 0L : outcome.userId(), outcome.tokenId(), scopes,
                outcome.projectScope()));
    }
}
