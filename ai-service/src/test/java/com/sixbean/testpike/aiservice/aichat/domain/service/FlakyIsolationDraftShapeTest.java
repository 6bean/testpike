package com.sixbean.testpike.aiservice.aichat.domain.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.aichat.domain.component.WriteProposal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Flaky-quarantine proposal shape of the dialog draft emitter (AO batch
 * , closed-loop operations S2 emitter side).
 * <p>The card must echo BOTH anchors the core-side landing strategy re-checks
 * — the observed case id and the version the failure analysis saw — and must
 * name the reason the human reads. A body missing any of them cannot land
 * without racing (a base_version of 0 would always conflict against a live
 * case version), so it gets no card, exactly like every other shape's
 * bait-a-failed-confirmation rule.
 */
class FlakyIsolationDraftShapeTest {

    private final ObjectMapper reader = new ObjectMapper();

    @Test
    void completeBodyLandsAsAnIsolationCardOnTheObservedCase() {
        WriteProposal proposal = DraftProposalShapes.toWriteProposal(
                "case.flaky.isolate", "FLAKY_ISOLATION",
                "{\"caseId\":42,\"baseVersion\":3,\"reason\":\"近 5 次执行失败翻转 3 次，疑似环境时序抖动\"}",
                reader);

        assertNotNull(proposal);
        assertEquals("FLAKY_ISOLATION", proposal.objectType());
        assertEquals(42L, proposal.targetId(), "targetId = the observed case id");
        assertEquals(3, proposal.baseVersion(), "baseVersion = the version the analysis saw");
        assertTrue(proposal.summary().contains("42"), "card names the case");
        assertTrue(proposal.summary().contains("近 5 次执行失败翻转 3 次"),
                "card states the reason the human confirms");
    }

    @Test
    void bodyWithoutTheCaseAnchorGetsNoCard() {
        assertNull(DraftProposalShapes.toWriteProposal(
                "case.flaky.isolate", "FLAKY_ISOLATION",
                "{\"baseVersion\":3,\"reason\":\"r\"}", reader));
    }

    @Test
    void bodyWithoutTheVersionAnchorGetsNoCard() {
        // 0/absent base_version would race the live row: the shared door
        // refuses stale echoes, so a card without the echo could only ever
        // fail on confirmation.
        assertNull(DraftProposalShapes.toWriteProposal(
                "case.flaky.isolate", "FLAKY_ISOLATION",
                "{\"caseId\":42,\"reason\":\"r\"}", reader));
    }

    @Test
    void bodyWithoutAReasonGetsNoCard() {
        // The core door falls back to default wording, but the value of this
        // card IS the stated evidence: an unnamed quarantine cannot be judged
        // on the card face, so the emitter refuses it (registered segment plan).
        assertNull(DraftProposalShapes.toWriteProposal(
                "case.flaky.isolate", "FLAKY_ISOLATION",
                "{\"caseId\":42,\"baseVersion\":3,\"reason\":\"  \"}", reader));
    }

    @Test
    void malformedBodyStaysAnHonestAbsence() {
        assertNull(DraftProposalShapes.toWriteProposal(
                "case.flaky.isolate", "FLAKY_ISOLATION", "{not json", reader));
    }

    @Test
    void switchKeyMatchesTheFactorySeedVerbatim() {
        // R32: the emitter's per-domain key literal must equal the tp_sys_config
        // factory row (V337) and the guidance frame quotes the same string.
        assertEquals("ai.chat.flaky-draft.enabled",
                ChatRunOrchestrator.KEY_FLAKY_DRAFT_ENABLED);
        assertEquals("FLAKY_ISOLATION", ChatRunOrchestrator.DRAFT_KIND_FLAKY_ISOLATION);
    }
}
