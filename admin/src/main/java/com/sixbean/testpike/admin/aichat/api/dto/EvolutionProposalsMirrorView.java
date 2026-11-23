package com.sixbean.testpike.admin.aichat.api.dto;

import java.util.List;

/**
 * Admin-side mirror of one manual self-evolution proposal run (core AF-i
 * phase 2b-ii). Field names match the core view one-to-one — the bridge
 * crosses HTTP by name, so a rename on either side silently drops a card
 * from the confirmation surface (the bridge contract test pins this).
 *
 * @param month the yyyy-MM window the signals were read from
 * @param projectId project segment the run covered
 * @param sessionId the evolution session the cards landed on (0 when the
 * window carried no complaint evidence — no cards, no session)
 * @param proposals one row per pending draft card, empty when nothing was
 * proposed (honest absence, never a fabricated card)
 */
public record EvolutionProposalsMirrorView(String month,
                                           Long projectId,
                                           long sessionId,
                                           List<ProposalMirrorRow> proposals) {

    /**
     * One generated pending draft card.
     *
     * @param draftId    tp_ai_chat_draft row — confirm it to land, never auto-lands
     * @param subKind    proposal family: PROMPT_TPL / TOOL_DEF / GROUNDING
     * @param targetName the asset the proposal names
     * @param summary    card summary the human reads before confirming
     */
    public record ProposalMirrorRow(long draftId, String subKind, String targetName, String summary) {
    }
}
