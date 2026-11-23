package com.sixbean.testpike.aiservice.gen.infrastructure.remote;

/**
 * Remote mirror of one model-facing query target (AO batch P3 ;
 * contract mirror of core {@code QueryTargetView}, field names aligned,
 * physical table detail withheld on the core side by design).
 *
 * @param targetKey registry key the spec must name
 * @param dimensionColumns allow-list for filters / groupBy (comma-joined)
 * @param measureColumns allow-list for sum/avg/min/max (comma-joined)
 * @param maxRows server-side row cap the limit clamps to
 */
public record QueryTargetViewRemote(
        String targetKey,
        String dimensionColumns,
        String measureColumns,
        int maxRows) {
}
