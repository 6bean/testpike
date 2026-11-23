package com.sixbean.testpike.api.mcp.domain.component;

import com.sixbean.testpike.api.mcp.infrastructure.remote.ApiTokenVerifyClient;
import com.sixbean.testpike.api.mcp.infrastructure.remote.McpVerifyOutcome;
import io.modelcontextprotocol.server.transport.ServerTransportSecurityException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;


class McpTransportAuthValidatorTest {

    
    private static final class CountingVerifyClient implements ApiTokenVerifyClient {
        private int calls;
        private McpVerifyOutcome outcome = McpVerifyOutcome.invalid("无效");
        private RuntimeException boom;

        @Override
        public McpVerifyOutcome verify(String plaintext) {
            calls++;
            if (boom != null) {
                throw boom;
            }
            return outcome;
        }
    }

    private static final String OK_HEADER = "Bearer tp_pat_valid_token";

    private final CountingVerifyClient client = new CountingVerifyClient();
    private final McpBearerAuthenticator authenticator = new McpBearerAuthenticator(client);
    private Function<String, McpRateLimitDecision> limiter = key -> McpRateLimitDecision.allow();

    private McpTransportAuthValidator newValidator() {
        return new McpTransportAuthValidator(authenticator, limiter);
    }

    /**
     * Daily-quota gate wiring: the transport validator must consult the quota only
     * after authentication passed, and must surface a denial as HTTP 429 with the
     * guard message untouched (external agents read this text directly).
     */
    @Test
    void 配额拒绝_抛429透传人话_且认证失败不消耗配额() {
        java.util.concurrent.atomic.AtomicInteger quotaChecks =
                new java.util.concurrent.atomic.AtomicInteger();
        McpTransportAuthValidator validator = new McpTransportAuthValidator(
                authenticator,
                key -> McpRateLimitDecision.allow(),
                key -> {
                    quotaChecks.incrementAndGet();
                    return McpRateLimitDecision.deny("今日配额已用尽（上限 2，当前 3）mcp.quota.calls-per-token-day", 1);
                });
        client.outcome = McpVerifyOutcome.ok(7L, List.of("mcp.case"));
        ServerTransportSecurityException ex = assertThrows(ServerTransportSecurityException.class,
                () -> validator.validateHeaders(headersWith(OK_HEADER)));
        assertEquals(429, ex.getStatusCode(), "quota exhaustion must answer 429");
        assertTrue(ex.getMessage().contains("mcp.quota.calls-per-token-day"),
                "denial must quote the governance key name: " + ex.getMessage());
        assertEquals(1, quotaChecks.get(), "one authenticated request consumes exactly one quota check");

        client.outcome = McpVerifyOutcome.invalid("无效");
        assertThrows(ServerTransportSecurityException.class,
                () -> validator.validateHeaders(headersWith("Bearer tp_pat_broken")));
        assertEquals(1, quotaChecks.get(), "failed authentication must not consume quota");
    }

    private static Map<String, List<String>> headersWith(String authorization) {
        return authorization == null ? Map.of() : Map.of("Authorization", List.of(authorization));
    }

    @Test
    void 缺Authorization_抛401人话_零外呼core() {
        ServerTransportSecurityException ex = assertThrows(ServerTransportSecurityException.class,
                () -> newValidator().validateHeaders(Map.of()));
        assertEquals(401, ex.getStatusCode(), "缺凭据必须 HTTP 401");
        assertTrue(ex.getMessage() != null && ex.getMessage().contains("Token"),
                "人话须指路 API Token: " + ex.getMessage());
        assertEquals(0, client.calls, "缺头不得打 core（防探测放大）");
    }

    @Test
    void 吊销与过期Token_抛401_core人话逐类透传() {
        for (String expect : List.of("吊销", "过期")) {
            client.outcome = "吊销".equals(expect)
                    ? McpVerifyOutcome.revoked("Token 已被吊销，请重新创建")
                    : McpVerifyOutcome.expired("Token 已过期，请检查有效期设置");
            ServerTransportSecurityException ex = assertThrows(ServerTransportSecurityException.class,
                    () -> newValidator().validateHeaders(headersWith(OK_HEADER)),
                    "拒态必须抛异常: " + expect);
            assertEquals(401, ex.getStatusCode(), expect + " 必须 401");
            assertTrue(ex.getMessage().contains(expect), "core 人话逐类透传须含「" + expect + "」: " + ex.getMessage());
        }
    }

    @Test
    void 合法Token_放行_提取侧可复用principal_单核调用() {
        client.outcome = McpVerifyOutcome.ok(7L, List.of("mcp.case"));
        McpTransportAuthValidator validator = newValidator();
        try {
            validator.validateHeaders(headersWith(OK_HEADER));
        } catch (ServerTransportSecurityException ex) {
            fail("合法 Token 不应拒: " + ex.getMessage());
        }
        assertEquals(1, client.calls, "validateHeaders 单核调用 core 一次");
        Optional<McpPrincipal> principal = validator.cachedPrincipal(OK_HEADER);
        assertTrue(principal.isPresent(), "认证产物须交 contextExtractor 侧复用（同请求单核调用）");
        assertEquals(7L, principal.orElseThrow().userId());
        assertEquals(List.of("mcp.case"), principal.orElseThrow().scopes());
        assertEquals(1, client.calls, "缓存复用不得二次外呼 core");
    }

    @Test
    void 未经认证的提取侧_空产物() {
        Optional<McpPrincipal> principal = newValidator().cachedPrincipal(OK_HEADER);
        assertTrue(principal.isEmpty(), "未走 validateHeaders 的头不得凭空出 principal");
        assertEquals(0, client.calls, "提取侧只读缓存，不得触发 core 认证");
    }

    @Test
    void 限流拒_抛429人话含等待时长() {
        client.outcome = McpVerifyOutcome.ok(7L, List.of("mcp.read"));
        limiter = key -> McpRateLimitDecision.deny(
                "MCP 调用已达每分钟上限（30 次），请约 42 秒后重试", 42);
        ServerTransportSecurityException ex = assertThrows(ServerTransportSecurityException.class,
                () -> newValidator().validateHeaders(headersWith(OK_HEADER)));
        assertEquals(429, ex.getStatusCode(), "限流必须 HTTP 429");
        assertTrue(ex.getMessage().contains("42"), "人话须含等待秒数: " + ex.getMessage());
    }

    @Test
    void 未认证请求_不计限流() {
        client.outcome = McpVerifyOutcome.invalid("无效");
        int[] limiterCalls = {0};
        limiter = key -> {
            limiterCalls[0]++;
            return McpRateLimitDecision.allow();
        };
        assertThrows(ServerTransportSecurityException.class,
                () -> newValidator().validateHeaders(headersWith(OK_HEADER)));
        assertEquals(0, limiterCalls[0], "认证失败不得消耗限流额度（仅已认证请求计数，与组件层口径一致）");
    }

    @Test
    void 认证服务不可用_failClosed抛401() {
        client.boom = new RuntimeException("connection refused");
        ServerTransportSecurityException ex = assertThrows(ServerTransportSecurityException.class,
                () -> newValidator().validateHeaders(headersWith(OK_HEADER)));
        assertEquals(401, ex.getStatusCode(), "认证单点不可用=拒绝不放行（fail-closed）");
        assertTrue(ex.getMessage().contains("不可用"), "人话指路稍后重试: " + ex.getMessage());
    }
}
