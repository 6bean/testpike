package com.sixbean.testpike.admin.spec.api.dto;

/**
 * Explicit re-analysis request for one contract version (admin external body).
 *
 * @param newSpecId      version to re-analyze against its previous version
 * @param detectedSource upload | fetch | poll (null = poll)
 */
public record SpecChangeReanalyzeRequest(long newSpecId, String detectedSource) {
}
