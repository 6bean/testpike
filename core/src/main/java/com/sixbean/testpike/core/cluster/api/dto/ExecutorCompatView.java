package com.sixbean.testpike.core.cluster.api.dto;

/**
 * This is the executor protocol compatibility view (latest manifest interval × executor row computed on the fly, health-check card data plane).
 * It also provides the authoritative source for ledger red marking.
 * The <p> tag indicates that compatible=true and reason=null (the green state does not falsely report a reason), while compatible=false includes a human-readable reason naming the required interval.
 * A missing manifest or blank version is treated as compatible (legacy executors have no drift and unimported states are not blocked, sharing the same decision lineage as the gate).
 *
 * @param id The executor ID.
 * @param name The executor name.
 * @param version The executor-reported version (null means the legacy executor did not report it).
 * @param compatible Whether it falls within the protocol compatibility interval of the latest published manifest.
 * @param reason The human-readable incompatibility reason (null when compatible).
 */
public record ExecutorCompatView(Long id, String name, String version,
                                 Boolean compatible, String reason) {
}
