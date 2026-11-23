package com.sixbean.testpike.api.perf.ci.infrastructure.remote.client;

import java.util.Map;

/**
 * Core perf bridge for the CI intake doors (api→HTTP→core /internal/perf
 * family, service-layering law: outside systems enter only through api).
 * <p>All responses unwrap through RestClientInvoker: core business failures
 * (404 cross-project, launch-gate refusals) surface as ServiceException with
 * the original plain wording — the door rewrites nothing and computes nothing
 * (verdict and view payloads are core's single-source results passed through).
 */
public interface PerfCiClient {

    /**
     * Launch one perf run through the core chain (gate + precheck + pinned
     * snapshot all decide inside core; refusals propagate as-is).
     *
     * @return the new run id assigned by core
     */
    long launch(long projectId, long scenarioId, Long envId, long operatorId);

    /** Run detail for status polling (GET /internal/perf/runs/{runId}). */
    Map<String, Object> view(long runId, long projectId);

    /** CI gate verdict (GET /internal/perf/runs/{runId}/ci-verdict): the core
     *  SLA judge head's result, forwarded untouched. */
    Map<String, Object> ciVerdict(long runId, long projectId);
}
