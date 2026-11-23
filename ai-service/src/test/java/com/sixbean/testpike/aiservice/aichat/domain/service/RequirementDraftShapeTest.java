package com.sixbean.testpike.aiservice.aichat.domain.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.aichat.domain.component.WriteProposal;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Requirement-write proposal shape of the dialog draft emitter (AL batch
 * , emitter side) plus the switch-table assembly assertion for the same
 * kind.
 * <p>Which side of the door the row lands on is decided by the tool NAME
 * (one kind, two tools — the same posture as the case and defect families):
 * a create must carry its title and an edit must carry BOTH the requirement
 * id and the version the model read, because the landing strategy re-checks
 * the optimistic echo through the shared versioned update chain. A body
 * missing its own anchors could only ever bait a failed confirmation, so it
 * gets no card — the honest-absence rule of every other shape here.
 * <p>The assembly half pins the one-key-per-kind invariant: REQUIREMENT_WRITE
 * owns exactly one draft switch key and the table size only ever grows by
 * whole kinds (a kind added to the shapes without a key row here fails).
 */
class RequirementDraftShapeTest {

    private final ObjectMapper reader = new ObjectMapper();

    @Test
    void createWithItsTitleLandsAsANewRequirementCard() {
        WriteProposal proposal = DraftProposalShapes.toWriteProposal(
                "requirement.create", "REQUIREMENT_WRITE",
                "{\"title\":\"支付超时需自动重试\",\"acceptanceCriteria\":\"30s 内自动重试 3 次\","
                        + "\"chapter\":\"支付中心\"}", reader);

        assertNotNull(proposal);
        assertEquals("REQUIREMENT_WRITE", proposal.objectType());
        assertEquals(0L, proposal.targetId(), "no row exists yet — the landing chain pins the id back");
        assertEquals(0, proposal.baseVersion(), "a create carries no optimistic anchor");
        assertTrue(proposal.summary().contains("支付超时需自动重试"), "card names the title the human confirms");
        assertTrue(proposal.summary().contains("当前会话项目"),
                "card states that the owning project comes from the session, never the payload");
    }

    @Test
    void createWithoutATitleGetsNoCard() {
        assertNull(DraftProposalShapes.toWriteProposal(
                "requirement.create", "REQUIREMENT_WRITE",
                "{\"acceptanceCriteria\":\"ac\"}", reader));
    }

    @Test
    void editWithBothAnchorsLandsAsAnEditCardNamingItsVersion() {
        WriteProposal proposal = DraftProposalShapes.toWriteProposal(
                "requirement.update", "REQUIREMENT_WRITE",
                "{\"requirementId\":7,\"baseVersion\":2,\"title\":\"支付超时需自动重试\","
                        + "\"acceptanceCriteria\":\"改后的验收标准\"}", reader);

        assertNotNull(proposal);
        assertEquals(7L, proposal.targetId(), "targetId = the requirement the edit names");
        assertEquals(2, proposal.baseVersion(), "baseVersion = the version the model read");
        assertTrue(proposal.summary().contains("7"), "card names the row");
        assertTrue(proposal.summary().contains("第 2 版"),
                "card states the optimistic anchor so a stale confirm is a visible refusal, not a silent race");
    }

    @Test
    void editWithoutTheVersionAnchorGetsNoCard() {
        // A 0/absent base_version would race whoever edited in between: the
        // shared save door refuses stale echoes, so the card must not exist.
        assertNull(DraftProposalShapes.toWriteProposal(
                "requirement.update", "REQUIREMENT_WRITE",
                "{\"requirementId\":7,\"title\":\"t\"}", reader));
    }

    @Test
    void editWithoutTheRowAnchorGetsNoCard() {
        assertNull(DraftProposalShapes.toWriteProposal(
                "requirement.update", "REQUIREMENT_WRITE",
                "{\"baseVersion\":2,\"title\":\"t\"}", reader));
    }

    @Test
    void unparsableBodyGetsNoCard() {
        assertNull(DraftProposalShapes.toWriteProposal(
                "requirement.create", "REQUIREMENT_WRITE", "{not json", reader));
    }

    @Test
    void theDraftSwitchTableCarriesTheRequirementKindWithItsOwnKey() throws Exception {
        Field field = ChatRunOrchestrator.class.getDeclaredField("DRAFT_SWITCH_KEYS");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, String> keys = (Map<String, String>) field.get(null);

        assertEquals("ai.chat.requirement-draft.enabled", keys.get("REQUIREMENT_WRITE"),
                "off-state guidance must name this kind's key verbatim");
        assertEquals(24, keys.size(),
                "every emitter kind owns exactly one switch key "
                        + "(21 carried kinds + REQUIREMENT_WRITE + API_ACCESS_WRITE + LOGIN_STATE_WRITE)");
    }
}
