package com.sixbean.testpike.api.mcp.infrastructure.remote;

import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;

import static com.sixbean.testpike.api.mcp.infrastructure.remote.McpGatewayStubHarness.OPERATOR;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gateway wiring for the in-session confirmation tools :
 * script.writeback passes the member gate, then issues exactly one confirmation draft
 * and returns the one-shot reference; mcp.review.confirm routes the human decision to
 * the settlement door. Stub discipline same as RestMcpGenToolTest — the object under
 * test is URL/method/body/operator-header assembly; core-side settle semantics are
 * pinned by their own service tests.
 */
class RestMcpConfirmToolTest {

    private McpGatewayStubHarness harness;

    @BeforeEach
    void startStub() throws IOException {
        harness = McpGatewayStubHarness.open();
    }

    @AfterEach
    void stopStub() {
        harness.close();
    }

    @Test
    void scriptWritebackPassesMemberGateThenIssuesDraftAndReturnsConfirmRef() {
        harness.core().enqueue(StubCoreHttpServer.ok("null")); // member gate passes
        harness.core().enqueue(StubCoreHttpServer.ok(
                "{\"confirmRef\":\"cf-77aa\",\"summary\":\"将把站外脚本写入用例 #7\",\"expiresInSeconds\":1800}"));

        String result = harness.gateway().invoke("script.writeback", Map.of(
                "project_id", 11,
                "case_id", 7,
                "script", "{\"actions\":[{\"step\":\"open\"}]}",
                "evidence", Map.of("ran_at", "2026-11-17T20:00:00+08:00",
                        "env_fingerprint", "win11/chrome-141", "passed", 9, "failed", 0,
                        "log_sha256", "ab12")), McpCaller.unrestricted(OPERATOR));

        assertEquals(2, harness.core().callCount(), "member gate + exactly one draft hop");
        StubCoreHttpServer.RecordedCall guard = harness.core().call(0);
        assertEquals("/internal/projects/access-check", guard.path());
        StubCoreHttpServer.RecordedCall draft = harness.core().call(1);
        assertEquals("POST", draft.method());
        assertEquals("/internal/mcp-confirm/drafts/script-writeback", draft.path());
        assertEquals(String.valueOf(OPERATOR), draft.lastOperatorHeader());
        assertTrue(draft.body().contains("\"projectId\":11"), draft.body());
        assertTrue(draft.body().contains("\"caseId\":7"), draft.body());
        assertTrue(draft.body().contains("open"), "action JSON reaches the draft: " + draft.body());
        assertTrue(draft.body().contains("\"logSha256\":\"ab12\""),
                "snake evidence keys land camel: " + draft.body());
        assertTrue(result.contains("\"confirm_ref\":\"cf-77aa\""), result);
        assertTrue(result.contains("mcp.review.confirm"), "receipt names the follow-up tool: " + result);
    }

    @Test
    void scriptWritebackRefusesNonMemberWithoutAnyDraftHop() {
        harness.core().enqueue(StubCoreHttpServer.fail("40301", "当前账号不在该项目成员范围"));
        assertThrows(ServiceException.class, () -> harness.gateway().invoke("script.writeback",
                Map.of("project_id", 11, "case_id", 7, "script", "{}"), McpCaller.unrestricted(OPERATOR)));
        assertEquals(1, harness.core().callCount(), "refusal costs only the gate hop, zero draft issued");
    }

    @Test
    void reviewConfirmRoutesDecisionAndReturnsLandedOutcome() {
        harness.core().enqueue(StubCoreHttpServer.ok(
                "{\"domain\":\"script.writeback\",\"decision\":\"accept\",\"landed\":true,\"message\":\"已按草案执行\"}"));
        String result = harness.gateway().invoke("mcp.review.confirm", Map.of(
                "confirm_ref", "cf-77aa", "decision", "accept", "reason", "已在真机复跑通过"), McpCaller.unrestricted(OPERATOR));
        assertEquals(1, harness.core().callCount());
        StubCoreHttpServer.RecordedCall hop = harness.core().call(0);
        assertEquals("POST", hop.method());
        assertEquals("/internal/mcp-confirm/confirm", hop.path());
        assertTrue(hop.body().contains("\"confirmRef\":\"cf-77aa\""), hop.body());
        assertTrue(hop.body().contains("真机复跑"), hop.body());
        assertTrue(result.contains("\"landed\":true"), result);
        assertTrue(result.contains("已按草案执行"), result);
    }

    @Test
    void reviewConfirmRejectDecisionPassesThroughZeroLanding() {
        harness.core().enqueue(StubCoreHttpServer.ok(
                "{\"domain\":\"script.writeback\",\"decision\":\"reject\",\"landed\":false,\"message\":\"已拒绝该草案，未做任何变更\"}"));
        String result = harness.gateway().invoke("mcp.review.confirm", Map.of(
                "confirm_ref", "cf-77aa", "decision", "reject"), McpCaller.unrestricted(OPERATOR));
        assertTrue(result.contains("\"landed\":false"), result);
    }
}
