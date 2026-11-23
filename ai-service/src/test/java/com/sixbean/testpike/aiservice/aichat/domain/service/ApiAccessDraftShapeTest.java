package com.sixbean.testpike.aiservice.aichat.domain.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.aichat.domain.component.WriteProposal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract tests of the interface pass-through change card shape (API
 * pass-through batch): the anchor stated on the card comes from the
 * conversation's server-side project segment — never from a model-echoed id,
 * because the tool description instructs the model not to supply one.
 * A slice-less caller (legacy generation link) still needs a payload anchor,
 * and no anchor at all stays an honest absence, never a bait card.
 */
class ApiAccessDraftShapeTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void sessionSliceSatisfiesTheAnchorWithoutPayloadProjectId() {
        String args = "{\"endpointKey\":\"POST /internal/tags\","
                + "\"arguments\":{\"name\":\"通用接口写验证\"}}";
        WriteProposal proposal = DraftProposalShapes.toWriteProposal(
                "apiAccess.write", "API_ACCESS_WRITE", args, mapper, 195855L);
        assertTrue(proposal != null, "card must land when the session carries a slice");
        assertTrue(proposal.summary().contains("195855"), proposal.summary());
        assertTrue(proposal.summary().contains("POST /internal/tags"), proposal.summary());
    }

    @Test
    void sessionSliceOverridesAModelClaimedProject() {
        String args = "{\"endpointKey\":\"POST /internal/tags\","
                + "\"arguments\":{\"name\":\"x\",\"projectId\":42}}";
        WriteProposal proposal = DraftProposalShapes.toWriteProposal(
                "apiAccess.write", "API_ACCESS_WRITE", args, mapper, 777L);
        assertTrue(proposal != null);
        assertTrue(proposal.summary().contains("777"), proposal.summary());
        assertTrue(!proposal.summary().contains("42"), "payload claim must not surface: " + proposal.summary());
    }

    @Test
    void legacyCallerWithoutSliceStillReadsPayloadAnchor() {
        String args = "{\"endpointKey\":\"POST /internal/tags\","
                + "\"arguments\":{\"name\":\"x\",\"projectId\":42}}";
        WriteProposal proposal = DraftProposalShapes.toWriteProposal(
                "apiAccess.write", "API_ACCESS_WRITE", args, mapper);
        assertTrue(proposal != null);
        assertTrue(proposal.summary().contains("42"), proposal.summary());
    }

    @Test
    void noAnchorAnywhereIsHonestAbsence() {
        String args = "{\"endpointKey\":\"POST /internal/tags\",\"arguments\":{\"name\":\"x\"}}";
        assertNull(DraftProposalShapes.toWriteProposal(
                "apiAccess.write", "API_ACCESS_WRITE", args, mapper, 0L));
    }

    @Test
    void blankEndpointKeyIsHonestAbsence() {
        assertNull(DraftProposalShapes.toWriteProposal(
                "apiAccess.write", "API_ACCESS_WRITE",
                "{\"endpointKey\":\"\",\"arguments\":{}}", mapper, 777L));
    }
}
