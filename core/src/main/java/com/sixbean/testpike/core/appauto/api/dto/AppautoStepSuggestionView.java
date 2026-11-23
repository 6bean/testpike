package com.sixbean.testpike.core.appauto.api.dto;

/**
 *
 * @param objectType object dimension APP/WEB (blank rows read as APP; WE S2)
 * @param elementId WEB rows: target ledger entry id (null for APP rows)
 * @param adoptedElementRevision WEB rows: ledger revision after the approved anchor landed
 */
public record AppautoStepSuggestionView(long id, Long projectId, Long caseId, Long sourceTaskId,
                                        int stepNo, int failureClass, String anchor, String source,
                                        String proposedAction, String proposedTarget, String reason,
                                        Integer x, Integer y, int status,
                                        Integer adoptedScriptVersion, String createdAt,
                                        String objectType, Long elementId,
                                        Integer adoptedElementRevision) {
}
