package com.sixbean.testpike.core.aichat.domain.service.impl;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R63 refusal contract for the self-evolution draft door.
 *
 * <p>The unknown-subKind branch fires before any collaborator is touched, so a
 * plain unit here exercises the real production throw without a Spring context
 * (no service call, no orchestration, no stub — only the message contract).
 * A refusal that only lists the accepted kinds but never tells the confirming
 * human what to DO next is a dead end on screen: it must quote the offending
 * value and name the reachable repair entry (the dialog that produced the card).
 */
class EvolutionProposalDraftGuidanceTest {

    @Test
    void unknownSubKindRefusalQuotesTheValueAndPointsToTheDialogRegeneration() {
        EvolutionProposalDraftStrategy strategy =
                new EvolutionProposalDraftStrategy(null, null, null, null);
        Map<String, Object> draftRow = Map.of(
                "id", 1L,
                "payload_json", "{\"subKind\":\"bogus_channel\"}");

        ServiceException e = assertThrows(ServiceException.class,
                () -> strategy.land(draftRow, 7L));

        assertEquals(BizErrorCode.VALIDATION_ERROR, e.getCode());
        assertTrue(e.getMessage().contains("bogus_channel"),
                "the refusal quotes the offending subKind verbatim: " + e.getMessage());
        assertTrue(e.getMessage().contains("请"),
                "the refusal carries an action word for the next step: " + e.getMessage());
        assertTrue(e.getMessage().contains("「AI 对话」"),
                "the refusal names the entry whose regeneration repairs it: " + e.getMessage());
        assertTrue(e.getMessage().contains("「提示词模板」")
                        && e.getMessage().contains("「工具定义」")
                        && e.getMessage().contains("「接地文档」"),
                "the accepted kinds travel with the refusal as correct-write examples: "
                        + e.getMessage());
    }
}
