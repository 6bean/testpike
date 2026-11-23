package com.sixbean.testpike.admin.session.api.dto;

import java.util.List;

/**
 * Declaration payload for the login exemption slot (AV5). It references the
 * master code only by cred_key — there is no value/secret component, which
 * the bridge contract test pins structurally (red line 3). Form rules and
 * the vault cross-check stay single-sourced in core; this DTO carries no
 * validation logic of its own (red line 6).
 *
 * @param kind         none / master_code / whitelist_accounts
 * @param credKey      vault reference key, required for master_code
 * @param accounts     code-exempt account listing, required for whitelist_accounts
 * @param reviewMethod how the claim gets re-checked: trial_login / manual
 */
public record LoginExemptDeclareRequest(
        String kind,
        String credKey,
        List<String> accounts,
        String reviewMethod) {
}
