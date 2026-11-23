package com.sixbean.testpike.core.mock.api.dto;

/**
 * Stub save input — one rule of the single rule source. JSON payload fields are
 * carried as raw text and shape-checked by MockRuleValidator at the save-chain
 * single point (illegal shape is a plain-word 400, never a database 500).
 *
 * @param name          rule name
 * @param priority      within-space evaluation order (ascending, first match wins)
 * @param method        HTTP verb, ANY as wildcard (stored uppercase)
 * @param pathMatchType exact | prefix | regex | template
 * @param pathPattern   path body under the space route (regex must compile at save time)
 * @param matchersJson  matcher rows as JSON array text (null/blank = [])
 * @param responseJson  response builder payload as JSON object text (null/blank = {})
 * @param scenarioName  stateful scenario membership (null = stateless)
 * @param requiredState scenario state this rule fires on (must be declared by
 *                      the machine; blank = fires in any state; illegal without
 *                      scenarioName)
 * @param newState      scenario state this rule advances to after a successful
 *                      response (same declaration gate as requiredState)
 * @param source        manual | openapi | record | nl-ai | case-flip
 * @param sourceRef     provenance anchor (spec/recording/case id)
 * @param enabled       serving switch — disabled rules never reach the traffic plane
 * @param expectedVersion optimistic-lock echo (edit only; V240). A stale echo
 *                      means someone else saved between read and submit, so
 *                      the update is refused with the plain-word conflict.
 *                      Null on create (no row to race against).
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
 * 13-arg compatibility constructor: pre-V240 callers (curl fast-create,
 * contract import, hit promote, AI confirm) never send a lock echo — the
 * edit segment only ever builds inputs through the full canonical form.
 */
    public MockStubInput(String name, Integer priority, String method, String pathMatchType,
                         String pathPattern, String matchersJson, String responseJson,
                         String scenarioName, String requiredState, String newState,
                         String source, String sourceRef, Boolean enabled) {
        this(name, priority, method, pathMatchType, pathPattern, matchersJson, responseJson,
                scenarioName, requiredState, newState, source, sourceRef, enabled, null);
    }
}
