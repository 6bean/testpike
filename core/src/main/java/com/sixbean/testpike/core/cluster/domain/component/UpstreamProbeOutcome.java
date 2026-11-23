package com.sixbean.testpike.core.cluster.domain.component;

/**
 * Upstream probe single-family result (internal value object in a separate class file; inner classes are forbidden).
 *
 * @param version the latest upstream version (null means this probe did not obtain it).
 * @param reason human-readable failure reason (null on success), retained in the result for traceability and never swallowed.
 */
public record UpstreamProbeOutcome(String version, String reason) {

    public static UpstreamProbeOutcome ok(String version) {
        return new UpstreamProbeOutcome(version, null);
    }

    public static UpstreamProbeOutcome failed(String reason) {
        return new UpstreamProbeOutcome(null, reason);
    }
}
