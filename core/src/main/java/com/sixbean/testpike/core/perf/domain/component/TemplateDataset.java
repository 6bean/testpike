package com.sixbean.testpike.core.perf.domain.component;

/**
 * Compile-time view of one bound CSV dataset handed to the engine templates
 * .
 *
 * @param fileName sidecar file name materialized next to the generated script
 * (k6 open resolves relative to the script directory; the
 * Gatling sidecar rides the compiled classes directory which
 * is on the engine classpath — both are the same-host artifact
 * root already used by the dispatch chain, zero new channel)
 * @param allocMode {@link PerfRowAllocModes} spelling resolved once at launch
 * (pin discipline: templates never re-read the K8 key)
 * @param vuCount virtual users the pressure config will allocate (segment
 * divisor for VUSER_EXCLUSIVE on k6, compile-time known)
 */
public record TemplateDataset(String fileName, String allocMode, int vuCount) {
}
