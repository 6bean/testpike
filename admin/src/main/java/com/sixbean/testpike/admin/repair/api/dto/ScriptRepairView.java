package com.sixbean.testpike.admin.repair.api.dto;


public record ScriptRepairView(Long id, Long projectId, Long caseId, Long sourceTaskId,
                               Long trialTaskId, Integer round, String proposalSummary,
                               Integer outcome, String outcomeLabel, String failNote,
                               String createdAt) {
}
