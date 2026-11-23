package com.sixbean.testpike.core.aichat.api.dto;

import java.util.List;

/**
 * Before/after trend report of applied self-evolution proposals (AF-i phase 4).
 * <p>The comparison reuses the observation rollup the proposal run already
 * reads — both sides of the window come from the same spec, so the loop
 * never grows a second observation store. One card row is emitted per APPLIED
 * prompt-family proposal of the run window; the verdict explains itself with
 * the two follow-up rates it compared.
 *
 * @param month the yyyy-MM run window the proposals were made in
 * @param projectId project segment the report covers
 * @param afterMonth the yyyy-MM comparison window (run month + 1)
 * @param afterMonthComplete whether that comparison window has fully elapsed
 * (false = too early to judge, cards stay watching)
 * @param cards one row per applied prompt-family proposal card
 */
public record EvolutionTrendView(String month,
                                 Long projectId,
                                 String afterMonth,
                                 boolean afterMonthComplete,
                                 List<CardRow> cards) {

    /**
     * Verdict for one applied proposal card.
     *
     * @param draftId          tp_ai_chat_draft row of the applied card
     * @param targetName       template family the card landed as
     * @param verdict          watching / insufficient / improved / ineffective
     *                         (ineffective also marks the draft row in place)
     * @param beforeFollowUpRate follow-up rate inside the run window
     * @param afterFollowUpRate  follow-up rate inside the comparison window
     * @param beforeThumbsDown   👎 count inside the run window
     * @param afterThumbsDown    👎 count inside the comparison window
     */
    public record CardRow(long draftId,
                          String targetName,
                          String verdict,
                          double beforeFollowUpRate,
                          double afterFollowUpRate,
                          long beforeThumbsDown,
                          long afterThumbsDown) {
    }
}
