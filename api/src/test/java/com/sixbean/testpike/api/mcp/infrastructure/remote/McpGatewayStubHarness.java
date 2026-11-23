package com.sixbean.testpike.api.mcp.infrastructure.remote;

import java.io.IOException;

/**
 * Shared lifecycle of the stub-core harness used by MCP gateway tests (single source, R40).
 * <p>Each gateway test class previously repeated the same start/field/stop block; new tests
 * open the harness instead of copying it. The two legacy gateway/gen classes keep their own
 * copies until their next closing segment migrates onto this harness (stock debt, not widened).
 */
final class McpGatewayStubHarness implements AutoCloseable {

    /** Operator identity every gateway test passes through the hop chain. */
    static final long OPERATOR = 4242L;

    private final StubCoreHttpServer core;
    private final RestMcpToolGateway gateway;

    private McpGatewayStubHarness(StubCoreHttpServer core, RestMcpToolGateway gateway) {
        this.core = core;
        this.gateway = gateway;
    }

    static McpGatewayStubHarness open() throws IOException {
        StubCoreHttpServer server = StubCoreHttpServer.start();
        return new McpGatewayStubHarness(server, new RestMcpToolGateway(server.baseUrl()));
    }

    StubCoreHttpServer core() {
        return core;
    }

    RestMcpToolGateway gateway() {
        return gateway;
    }

    @Override
    public void close() {
        core.close();
    }
}
