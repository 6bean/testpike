package com.sixbean.testpike.admin.session.api.dto;

import java.util.List;

/**
 * Admin read model of the login exemption declaration on one environment
 * profile (AV5). Field names mirror the core record for direct mapping. The
 * shape carries a vault reference (credKey) but has no secret component at
 * all, so a plaintext can never ride this view back to the page (red line 3).
 *
 * @param envId        environment profile the declaration is attached to
 * @param projectId    owning project (data-isolation echo for the gates)
 * @param declared     false = honest undeclared, NOT an affirmative exemption
 * @param kind         none / master_code / whitelist_accounts
 * @param credKey      vault reference key for the master code (never a value)
 * @param accounts     code-exempt account listing
 * @param reviewMethod trial_login / manual
 * @param reviewStatus pending / passed / failed
 * @param reviewDetail honest wording of the last re-check verdict
 * @param reviewedAt   server-stamped ISO time of the last re-check
 */
public record LoginExemptView(
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
