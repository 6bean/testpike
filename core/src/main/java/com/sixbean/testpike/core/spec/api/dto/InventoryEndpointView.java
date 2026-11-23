package com.sixbean.testpike.core.spec.api.dto;

import java.util.List;

/**
 * One contract-endpoint row of the API inventory page.
 * <p>freshness = stable | changed | removed (three-state lifecycle tinting):
 * removed rows exist only in the previous snapshot and are still listed for
 * traceability. deprecated and removed rows set excludedFromCoverage so the
 * coverage denominator stays honest without hiding anything from the operator.
 * <p>requestsCreated / casesPromoted come from indexed equality on
 * tp_apitest_request.contract_endpoint_key (folded match key), never a
 * per-row recompute. mockCovered is the twin-badge source (openapi-sourced
 * stubs in the project's mock spaces); mockAiFallbackOnly is the honest
 * provenance note — every recorded answer for the matching stub came from
 * the LLM fallback, so "covered" alone would be a lie.
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
