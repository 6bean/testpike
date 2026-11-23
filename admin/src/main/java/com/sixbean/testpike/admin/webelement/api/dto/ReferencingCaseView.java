package com.sixbean.testpike.admin.webelement.api.dto;

/**
 * Admin mirror of the core referencing-case row (element drawer reference
 * list): which live case, at which step, carries anchor evidence naming the
 * ledger element. Field names match the core contract for name-based
 * deserialization.
 */
public record ReferencingCaseView(
        long caseId,
        String caseNo,
        String caseTitle,
        int caseVersion,
        int stepNo,
        String stepDescription) {
}
