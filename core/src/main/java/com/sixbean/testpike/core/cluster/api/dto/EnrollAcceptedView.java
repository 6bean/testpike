package com.sixbean.testpike.core.cluster.api.dto;

/**
 * A successful redemption response packet returns the installkit enroll exchange result with the role and project scope to the new machine.
 * The page node card turns green from "waiting to join" based on this.
 */
public record EnrollAcceptedView(
        Long tokenId,
        String role,
        String projectScope) {
}
