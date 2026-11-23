package com.sixbean.testpike.core.spec.api.dto;

/**
 * Coverage summary card of the API inventory page.
 * <p>Denominator = endpoint rows of the max(spec_version) row that are not
 * deprecated (W-c2 / review round-2 R-9). Asset counts read the folded
 * contract key equality join — the same single spec as the row list.
 *
 * @param specVersion             anchor version (0 = no such chain in project)
 * @param total                   in-coverage denominator endpoints
 * @param endpointsWithRequests   denominator rows with ≥1 saved request
 * @param uncoveredCount          total − endpointsWithRequests
 * @param coveragePercent         rounded percentage, 0 when total = 0
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
