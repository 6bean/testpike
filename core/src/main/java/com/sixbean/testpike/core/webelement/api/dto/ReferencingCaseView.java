package com.sixbean.testpike.core.webelement.api.dto;

/**
 * One referencing case step row for the element ledger detail drawer: which
 * live case (and at which step) carries anchor evidence naming this element.
 * The version carried is the case's current version at read time, so the UI
 * can deep-link the exact revision the list was produced against.
 */
public record ReferencingCaseView(
        long caseId,
        String caseNo,
        String caseTitle,
        int caseVersion,
        int stepNo,
        String stepDescription) {
}
