package com.sixbean.testpike.core.apiaccess.api.dto;

/**
 * Governance overlay of one cataloged endpoint (the audited human judgement on top of the
 * generated catalog).
 *
 * @param endpointKey   catalog key the row governs
 * @param scopeVerified true when somebody verified this door does not leak across projects —
 *                      endpoints without a project anchor stay unreachable until a row says so
 * @param reachable     false retires the endpoint from the door without deleting the catalog entry
 * @param description   what this endpoint answers, written for the dialog reader
 * @param excludeReason why the endpoint is deliberately out of reach (shown instead of silence)
 * @param rowOwnerEntity  entity whose row the call addresses ("case"); null keeps the family-level
 *                        verdict — set it to demand a per-row project check before serving
 * @param rowOwnerIdParam the parameter carrying that row's id ("caseId"), so the door knows where
 *                        to look in the call's arguments; null with a non-null entity fails closed
 * @param searchTerms     curated user-vocabulary synonyms (comma separated, either language) that
 *                       the catalog keyword search also matches; null keeps the old search surface
 */
public record ApiAccessGovernanceView(
        String endpointKey,
        boolean scopeVerified,
        boolean reachable,
        String description,
        String excludeReason,
        String rowOwnerEntity,
        String rowOwnerIdParam,
        String requiredPermission,
        String searchTerms) {

    /** Overlay row without a permission gate (the shape registered before the third ownership face). */
    public ApiAccessGovernanceView(String endpointKey, boolean scopeVerified, boolean reachable,
                                   String description, String excludeReason,
                                   String rowOwnerEntity, String rowOwnerIdParam) {
        this(endpointKey, scopeVerified, reachable, description, excludeReason,
                rowOwnerEntity, rowOwnerIdParam, null, null);
    }

    /** Overlay row without a synonym column (the shape registered before the search corpus face). */
    public ApiAccessGovernanceView(String endpointKey, boolean scopeVerified, boolean reachable,
                                   String description, String excludeReason,
                                   String rowOwnerEntity, String rowOwnerIdParam,
                                   String requiredPermission) {
        this(endpointKey, scopeVerified, reachable, description, excludeReason,
                rowOwnerEntity, rowOwnerIdParam, requiredPermission, null);
    }

    /** Overlay row without a row-level anchor (the shape registered before row alignment). */
    public ApiAccessGovernanceView(String endpointKey, boolean scopeVerified, boolean reachable,
                                   String description, String excludeReason) {
        this(endpointKey, scopeVerified, reachable, description, excludeReason, null, null, null, null);
    }
}
