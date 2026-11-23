package com.sixbean.testpike.core.repair.api.dto;


public record ScriptRepairRecordInput(long projectId, long caseId, Long sourceTaskId,
                                      Long trialTaskId, String proposalSummary,
                                      int outcome, String failNote) {
}
