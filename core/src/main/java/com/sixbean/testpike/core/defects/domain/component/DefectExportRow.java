package com.sixbean.testpike.core.defects.domain.component;

import java.util.List;


public record DefectExportRow(
        String defectNo,
        String title,
        String severityLabel,
        String statusLabel,
        String caseNo,
        String assigneeName,
        String reporterName,
        String externalKey,
        String description,
        String createdAt) {

    
    public List<Object> toExcelRow() {
        return List.of(
                nvl(defectNo), nvl(title), nvl(severityLabel), nvl(statusLabel), nvl(caseNo),
                nvl(assigneeName), nvl(reporterName), nvl(externalKey), nvl(description), nvl(createdAt));
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }
}
