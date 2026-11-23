package com.sixbean.testpike.admin.aichat.api.dto;

import java.util.List;

/**
 * Admin-side mirror of the core evolution trend report (AF-i phase 4).
 * Field names match the core view one-to-one — the bridge crosses HTTP by
 * name, so a rename on either side silently drops a column on the page (the
 * bridge contract test pins this). No card content travels the bridge: only
 * the verdict numbers a human reads before deciding to roll back.
 *
 * @param month the yyyy-MM run window the proposals were made in
 * @param projectId project segment the report covers
 * @param afterMonth the yyyy-MM comparison window (run month + 1)
 * @param afterMonthComplete whether that window has fully elapsed (false = watching)
 * @param cards one row per applied prompt-family proposal card
 */
public record EvolutionTrendMirrorView(String month,
                                       Long projectId,
                                       String afterMonth,
                                       boolean afterMonthComplete,
                                       List<CardMirrorRow> cards) {

    /**
     * Verdict for one applied proposal card.
     *
     * @param draftId            tp_ai_chat_draft row of the applied card
     * @param targetName         template family the card landed as
     * @param verdict            watching / insufficient / improved / ineffective
     * @param beforeFollowUpRate follow-up rate inside the run window
     * @param afterFollowUpRate  follow-up rate inside the comparison window
     * @param beforeThumbsDown   thumbs-down count inside the run window
     * @param afterThumbsDown    thumbs-down count inside the comparison window
     */
    public record CardMirrorRow(long draftId,
                                String targetName,
                                String verdict,
                                double beforeFollowUpRate,
                                double afterFollowUpRate,
                                long beforeThumbsDown,
                                long afterThumbsDown) {
    }
}
