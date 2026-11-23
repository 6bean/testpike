package com.sixbean.testpike.aiservice.aichat.domain.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.aichat.domain.component.WriteProposal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Case-rollback proposal shape of the dialog draft emitter (AM batch
 * , emitter side): the confirmed card replays the case through the
 * shared CaseService.rollback chain, so the card face must name BOTH the case
 * and the target version the human chose, and the journaled row must carry
 * the optimistic anchor the door re-checks at confirm time.
 * <p>A body missing any of them cannot land honestly — a card without a
 * target version would bait a confirmation the core strategy must refuse,
 * and a zero anchor always conflicts against a live case version — so it
 * gets no card, exactly like every other shape's bait-a-failed-confirmation
 * rule.
 */
class CaseRollbackDraftShapeTest {

    private final ObjectMapper reader = new ObjectMapper();

    @Test
    void completeBodyLandsAsARollbackCardNamingTargetVersion() {
        WriteProposal proposal = DraftProposalShapes.toWriteProposal(
                "case.rollback", "CASE_ROLLBACK",
                "{\"caseId\":7,\"targetVersion\":3,\"baseVersion\":9}",
                reader);

        assertNotNull(proposal);
        assertEquals("CASE_ROLLBACK", proposal.objectType());
        assertEquals(7L, proposal.targetId(), "targetId = the case to roll back");
        assertEquals(9, proposal.baseVersion(),
                "baseVersion = the live version the card was cut against");
        assertTrue(proposal.summary().contains("7"), "card names the case");
        assertTrue(proposal.summary().contains("3"),
                "card states the target version the human confirms");
        assertTrue(proposal.actionJson().contains("targetVersion"),
                "raw body rides through for the core-side strategy to re-parse");
    }

    @Test
    void bodyWithoutTheCaseAnchorGetsNoCard() {
        assertNull(DraftProposalShapes.toWriteProposal(
                "case.rollback", "CASE_ROLLBACK",
                "{\"targetVersion\":3,\"baseVersion\":9}", reader));
    }

    @Test
    void bodyWithoutATargetVersionGetsNoCard() {
        // The landing door refuses target versions below 1: a card without
        // the number could only ever fail on confirmation.
        assertNull(DraftProposalShapes.toWriteProposal(
                "case.rollback", "CASE_ROLLBACK",
                "{\"caseId\":7,\"baseVersion\":9}", reader));
        assertNull(DraftProposalShapes.toWriteProposal(
                "case.rollback", "CASE_ROLLBACK",
                "{\"caseId\":7,\"targetVersion\":0,\"baseVersion\":9}", reader));
    }

    @Test
    void bodyWithoutTheOptimisticAnchorGetsNoCard() {
        // 0/absent base_version would race the live row: the shared door
        // refuses stale echoes, so a card without the echo is refused here.
        assertNull(DraftProposalShapes.toWriteProposal(
                "case.rollback", "CASE_ROLLBACK",
                "{\"caseId\":7,\"targetVersion\":3}", reader));
    }

    @Test
    void malformedBodyStaysAnHonestAbsence() {
        assertNull(DraftProposalShapes.toWriteProposal(
                "case.rollback", "CASE_ROLLBACK", "{not json", reader));
    }

    @Test
    void rollbackCardsRideTheExistingCaseDraftSwitch() {
        // R32/R40: the rollback card belongs to the case-write family and
        // reuses its hot-updated switch (no second default-value source).
        assertEquals("ai.chat.case-draft.enabled",
                ChatRunOrchestrator.KEY_CASE_DRAFT_ENABLED);
        assertEquals("CASE_ROLLBACK", ChatRunOrchestrator.DRAFT_KIND_CASE_ROLLBACK);
    }
}
