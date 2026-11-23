package com.sixbean.testpike.admin.appauto.api.dto;


public record AppautoStepSuggestionView(long id, Long projectId, Long caseId, Long sourceTaskId,
                                        int stepNo, int failureClass, String anchor, String source,
                                        String proposedAction, String proposedTarget, String reason,
                                        Integer x, Integer y, int status,
                                        Integer adoptedScriptVersion, String createdAt) {
}
