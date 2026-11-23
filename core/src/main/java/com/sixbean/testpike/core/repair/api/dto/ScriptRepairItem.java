package com.sixbean.testpike.core.repair.api.dto;


public record ScriptRepairItem(Long id, Long projectId, Long caseId, Long sourceTaskId,
                               Long trialTaskId, Integer round, String proposalSummary,
                               Integer outcome, String outcomeLabel, String failNote,
                               String createdAt) {
}
