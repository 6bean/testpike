package com.sixbean.testpike.api.perf.ci.api.dto;

/**
 * CI perf-run intake body (02 §I8 POST /open/perf/ci/runs contract).
 * <p>{@code projectId} and {@code scenarioId} are mandatory and checked at this
 * intake; {@code envId} is optional — absent means "use the environment bound
 * to the scenario", and the override decision (GB-1) stays single-point in the
 * core launch chain.
 *
 * @param projectId owning project id (ownership re-checked in core, never here)
 * @param scenarioId perf scenario to launch
 * @param envId     optional environment override for this run
 */
public record PerfCiRunInput(Long projectId, Long scenarioId, Long envId) {
}
