package com.sixbean.testpike.core.session.api.dto;

import java.util.List;

/**
 * Declaration input for the login exemption slot on an environment profile
 * (AV5). There is deliberately NO secret-value field: the master code lives
 * only in the credential vault and is referenced here by cred_key (red
 * line 3 — ciphertext single source, no second store).
 *
 * @param kind         exemption form: none / master_code / whitelist_accounts
 * @param credKey      vault reference key, required for master_code
 * @param accounts     code-exempt account listing, required for whitelist_accounts
 * @param reviewMethod how the claim gets re-checked: trial_login / manual
 */
public record LoginExemptInput(
        String kind,
        String credKey,
        List<String> accounts,
        String reviewMethod) {
}
