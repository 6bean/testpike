package com.sixbean.testpike.core.spec.api.dto;

/**
 * Explicit re-analysis request for the contract change queue (core internal).
 *
 * @param newSpecId      contract version to re-analyze against its previous version
 * @param detectedSource upload | fetch | poll — recorded on the refreshed queue row
 */
public record SpecChangeReanalyzeRequest(long newSpecId, String detectedSource) {
}
