package com.sixbean.testpike.core.llmconfig.infrastructure.remote.client;

/**
 * Outbound port from the llm-config domain to the ai-service route-axis
 * golden replay : when a project's effective model route
 * changes, the registered golden packs are replayed through the old and the
 * new route so the switch ships with a measured drift verdict on the ledger.
 * Fire-and-forget on purpose — the verdict itself lands on the core drift
 * ledger through ai-service's existing internal callback, not through this leg.
 */
public interface GoldenReplayRouteClient {

    /**
     * POST /internal/golden-replay/run-route (path mirrors
     * GoldenReplayInternalController character by character).
     *
     * @param domain         prompt template domain whose golden pack is replayed
     * @param templateName   prompt template family (its released row is replayed)
     * @param controlModel   the route the project used before the change
     * @param candidateModel the route the project now uses
     * @param projectId      project that owns the replay calls (billing scoping)
     */
    void runRouteReplay(String domain, String templateName, String controlModel,
                        String candidateModel, long projectId);
}
