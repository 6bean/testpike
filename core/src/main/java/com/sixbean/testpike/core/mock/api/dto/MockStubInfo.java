package com.sixbean.testpike.core.mock.api.dto;

import java.time.LocalDateTime;

/**
 * Stub view — one rule row as consumed by UI, snapshot assembler and executor.
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
 * @param version       optimistic lock (V240) — an update must echo the value
 *                      this row was read with; a stale echo is refused loudly
 * @param createdAt     row creation time
 * @param updatedAt     last mutation time
 * @param enrichedBy    AI enrichment job anchor (job:{id}); null = the rule was
 *                      never improved by an enrichment run (contract-direct badge)
 * @param contractStale true when the endpoint this rule was imported for
 *                      disappeared from the contract; the row keeps its
 *                      identity and traffic history, serving is switched
 *                      off and the list view shows one honest badge
 */
public record MockStubInfo(
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
        LocalDateTime updatedAt,
        String enrichedBy,
        Boolean contractStale) {

    /**
     * 17-arg compatibility constructor (V240): callers written before the
     * optimistic-lock column exists (dialect renderers, pre-edit blades)
     * build rows without a version — null rides on, meaning "not a lock
     * echo", never a fake 1.
     */
    public MockStubInfo(Long id, Long spaceId, String name, Integer priority,
                        String method, String pathMatchType, String pathPattern,
                        String matchersJson, String responseJson,
                        String scenarioName, String requiredState, String newState,
                        String source, String sourceRef, Boolean enabled,
                        LocalDateTime createdAt, LocalDateTime updatedAt) {
        this(id, spaceId, name, priority, method, pathMatchType, pathPattern,
                matchersJson, responseJson, scenarioName, requiredState, newState,
                source, sourceRef, enabled, null, createdAt, updatedAt, null);
    }

    /**
     * 18-arg compatibility constructor (V669): callers written before the
     * enrichment provenance column build rows without an enrichment anchor.
     */
    public MockStubInfo(Long id, Long spaceId, String name, Integer priority,
                        String method, String pathMatchType, String pathPattern,
                        String matchersJson, String responseJson,
                        String scenarioName, String requiredState, String newState,
                        String source, String sourceRef, Boolean enabled,
                        Integer version, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this(id, spaceId, name, priority, method, pathMatchType, pathPattern,
                matchersJson, responseJson, scenarioName, requiredState, newState,
                source, sourceRef, enabled, version, createdAt, updatedAt, null);
    }

    /**
     * 19-arg compatibility constructor (V670): callers written before the
     * contract-removal flag exist read the row as never removed (null).
     */
    public MockStubInfo(Long id, Long spaceId, String name, Integer priority,
                        String method, String pathMatchType, String pathPattern,
                        String matchersJson, String responseJson,
                        String scenarioName, String requiredState, String newState,
                        String source, String sourceRef, Boolean enabled,
                        Integer version, LocalDateTime createdAt, LocalDateTime updatedAt,
                        String enrichedBy) {
        this(id, spaceId, name, priority, method, pathMatchType, pathPattern,
                matchersJson, responseJson, scenarioName, requiredState, newState,
                source, sourceRef, enabled, version, createdAt, updatedAt, enrichedBy, null);
    }
}
