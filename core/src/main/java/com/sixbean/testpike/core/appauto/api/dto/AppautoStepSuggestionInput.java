package com.sixbean.testpike.core.appauto.api.dto;

/**
 *
 * @param objectType object dimension APP/WEB (blank = APP, zero migration for legacy callers; WE S2)
 * @param elementId WEB dimension: target ledger entry id (always null for APP rows)
 */
public record AppautoStepSuggestionInput(
        Long taskId,
        Integer stepNo,
        Integer failureClass,
        String anchor,
        String source,
        String proposedAction,
        String proposedTarget,
        String reason,
        Integer x,
        Integer y,
        String objectType,
        Long elementId) {

    /** APP-shape compatibility constructor: legacy call sites keep compiling. */
    public AppautoStepSuggestionInput(Long taskId, Integer stepNo, Integer failureClass,
                                      String anchor, String source, String proposedAction,
                                      String proposedTarget, String reason, Integer x, Integer y) {
        this(taskId, stepNo, failureClass, anchor, source, proposedAction, proposedTarget,
                reason, x, y, null, null);
    }
}
