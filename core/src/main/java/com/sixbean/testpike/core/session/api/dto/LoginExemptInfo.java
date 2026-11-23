package com.sixbean.testpike.core.session.api.dto;

import java.util.List;

/**
 * Read view of the login exemption declaration on one environment profile
 * (AV5). {@code declared=false} is the honest undeclared state — it is NOT
 * the same as "kind=none" (that is an affirmative exemption claim).
 *
 * @param envId        environment profile the declaration is attached to
 * @param projectId    owning project (data-isolation echo for the admin gate)
 * @param declared     whether a structured declaration exists
 * @param kind         none / master_code / whitelist_accounts
 * @param credKey      vault reference key for the master code (never a value)
 * @param accounts     code-exempt account listing
 * @param reviewMethod trial_login / manual
 * @param reviewStatus pending / passed / failed
 * @param reviewDetail honest reason kept on a failed or noted review
 * @param reviewedAt   ISO timestamp of the last review write-back
 */
public record LoginExemptInfo(
        long envId,
        long projectId,
        boolean declared,
        String kind,
        String credKey,
        List<String> accounts,
        String reviewMethod,
        String reviewStatus,
        String reviewDetail,
        String reviewedAt) {
}
