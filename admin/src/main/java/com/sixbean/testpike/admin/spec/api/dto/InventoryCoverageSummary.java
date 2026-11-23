package com.sixbean.testpike.admin.spec.api.dto;

/**
 * Coverage summary card mirror (denominator = max(spec_version) rows that are
 * not deprecated; the anchor version is reported so the page can show which
 * contract version the numbers describe — 0 = the name chain has no rows in
 * this project).
 */
public record InventoryCoverageSummary(
        long projectId,
        String specName,
        int specVersion,
        int total,
        int endpointsWithRequests,
        int uncoveredCount,
        int coveragePercent) {
}
