package com.sixbean.testpike.admin.explore.api.dto;

/**
 *
 * @param envId target environment id (mobile shape required; web passes null)
 * @param credRef vault credential reference (cred_key name, never a decrypted value)
 * @param loginStateName login-state ledger handle (tp_login_state.state_name; web
 * shape optional, null=anonymous explore; validation stays in core)
 */
public record ExploreCreateRequest(long projectId, String goalText, String startUrl, Boolean confirm,
                                   Long envId, String credRef, String loginStateName) {

    /** Four-arg delegate keeping the legacy web contract intact (all extras null). */
    public ExploreCreateRequest(long projectId, String goalText, String startUrl, Boolean confirm) {
        this(projectId, goalText, startUrl, confirm, null, null, null);
    }

    /** Six-arg delegate (pre- shape stays compilable, loginStateName=null). */
    public ExploreCreateRequest(long projectId, String goalText, String startUrl, Boolean confirm,
                                Long envId, String credRef) {
        this(projectId, goalText, startUrl, confirm, envId, credRef, null);
    }
}
