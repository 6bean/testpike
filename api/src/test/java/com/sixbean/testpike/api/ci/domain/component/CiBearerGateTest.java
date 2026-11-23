package com.sixbean.testpike.api.ci.domain.component;

import com.sixbean.testpike.api.mcp.domain.component.McpBearerAuthenticator;
import com.sixbean.testpike.api.mcp.domain.component.McpPrincipal;
import com.sixbean.testpike.api.mcp.infrastructure.remote.ApiTokenVerifyClient;
import com.sixbean.testpike.api.mcp.infrastructure.remote.McpVerifyOutcome;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class CiBearerGateTest {

    
    private static final class StubVerifyClient implements ApiTokenVerifyClient {
        private int calls;
        private McpVerifyOutcome outcome = McpVerifyOutcome.ok(7L, List.of("ci"));

        @Override
        public McpVerifyOutcome verify(String plaintext) {
            calls++;
            return outcome;
        }
    }

    private final StubVerifyClient client = new StubVerifyClient();
    private final CiBearerGate gate = new CiBearerGate(new McpBearerAuthenticator(client));

    @Test
    void 缺凭据_40101且core人话原文透传() {
        ServiceException ex = assertThrows(ServiceException.class, () -> gate.requireCiPrincipal(null));
        assertEquals(BizErrorCode.UNAUTHORIZED, ex.getCode(), "缺凭据=401 语义");
        assertTrue(ex.getMessage().contains("Token"), "人话指路: " + ex.getMessage());
        assertEquals(0, client.calls, "缺头不得外呼 core（认证单点本地拒）");
    }

    @Test
    void token无效_40101透传core文案() {
        client.outcome = McpVerifyOutcome.revoked("Token 已被吊销，请在管理页重新创建");
        ServiceException ex = assertThrows(ServiceException.class,
                () -> gate.requireCiPrincipal("Bearer tp_pat_x"));
        assertEquals(BizErrorCode.UNAUTHORIZED, ex.getCode());
        assertTrue(ex.getMessage().contains("吊销"), "core 文案禁改写: " + ex.getMessage());
    }

    @Test
    void 认证通过但无ci范围_40301人话与401区分() {
        client.outcome = McpVerifyOutcome.ok(7L, List.of("mcp.case", "mcp.read"));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> gate.requireCiPrincipal("Bearer tp_pat_x"));
        assertEquals(BizErrorCode.FORBIDDEN, ex.getCode(), "scope 不足=403，与未认证 401 区分（IT-I8-01）");
        assertTrue(ex.getMessage().contains("ci"), "人话须点名 ci scope: " + ex.getMessage());
    }

    @Test
    void ci范围通过_回传principal供操作人透传() {
        client.outcome = McpVerifyOutcome.ok(4242L, List.of("ci", "mcp.read"));
        McpPrincipal principal = gate.requireCiPrincipal("Bearer tp_pat_x");
        assertNotNull(principal);
        assertEquals(4242L, principal.userId(), "userId=X-Operator-Id 透 core 审计归属原点");
        assertTrue(principal.scopes().contains("ci"));
    }
}
