package com.sixbean.testpike.core.cases.api.dto;

import java.util.List;

/**
 * Case flow guide answer payload (AO-9 E8, "stuck at some step — what next").
 * <p>With caseId the graph is pinned to a live case (current status + its actionable
 * events); without caseId it is the whole vocabulary map. Edge and label data are
 * derived from CaseStatusMachine/CaseStatus/CaseEvent at query time — no second
 * transition table, no second status vocabulary anywhere downstream.
 *
 * @param caseId          live case id (null = pure graph question)
 * @param caseTitle       live case title (null when no case)
 * @param currentStatus   live status code (null when no case)
 * @param currentStatusLabel live status name from CaseStatus.label()
 * @param states          every status node with its legally fireable actions
 */
public record FlowGuideInfo(Long caseId, String caseTitle, Integer currentStatus,
                            String currentStatusLabel, List<FlowGuideStateNode> states) {

    /** Actions that can run from the live case's current status (empty when terminal or no case). */
    public List<FlowGuideAction> actionsForCurrent() {
        if (currentStatus == null) {
            return List.of();
        }
        for (FlowGuideStateNode node : states) {
            if (node.code() == currentStatus) {
                return node.actions();
            }
        }
        return List.of();
    }
}
