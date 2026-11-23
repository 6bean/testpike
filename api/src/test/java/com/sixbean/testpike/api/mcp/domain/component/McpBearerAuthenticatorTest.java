package com.sixbean.testpike.api.mcp.domain.component;

import com.sixbean.testpike.api.mcp.infrastructure.remote.ApiTokenVerifyClient;
import com.sixbean.testpike.api.mcp.infrastructure.remote.McpVerifyOutcome;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


class McpBearerAuthenticatorTest {

    
    private static final class RecordingVerifyClient implements ApiTokenVerifyClient {
        private String lastPlaintext;
        private int calls;
        private McpVerifyOutcome outcome = McpVerifyOutcome.invalid("无效");

        @Override
        public McpVerifyOutcome verify(String plaintext) {
            calls++;
            lastPlaintext = plaintext;
            return outcome;
        }
    }

    private final RecordingVerifyClient client = new RecordingVerifyClient();
    private final McpBearerAuthenticator authenticator = new McpBearerAuthenticator(client);

    @Test
    void 缺Authorization头_401人话_零外呼core() {
        McpAuthResult result = authenticator.authenticate(null);
        assertEquals(401, result.httpStatus(), "缺凭据必须 401");
        assertFalse(result.authenticated());
        assertNotNull(result.message());
        assertTrue(result.message().contains("Token"), "人话须指路 API Token: " + result.message());
        assertEquals(0, client.calls, "无凭据不得打 core（防探测放大）");
    }

    @Test
    void 非Bearer前缀与空白Token_401人话_零外呼core() {
        for (String header : List.of("Basic abc", "bearer tp_pat_x", "   ", "Bearer", "Bearer   ")) {
            McpAuthResult result = authenticator.authenticate(header);
            assertFalse(result.authenticated(), "非法 Authorization 形态须拒: " + header);
            assertEquals(401, result.httpStatus(), header);
        }
        assertEquals(0, client.calls, "形态非法一律本地拒，零外呼 core");
    }

    @Test
    void 吊销Token_401含吊销与人话_透传明文给core单点() {
        client.outcome = McpVerifyOutcome.revoked("该 API Token 已被吊销，请重新创建");
        McpAuthResult result = authenticator.authenticate("Bearer tp_pat_revoked");
        assertEquals(401, result.httpStatus());
        assertTrue(result.message().contains("吊销"), "逐类人话须含「吊销」: " + result.message());
        assertEquals("tp_pat_revoked", client.lastPlaintext, "明文透传 core 复算哈希（api 不自行哈希）");
    }

    @Test
    void 过期Token_401含过期人话() {
        client.outcome = McpVerifyOutcome.expired("该 API Token 已于 2026-09-01 10:00 过期，请重新创建");
        McpAuthResult result = authenticator.authenticate("Bearer tp_pat_exp");
        assertEquals(401, result.httpStatus());
        assertTrue(result.message().contains("过期"), "逐类人话须含「过期」: " + result.message());
    }

    @Test
    void 属主停用或未知_统一INVALID人话防枚举() {
        client.outcome = McpVerifyOutcome.invalid("Token 无效或属主已停用");
        McpAuthResult result = authenticator.authenticate("Bearer tp_pat_ghost");
        assertEquals(401, result.httpStatus());
        assertFalse(result.message().isBlank());
        assertFalse(result.message().contains("吊销") || result.message().contains("过期"),
                "INVALID 类不得混入其它类别文案（core 单点已分好，api 不改写）");
    }

    @Test
    void 认证成功_产出属主与scopes上下文() {
        client.outcome = McpVerifyOutcome.ok(9L, List.of("mcp.read", "mcp.case"));
        McpAuthResult result = authenticator.authenticate("Bearer tp_pat_good");
        assertTrue(result.authenticated());
        assertEquals(9L, result.principal().userId());
        assertEquals(List.of("mcp.read", "mcp.case"), result.principal().scopes());
        assertEquals("tp_pat_good", client.lastPlaintext);
    }
}
