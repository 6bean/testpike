package com.sixbean.testpike.admin.cluster.api.dto;

/**
 * Client-supplied fields for one enrollment-token issuance. Deliberately no actorRole
 * component: the acting role is injected from the authenticated session inside the
 * controller, so a caller cannot smuggle a whitelisted role through the body.
 */
public record FleetEnrollIssueBody(
        String role,
        String projectScope,
        Integer maxUses,
        String remark) {
}
