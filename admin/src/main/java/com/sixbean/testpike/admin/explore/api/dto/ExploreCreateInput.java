package com.sixbean.testpike.admin.explore.api.dto;

/**
 * Mirror of the core ExploreCreateInput five-component contract, mapped by
 * component name over JSON. envId/credRef are consumed by the mobile shape
 * only; loginStateName by the web shape only. Shapes that do not consume a
 * value pass null and the core gate lets the call through untouched.
 *
 * @param goalText goal text of the exploration
 * @param startUrl start URL (web=http(s); mobile=app://package)
 * @param envId    target environment id (tp_project_env; required for mobile)
 * @param credRef  vault credential reference (cred_key name, never plaintext)
 * @param loginStateName login-state ledger handle (tp_login_state.state_name,
 *                  web shape only; separate namespace from credRef, null=anonymous)
 */
public record ExploreCreateInput(String goalText, String startUrl, Long envId, String credRef,
                                 String loginStateName) {

    /** Four-arg delegate keeping the pre- contract intact (anonymous web/mobile shape). */
    public ExploreCreateInput(String goalText, String startUrl, Long envId, String credRef) {
        this(goalText, startUrl, envId, credRef, null);
    }

    /** Two-arg web delegate (legacy call sites stay untouched). */
    public ExploreCreateInput(String goalText, String startUrl) {
        this(goalText, startUrl, null, null, null);
    }
}
