package com.sixbean.testpike.api.mcp.domain.component;

import com.sixbean.testpike.api.mcp.infrastructure.remote.McpPassThroughRemoteTool;
import com.sixbean.testpike.api.mcp.infrastructure.remote.McpToolGateway;
import com.sixbean.testpike.api.mcp.infrastructure.remote.RestCoreConfigClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The pass-through tool face on the inbound MCP surface: projected, filtered by scope, and never a
 * place where an agent can be handed a change.
 */
class McpPassThroughToolRegistryTest {

    private static final class FakeGateway implements McpToolGateway {

        @Override
        public String tokenUsage(long tokenId, long operatorUserId) {
            // This fake only serves the pass-through projection surface; the
            // usage roll-up door is exercised in McpServerHandlerTest.
            throw new UnsupportedOperationException("not part of the registry surface");
        }
        private final AtomicInteger listCalls = new AtomicInteger();
        private final AtomicReference<Object> outcome = new AtomicReference<>(List.of());

        @Override
        public String invoke(String toolName, Map<String, Object> arguments,
                com.sixbean.testpike.api.mcp.infrastructure.remote.McpCaller caller) {
            throw new UnsupportedOperationException("static family is not this registry's concern");
        }

        @SuppressWarnings("unchecked")
        @Override
        public List<McpPassThroughRemoteTool> listPassThroughTools() {
            listCalls.incrementAndGet();
            Object value = outcome.get();
            if (value instanceof RuntimeException ex) {
                throw ex;
            }
            return (List<McpPassThroughRemoteTool>) value;
        }

        @Override
        public String invokePassThrough(String endpointKey, Map<String, Object> arguments,
                                        long operatorUserId) {
            return "{\"endpointKey\":\"" + endpointKey + "\",\"arguments\":" + arguments + "}";
        }
    }

    private static McpPassThroughRemoteTool tool(String name, String key) {
        return new McpPassThroughRemoteTool(name, "直读 " + key,
                "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}", key);
    }

    private static RestCoreConfigClient configReturning(long seconds) {
        RestCoreConfigClient client = mock(RestCoreConfigClient.class);
        when(client.secondsSupplier(anyString(), anyLong())).thenReturn(() -> seconds);
        return client;
    }

    @Test
    void projectedToolsAreReadToolsUnderTheReadScope() {
        FakeGateway gateway = new FakeGateway();
        gateway.outcome.set(List.of(tool("api_get_internal_cases", "GET /internal/cases")));
        McpPassThroughToolRegistry registry =
                new McpPassThroughToolRegistry(gateway, configReturning(300L));

        List<McpPassThroughToolRegistry.PassThroughTool> tools = registry.tools();
        assertEquals(1, tools.size());
        McpToolDefinition definition = tools.get(0).definition();
        assertFalse(definition.writeTool(), "对外面给出的每一件都必须是读件");
        assertEquals(McpToolCatalog.SCOPE_READ, definition.requiredScope());
        assertEquals("GET /internal/cases", registry.byName("api_get_internal_cases")
                .orElseThrow().endpointKey());
    }

    @Test
    void namesOutsideTheProjectedFamilyNeverResolve() {
        FakeGateway gateway = new FakeGateway();
        gateway.outcome.set(List.of(tool("api_get_internal_cases", "GET /internal/cases")));
        McpPassThroughToolRegistry registry =
                new McpPassThroughToolRegistry(gateway, configReturning(300L));
        assertTrue(registry.tools().size() > 0);
        assertTrue(registry.byName("get_cases").isEmpty(),
                "a static tool name must never be shadowed by the dynamic face");
        assertTrue(registry.byName("api_get_internal_cases_extra").isEmpty(),
                "an unknown projected name resolves to nothing (the handler then answers with the "
                        + "same refusal it uses for unknown static tools)");
    }

    @Test
    void refreshFailureKeepsTheLastKnownFaceAndDoesNotEmptyTheList() {
        FakeGateway gateway = new FakeGateway();
        gateway.outcome.set(List.of(tool("api_get_internal_cases", "GET /internal/cases")));
        McpPassThroughToolRegistry registry =
                new McpPassThroughToolRegistry(gateway, configReturning(0L));
        assertEquals(1, registry.tools().size());

        // Same window, door now unreachable: the face stays as it was (degraded, not blind).
        gateway.outcome.set(new IllegalStateException("core 不可用"));
        List<McpPassThroughToolRegistry.PassThroughTool> after = registry.tools();
        assertEquals(1, after.size(), "刷新失败清空工具集会让合法集成看起来坏了，禁此语义");
        assertEquals("api_get_internal_cases", after.get(0).name());
    }

    @Test
    void refreshIsWindowedNotPerRequest() {
        FakeGateway gateway = new FakeGateway();
        gateway.outcome.set(List.of(tool("api_get_internal_cases", "GET /internal/cases"),
                tool("api_get_internal_tags", "GET /internal/tags")));
        McpPassThroughToolRegistry registry =
                new McpPassThroughToolRegistry(gateway, configReturning(600L));
        registry.tools();
        registry.tools();
        registry.byName("api_get_internal_tags");
        assertEquals(1, gateway.listCalls.get(),
                "tools/list is polled by agents; the catalog only changes on redeploy");
    }

    @Test
    void rowsWithoutANameOrKeyAreSkipped() {
        FakeGateway gateway = new FakeGateway();
        gateway.outcome.set(List.of(new McpPassThroughRemoteTool("", "x", "{}", "GET /internal/x"),
                new McpPassThroughRemoteTool("api_ok", "y", "{}", "")));
        McpPassThroughToolRegistry registry =
                new McpPassThroughToolRegistry(gateway, configReturning(300L));
        assertTrue(registry.tools().isEmpty(), "半截投影不该出现在对外面");
    }

    @Test
    void projectedFaceIsOrderedStably() {
        FakeGateway gateway = new FakeGateway();
        gateway.outcome.set(List.of(tool("api_get_internal_zeta", "GET /internal/zeta"),
                tool("api_get_internal_alpha", "GET /internal/alpha")));
        McpPassThroughToolRegistry registry =
                new McpPassThroughToolRegistry(gateway, configReturning(0L));
        List<String> names = registry.tools().stream()
                .map(McpPassThroughToolRegistry.PassThroughTool::name).toList();
        assertEquals(List.of("api_get_internal_alpha", "api_get_internal_zeta"), names);
    }
}
