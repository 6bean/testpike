package com.sixbean.testpike.api.mcp.infrastructure.remote;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GA1 piece A bridge contract: the core verify response carries the token's
 * pinned project ids across the HTTP bridge and the api-side mirror must keep
 * them — a silently dropped field would leave the MCP gateway unable to
 * enforce the pin (mirror fields are wired by JSON name, not by code).
 */
class RestApiTokenVerifyClientTest {

    @Test
    void verifyOutcomeParsesProjectScopeFromCoreResponse() throws Exception {
        try (StubCoreHttpServer core = StubCoreHttpServer.start()) {
            core.enqueue(StubCoreHttpServer.ok(
                    "{\"ok\":true,\"status\":\"OK\",\"message\":null,\"userId\":7,"
                            + "\"tokenId\":9,\"scopes\":[\"mcp.case\"],"
                            + "\"projectScope\":[\"3\",\"12\"]}"));
            RestApiTokenVerifyClient client =
                    new RestApiTokenVerifyClient(core.baseUrl());

            McpVerifyOutcome outcome = client.verify("tp_pat_sample");

            assertTrue(outcome.ok());
            assertEquals(List.of("3", "12"), outcome.projectScope());
        }
    }

    @Test
    void verifyOutcomeTreatsMissingProjectScopeAsUnrestricted() throws Exception {
        try (StubCoreHttpServer core = StubCoreHttpServer.start()) {
            core.enqueue(StubCoreHttpServer.ok(
                    "{\"ok\":true,\"status\":\"OK\",\"message\":null,\"userId\":7,"
                            + "\"tokenId\":9,\"scopes\":[\"mcp.read\"],"
                            + "\"projectScope\":null}"));
            RestApiTokenVerifyClient client =
                    new RestApiTokenVerifyClient(core.baseUrl());

            McpVerifyOutcome outcome = client.verify("tp_pat_sample");

            assertTrue(outcome.ok());
            assertEquals(List.of(), outcome.projectScope(),
                    "null pin = unrestricted (legacy tokens keep their current behaviour)");
        }
    }
}
