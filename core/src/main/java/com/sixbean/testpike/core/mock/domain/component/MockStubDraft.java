package com.sixbean.testpike.core.mock.domain.component;

/**
 * Save-ready draft of one mock stub produced by deterministic contract
 * mapping (design F1 path 1 — zero tokens). Field shapes mirror
 * {@code MockStubInput} so the import wizard posts a draft straight into the
 * normal stub save chain: the draft is a proposal, never a bypass — every
 * rule still passes MockRuleValidator at save time.
 *
 * @param endpointKey   contract key (METHOD + normalized path) the draft came from
 * @param name          rule name (contractor title, clamped to column width)
 * @param method        HTTP verb as stored (uppercase, kernel-served only)
 * @param pathMatchType exact | template (chosen from the path shape)
 * @param pathPattern   path under the space route
 * @param matchersJson  matcher rows JSON text ("[]" — contract matching is path-level)
 * @param responseJson  response builder document (status/headers/bodyMode/body)
 * @param source        provenance family (openapi for every draft of this mapper)
 * @param sourceRef     provenance anchor (spec:{id}) for stale badges and retracing
 * @param enrichedBy    AI enrichment job anchor (job:{id}) when an enrichment
 *                      run improved this draft; null for contract-direct rows
 */
public record MockStubDraft(
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
     * Compatibility constructor for contract-direct drafts (V669): rows the
     * deterministic mapper produced never saw an AI enrichment job, so the
     * enrichment slot rides on null and the column stays NULL on save.
     */
    public MockStubDraft(String endpointKey, String name, String method, String pathMatchType,
                         String pathPattern, String matchersJson, String responseJson,
                         String source, String sourceRef) {
        this(endpointKey, name, method, pathMatchType, pathPattern, matchersJson, responseJson,
                source, sourceRef, null);
    }

    /** Provenance family stamped by contract-direct mapping (tp_mock_stub.source CHECK). */
    public static final String SOURCE_OPENAPI = "openapi";

    /** Rule name column width — overlong contractor titles clamp instead of failing the save. */
    static final int NAME_MAX = 120;

    static String clampName(String raw) {
        String text = raw == null || raw.isBlank() ? "未命名规则" : raw.trim();
        return text.length() <= NAME_MAX ? text : text.substring(0, NAME_MAX);
    }
}
