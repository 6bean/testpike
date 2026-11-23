package com.sixbean.testpike.core.mcp.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.core.mcp.api.dto.McpConfirmDecisionInput;
import com.sixbean.testpike.core.mcp.api.dto.McpConfirmGrantTicket;
import com.sixbean.testpike.core.mcp.api.dto.McpConfirmOutcome;
import com.sixbean.testpike.core.mcp.api.dto.McpScriptWritebackDraftInput;
import com.sixbean.testpike.core.mcp.domain.service.McpConfirmSettlementService;
import com.sixbean.testpike.core.script.api.dto.ScriptWritebackEvidence;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wiring contract for the MCP confirmation doors : the controller hands the
 * settlement service the registered landing domain, a payload the landing handler can
 * re-read verbatim, and a plain-language digest for the conversation. The write itself
 * stays in the single landing path — this face adds no persistence route of its own.
 */
class McpConfirmDraftAssemblyTest {

    private static final class Capturing implements McpConfirmSettlementService {
        String domain;
        String payloadJson;
        String summary;
        long projectId;
        Long actorId;
        String confirmRef;
        String decision;
        String reason;
        Long operator;

        @Override
        public McpConfirmGrantTicket issueGrant(String domain, String payloadJson, String summary,
                                                long projectId, Long actorId) {
            this.domain = domain;
            this.payloadJson = payloadJson;
            this.summary = summary;
            this.projectId = projectId;
            this.actorId = actorId;
            return new McpConfirmGrantTicket("cf-1", summary, 1800L);
        }

        @Override
        public McpConfirmOutcome confirm(String confirmRef, String decision, String reason, Long operatorId) {
            this.confirmRef = confirmRef;
            this.decision = decision;
            this.reason = reason;
            this.operator = operatorId;
            return new McpConfirmOutcome("script.writeback", decision, "accept".equals(decision),
                    "已按草案执行：脚本新版本落库");
        }
    }

    private final Capturing settlement = new Capturing();
    private final McpConfirmInternalController controller =
            new McpConfirmInternalController(settlement, new ObjectMapper());

    @Test
    void scriptWritebackDraftRidesRegisteredDomainWithReReadablePayload() {
        controller.draftScriptWriteback(new McpScriptWritebackDraftInput(
                11, 7, "v2", "{\"actions\":[{\"step\":\"open\"}]}",
                new ScriptWritebackEvidence("2026-11-17T20:00:00+08:00", "mac/chrome", 9, 0, "ab12cd"), 3));

        assertEquals("script.writeback", settlement.domain, "landing registry key must match the tool domain");
        assertEquals(11, settlement.projectId);
        assertNotNull(settlement.summary, "receipt must carry a plain-language draft digest");
        assertFalse(settlement.summary.isBlank());
        assertTrue(settlement.payloadJson.contains("\"caseId\":7"), settlement.payloadJson);
        assertTrue(settlement.payloadJson.contains("open"), settlement.payloadJson);
        assertTrue(settlement.payloadJson.contains("\"logSha256\":\"ab12cd\""), settlement.payloadJson);
        assertTrue(settlement.payloadJson.contains("\"expectedVersion\":3"), settlement.payloadJson);
    }

    @Test
    void bareReportDraftWithoutEvidenceKeepsEnvelopeShape() {
        controller.draftScriptWriteback(new McpScriptWritebackDraftInput(
                11, 7, "v2", "{\"actions\":[]}", null, null));
        assertTrue(settlement.payloadJson.contains("\"caseId\":7"));
        assertFalse(settlement.payloadJson.contains("ab12cd"));
    }

    /** segment 4 (11-16 ruling, experience detail 4): the conversation digest is a
 * three-part plain-language summary — touched object / change points / blast
 * radius — and never dumps the payload json. */
    @Test
    void summaryCarriesThreePartPlainLanguageDigest() {
        controller.draftScriptWriteback(new McpScriptWritebackDraftInput(
                11, 7, "v2", "{\"actions\":[{\"step\":\"open\"}]}",
                new ScriptWritebackEvidence("2026-11-17T20:00:00+08:00", "mac/chrome", 9, 0, "ab12cd"), 3));

        String digest = settlement.summary;
        for (String part : new String[] {"动什么", "变更点", "影响面"}) {
            assertTrue(digest.contains(part), "digest must name part " + part + ": " + digest);
        }
        assertTrue(digest.contains("用例 #7"), digest);
        assertTrue(digest.contains("待评审"), digest);
        assertTrue(digest.contains("期望基于版本 3"), digest);
        assertTrue(digest.contains("站外实证"), digest);
        assertFalse(digest.contains("actionJson"), "digest must not dump the payload json");
    }

    @Test
    void summaryDigestsBareDraftAsSelfReportedWithoutVersionComparison() {
        controller.draftScriptWriteback(new McpScriptWritebackDraftInput(
                11, 7, "v2", "{\"actions\":[]}", null, null));

        String digest = settlement.summary;
        assertTrue(digest.contains("站外自报"), digest);
        assertTrue(digest.contains("不做版本比对"), digest);
        assertTrue(digest.contains("自动失效"), digest);
    }

    @Test
    void confirmDoorPassesRefDecisionAndReasonThrough() {
        controller.confirm(new McpConfirmDecisionInput("cf-9", "reject", "脚本覆盖了手工步骤"));
        assertEquals("cf-9", settlement.confirmRef);
        assertEquals("reject", settlement.decision);
        assertTrue(settlement.reason.contains("手工步骤"));
    }
}
