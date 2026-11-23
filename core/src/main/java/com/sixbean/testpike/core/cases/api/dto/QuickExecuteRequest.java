package com.sixbean.testpike.core.cases.api.dto;

/**
 * Quick execution request body for POST /cases/{id}/quick-execute, with no explicit contract definition.
 * The body definition uses the minimal parameter envId as optional placement, defaulting to the project default environment with start validation fallback returning a human-readable 400 error.
 *
 * @param browser specifies the browser for the trailing quick execution, where null or empty defaults to chromium and an unsupported environment matrix returns a human-readable 400 rejection.
 */
public record QuickExecuteRequest(Long envId, String browser) {

    /** Legacy one-parameter compatibility constructor that does not specify a browser. */
    public QuickExecuteRequest(Long envId) {
        this(envId, null);
    }
}
