package com.sixbean.testpike.admin.mock.api.dto;

/**
 * Stub save input (admin bridge mirror of the core MockStubInput record). JSON
 * payload fields travel as raw text — shape validation (regex compilability,
 * matcher/response skeleton) is the core save-chain single point.
 *
 * @param name          rule name
 * @param priority      within-space evaluation order (ascending, first match wins)
 * @param method        HTTP verb, ANY as wildcard
 * @param pathMatchType exact | prefix | regex | template
 * @param pathPattern   path body under the space route
 * @param matchersJson  matcher rows as JSON array text (null/blank = [])
 * @param responseJson  response builder payload as JSON object text (null/blank = {})
 * @param scenarioName  stateful scenario membership (null = stateless)
 * @param requiredState scenario state this rule fires on (declared-state gate in core)
 * @param newState      scenario state this rule advances to (same gate)
 * @param source        manual | openapi | record | nl-ai | case-flip
 * @param sourceRef     provenance anchor (spec/recording/case id)
 * @param enabled       serving switch — disabled rules never reach the traffic plane
 * @param expectedVersion optimistic-lock echo (edit only, V240); null on create
 */
public record MockStubInput(
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
        Integer expectedVersion) {

    /**
     * 13-arg compatibility constructor: pre-V240 senders never carry a lock
     * echo (same posture as the core record).
     */
    public MockStubInput(String name, Integer priority, String method, String pathMatchType,
                         String pathPattern, String matchersJson, String responseJson,
                         String scenarioName, String requiredState, String newState,
                         String source, String sourceRef, Boolean enabled) {
        this(name, priority, method, pathMatchType, pathPattern, matchersJson, responseJson,
                scenarioName, requiredState, newState, source, sourceRef, enabled, null);
    }
}
