package com.sixbean.testpike.core.cases.domain.component;

import java.util.List;

/**
 * Export row view (P2; Excel assembly row is the flat structure after aggregation of step, label, and assignee display names completes).
 *
 */
public record CaseExportRow(
        String title,
        String precondition,
        String expectedResult,
        int priority,
        int confidence,
        String endType,
        String stepsText,
        String caseNo,
        String statusLabel,
        String tagNames,
        String assigneeName) {

    /** Excel column values (template columns 0-6 first = import round-trip consistency per PRD 4.2.5; ID/status/label/assignee appended columns 7+). */
    public List<Object> toExcelRow() {
        return List.of(
                nvl(title), nvl(precondition), nvl(expectedResult),
                String.valueOf(priority), String.valueOf(confidence), nvl(endType), nvl(stepsText),
                nvl(caseNo), nvl(statusLabel), nvl(tagNames), nvl(assigneeName));
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }
}
