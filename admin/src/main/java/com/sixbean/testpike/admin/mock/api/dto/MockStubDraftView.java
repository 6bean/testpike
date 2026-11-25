package com.sixbean.testpike.admin.mock.api.dto;

/**
 * Contract-direct stub draft proposed by the core deterministic mapper
 * — a field-for-field mirror of the core draft record so
 * the bridge passes it through untouched: the draft is a save-ready proposal
 * for the import wizard, never a bypass of the core save-chain single point.
 *
 * @param endpointKey contract key (METHOD + normalized path) the draft came from
 * @param name rule name (contractor title, clamped to column width)
 * @param method HTTP verb as stored (uppercase, kernel-served only)
 * @param pathMatchType exact | template (chosen from the path shape)
 * @param pathPattern path under the space route
 * @param matchersJson matcher rows JSON text ("[]" — contract matching is path-level)
 * @param responseJson response builder document (status/headers/bodyMode/body)
 * @param source provenance family (openapi for every contract-direct draft)
 * @param sourceRef provenance anchor (spec:{id}) for stale badges and retracing
 * @param enrichedBy AI enrichment job anchor (job:{id}) when the draft was
 *                  improved by a bulk enrichment task; null = contract-direct
 *                  (mirrors the core MockStubDraft component order)
 */
public record MockStubDraftView(
        String endpointKey,
        String name,
        String method,
        String pathMatchType,
        String pathPattern,
        String matchersJson,
        String responseJson,
        String source,
        String sourceRef,
        String enrichedBy) {

    /**
     * 9-arg compatibility constructor for pre-enrichment bridge fixtures and
     * producers: the enrichment anchor simply rides on null (never improved).
     */
    public MockStubDraftView(String endpointKey, String name, String method, String pathMatchType,
                             String pathPattern, String matchersJson, String responseJson,
                             String source, String sourceRef) {
        this(endpointKey, name, method, pathMatchType, pathPattern, matchersJson, responseJson,
                source, sourceRef, null);
    }
}
