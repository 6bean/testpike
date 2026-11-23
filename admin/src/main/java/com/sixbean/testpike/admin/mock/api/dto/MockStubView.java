package com.sixbean.testpike.admin.mock.api.dto;

import java.time.LocalDateTime;

/**
 * Stub view (admin bridge; field-for-field mirror of the core MockStubInfo
 * record). Rows arrive already in evaluation order (priority asc) — the bridge
 * never re-sorts, first-match semantics depend on the core ordering.
 *
 * @param id            row id
 * @param spaceId       owning space
 * @param name          rule name
 * @param priority      within-space evaluation order
 * @param method        HTTP verb (uppercase, ANY = wildcard)
 * @param pathMatchType exact | prefix | regex | template
 * @param pathPattern   path body under the space route
 * @param matchersJson  matcher rows (JSON array text)
 * @param responseJson  response builder payload (JSON object text)
 * @param scenarioName  stateful scenario membership (null = stateless)
 * @param requiredState scenario state this rule fires on
 * @param newState      scenario state this rule advances to
 * @param source        provenance class
 * @param sourceRef     provenance anchor
 * @param enabled       serving switch
 * @param version       optimistic lock (V240) — the edit door echoes it back
 * @param createdAt     row creation time
 * @param updatedAt     last mutation time
 */
public record MockStubView(
        Long id,
        Long spaceId,
        String name,
        Integer priority,
        String method,
        String pathMatchType,
        String pathPattern,
        String matchersJson,
        String responseJson,
        String scenarioName,
        String requiredState,
        String newState,
        String source,
        String sourceRef,
        Boolean enabled,
        Integer version,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
