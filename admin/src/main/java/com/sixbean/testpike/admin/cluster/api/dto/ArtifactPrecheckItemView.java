package com.sixbean.testpike.admin.cluster.api.dto;

/**
 * admin bridge mirror of core ArtifactPrecheckItemView (same-name direct mapping,
 * JSON passthrough — the bridge never recomputes probe verdicts).
 */
public record ArtifactPrecheckItemView(
        String componentFamily,
        String url,
        long declaredSize,
        long observedSize,
        String verdict,
        String reason) {
}
