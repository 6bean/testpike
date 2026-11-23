package com.sixbean.testpike.core.aichat.api.dto;

import java.util.List;

/**
 * Result of one manual self-evolution proposal run (AF-i phase 2b-ii).
 * <p>The run reads the observation rollup for one month window and lands
 * every generated proposal as a PENDING draft card on a dedicated evolution
 * session owned by the triggering human — nothing is written to business
 * tables here, nothing publishes itself; landing only happens behind the
 * shared draft-confirmation door. A signal window with nothing to complain
 * about produces zero cards honestly (empty list, not a fabricated proposal).
 *
 * @param month the yyyy-MM window the signals were read from
 * @param projectId project segment the run covered (null = platform-wide)
 * @param sessionId the evolution session the proposal cards were landed on
 * @param proposals one row per generated draft card, empty when the
 * window carried no actionable complaint signal
 */
public record EvolutionProposalView(String month,
                                    Long projectId,
                                    long sessionId,
                                    List<ProposalRow> proposals) {

    /**
     * One generated draft card.
     *
     * @param draftId   tp_ai_chat_draft row (pending; confirm it to land)
     * @param subKind   proposal family: PROMPT_TPL / TOOL_DEF / GROUNDING
     * @param targetName the asset the proposal names (template family, tool
     *                   name or grounding document name)
     * @param summary   card summary the human reads before confirming
     */
    public record ProposalRow(long draftId, String subKind, String targetName, String summary) {
    }
}
