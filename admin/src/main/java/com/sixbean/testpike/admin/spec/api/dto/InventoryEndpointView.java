package com.sixbean.testpike.admin.spec.api.dto;

import java.util.List;

/**
 * One contract-endpoint row of the API inventory page (admin mirror of the
 * core DTO — fields cross the bridge by name, nothing recomputed here).
 * <p>freshness = stable | changed | removed; deprecated and removed rows are
 * still listed but excludedFromCoverage keeps the coverage denominator honest.
 * mockCovered / mockAiFallbackOnly mirror the twin-badge provenance note
 * (all-LLM answers never pretend to be real integration coverage).
 */
public record InventoryEndpointView(
        String method,
        String path,
        String endpointKey,
        String title,
        int parametersCount,
        List<String> securityRefs,
        boolean deprecated,
        boolean excludedFromCoverage,
        String freshness,
        int requestsCreated,
        int casesPromoted,
        boolean mockCovered,
        boolean mockAiFallbackOnly) {
}
